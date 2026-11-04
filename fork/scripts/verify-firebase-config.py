#!/usr/bin/env python3
"""Preflight check for Google Firebase config files, before pnpm/Gradle runs.

Why this exists: the r11 cloud builds (Windows + HB) shipped only the *release*
google-services.json, so `:app:processGithubDebugGoogleServices` failed late in
the build with a missing/incorrect client. This script fails before Gradle instead,
and names the exact path plus the package that is missing.

Checks (no secrets are ever printed -- only file paths, package names and
booleans):
  1. app/build.gradle.kts gives applicationId + debug applicationIdSuffix
     (so a future suffix change is picked up automatically).
  2. app/google-services.json exists and is valid JSON.
  3. app/src/debug/google-services.json exists and is valid JSON.
  4. Each file has the structure the google-services Gradle plugin needs:
     project_info.project_id/project_number, at least one client, and every
     client carries client_info.android_client_info.package_name (plus at
     least one api_key.current_key for the release file).
  5. Release file contains the base applicationId; debug file contains the
     base + suffix (the .debug variant). A debug file holding only the release
     package is the exact bug this script is meant to stop.

Usage:
    python3 fork/scripts/verify-firebase-config.py [--root .] [--mode both]
    --mode release   only validate app/google-services.json
    --mode debug     only validate app/src/debug/google-services.json
    --mode both      validate both (default when both files exist)

Exit codes: 0 ok, 2 cannot read build.gradle.kts, 3 file missing,
4 package mismatch, 5 JSON/structure problem, 6 unreadable file.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

EXIT_GRADLE = 2
EXIT_MISSING = 3
EXIT_PACKAGE = 4
EXIT_STRUCTURE = 5
EXIT_UNREADABLE = 6


class Failure(Exception):
    def __init__(self, code, reason, path=None, package=None, detail=None):
        super().__init__(reason)
        self.code = code
        self.reason = reason
        self.path = path
        self.package = package
        self.detail = detail


def fail(code, reason, path=None, package=None, detail=None):
    raise Failure(code, reason, path, package, detail)


def read_text(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError as exc:
        fail(EXIT_UNREADABLE, "cannot read file: %s" % exc.strerror, path=str(path))


APPLICATION_ID_RE = re.compile(r"""applicationId\s*=\s*["']([^"']+)["']""")
SUFFIX_RE = re.compile(r"""applicationIdSuffix\s*=\s*["']([^"']+)["']""")


def read_gradle_identity(root):
    """Return (application_id, debug_suffix). Tolerates Kotlin/Groovy forms."""
    path = os.path.join(root, "app", "build.gradle.kts")
    if not os.path.isfile(path):
        path = os.path.join(root, "app", "build.gradle")
    if not os.path.isfile(path):
        fail(EXIT_GRADLE, "app/build.gradle.kts not found under --root", path=path)
    text = read_text(path)
    m = APPLICATION_ID_RE.search(text)
    if not m:
        fail(EXIT_GRADLE, "no applicationId found in app/build.gradle.kts", path=path)
    app_id = m.group(1).strip()
    sm = SUFFIX_RE.search(text)
    suffix = sm.group(1).strip() if sm else ""
    if not app_id:
        fail(EXIT_GRADLE, "empty applicationId in app/build.gradle.kts", path=path)
    return app_id, suffix, path


def load_config(path):
    if not os.path.isfile(path):
        fail(EXIT_MISSING, "firebase config file missing", path=path)
    try:
        with open(path, "rb") as source:
            raw = source.read()
    except OSError as exc:
        fail(EXIT_UNREADABLE, "cannot read firebase config: %s" % exc.strerror, path=path)
    if not raw.strip():
        fail(EXIT_STRUCTURE, "firebase config file is empty", path=path)
    try:
        data = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        fail(EXIT_STRUCTURE, "firebase config is not valid JSON (%s)" % type(exc).__name__, path=path)
    if not isinstance(data, dict):
        fail(EXIT_STRUCTURE, "firebase config root is not a JSON object", path=path)
    return data


def check_structure(data, path, label):
    info = data.get("project_info")
    if not isinstance(info, dict) or not info.get("project_id") or not info.get("project_number"):
        fail(EXIT_STRUCTURE, "%s config has no project_info.project_id" % label, path=path)
    clients = data.get("client")
    if not isinstance(clients, list) or not clients:
        fail(EXIT_STRUCTURE, "%s config has no client[] entries" % label, path=path)
    packages = []
    has_api_key = False
    for index, client in enumerate(clients):
        if not isinstance(client, dict):
            fail(EXIT_STRUCTURE, "%s config client[%d] is not an object" % (label, index), path=path)
        cinfo = client.get("client_info")
        if not isinstance(cinfo, dict):
            fail(EXIT_STRUCTURE, "%s config client[%d] has no client_info" % (label, index), path=path)
        if not cinfo.get("mobilesdk_app_id"):
            fail(EXIT_STRUCTURE, "%s client has no mobilesdk_app_id" % label, path=path)
        android = cinfo.get("android_client_info")
        if not isinstance(android, dict) or not android.get("package_name"):
            fail(EXIT_STRUCTURE, "%s config client[%d] has no client_info.android_client_info.package_name"
                 % (label, index), path=path)
        packages.append(str(android["package_name"]).strip())
        for key in client.get("api_key") or []:
            if isinstance(key, dict) and key.get("current_key"):
                has_api_key = True
    return packages, has_api_key


def verify_one(root, mode, expected_package):
    rel = os.path.join("app", "src", "debug", "google-services.json") if mode == "debug" \
        else os.path.join("app", "google-services.json")
    path = os.path.join(root, rel)
    data = load_config(path)
    packages, has_api_key = check_structure(data, path, mode)
    if expected_package not in packages:
        fail(EXIT_PACKAGE,
             "%s firebase config does not contain the expected package" % mode,
             path=path,
             package=expected_package,
             detail="file packages: " + ", ".join(sorted(set(packages))))
    if mode == "release" and not has_api_key:
        fail(EXIT_STRUCTURE, "release config has no api_key.current_key entry", path=path)
    return {"mode": mode, "path": path, "package": expected_package,
            "clients": len(packages), "hasApiKey": has_api_key}


def main(argv=None):
    parser = argparse.ArgumentParser(description="Validate Firebase google-services.json files before Gradle.")
    parser.add_argument("--root", default=None, help="project root, default: nearest dir with app/build.gradle.kts")
    parser.add_argument("--mode", choices=("release", "debug", "both"), default="both")
    parser.add_argument("--json", action="store_true", help="emit one JSON line")
    args = parser.parse_args(argv)

    root = args.root or os.getcwd()
    root = os.path.abspath(root)
    try:
        app_id, suffix, gradle_path = read_gradle_identity(root)
        debug_id = app_id + suffix
        checks = []
        if args.mode in ("release", "both"):
            checks.append(verify_one(root, "release", app_id))
        if args.mode in ("debug", "both"):
            checks.append(verify_one(root, "debug", debug_id))
    except Failure as exc:
        payload = {"ok": False, "reason": exc.reason, "path": exc.path,
                   "missingPackage": exc.package, "detail": exc.detail}
        print("FIREBASE_CONFIG_FAIL " + json.dumps(payload, sort_keys=True))
        return exc.code

    result = {"ok": True, "root": root, "gradle": gradle_path, "applicationId": app_id,
              "debugApplicationId": debug_id, "checks": checks}
    if args.json:
        print(json.dumps(result, sort_keys=True))
    else:
        for check in checks:
            print("FIREBASE_CONFIG_OK mode=%s package=%s clients=%d path=%s"
                  % (check["mode"], check["package"], check["clients"], check["path"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
