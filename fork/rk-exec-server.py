#!/usr/bin/env python3
"""RikkaHub fork 远程执行后端。

协议：
  POST /exec        {"command","cwd","timeout"} -> 同步执行
  POST /job/start   {"command","cwd"}          -> 后台任务
  POST /job/status  {"id","tail"}              -> 状态 + 日志尾部
  POST /job/kill    {"id"}                     -> 结束任务

鉴权：Authorization: Bearer <RK_EXEC_TOKEN>
只监听回环，公网由 nginx + TLS 反代。
"""
import json
import os
import signal
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("RK_EXEC_TOKEN", "")
ROOT = os.environ.get("RK_EXEC_ROOT", os.path.expanduser("~/rk-work"))
JOBS_DIR = os.environ.get("RK_EXEC_JOBS", os.path.expanduser("~/rk-jobs"))
MAX_OUTPUT = 128 * 1024
MAX_TAIL = 64 * 1024

JOBS = {}
LOCK = threading.Lock()


def trim(text: bytes) -> tuple[str, bool]:
    if len(text) <= MAX_OUTPUT:
        return text.decode("utf-8", "replace"), False
    return text[:MAX_OUTPUT].decode("utf-8", "replace"), True


def read_tail(path: str, lines: int) -> tuple[str, bool]:
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as fh:
            fh.seek(max(0, size - MAX_TAIL))
            raw = fh.read()
        text = raw.decode("utf-8", "replace")
        tail = "\n".join(text.splitlines()[-lines:])
        return tail, size > MAX_TAIL
    except FileNotFoundError:
        return "", False


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        return not TOKEN or self.headers.get("Authorization") == f"Bearer {TOKEN}"

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"ok": True, "root": ROOT})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?", 1)[0].replace("/rk-exec", "", 1)
        if path not in ("/exec", "/job/start", "/job/status", "/job/kill"):
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        length = int(self.headers.get("Content-Length") or 0)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            return self._json(400, {"error": "invalid json"})

        if path == "/exec":
            return self._exec(body)
        if path == "/job/start":
            return self._job_start(body)
        if path == "/job/status":
            return self._job_status(body)
        return self._job_kill(body)

    def _cwd(self, body) -> str:
        cwd = str(body.get("cwd") or ROOT)
        os.makedirs(ROOT, exist_ok=True)
        return cwd if os.path.isdir(cwd) else ROOT

    def _exec(self, body):
        command = str(body.get("command") or "")
        if not command.strip():
            return self._json(400, {"error": "command is required"})
        timeout = int(body.get("timeout") or 0) or None
        started = time.time()
        proc = subprocess.Popen(
            ["bash", "-lc", command],
            cwd=self._cwd(body),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
        timed_out = False
        try:
            out, err = proc.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            out, err = proc.communicate()
        stdout, cut1 = trim(out or b"")
        stderr, cut2 = trim(err or b"")
        self._json(200, {
            "exitCode": -1 if timed_out else proc.returncode,
            "stdout": stdout,
            "stderr": stderr,
            "timedOut": timed_out,
            "truncated": cut1 or cut2,
            "elapsedMs": int((time.time() - started) * 1000),
        })

    def _job_start(self, body):
        command = str(body.get("command") or "")
        if not command.strip():
            return self._json(400, {"error": "command is required"})
        os.makedirs(JOBS_DIR, exist_ok=True)
        jid = uuid.uuid4().hex[:8]
        log_path = os.path.join(JOBS_DIR, jid + ".log")
        log_file = open(log_path, "wb")
        proc = subprocess.Popen(
            ["bash", "-lc", command],
            cwd=self._cwd(body),
            stdout=log_file,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
        )
        with LOCK:
            JOBS[jid] = {
                "proc": proc,
                "log": log_path,
                "command": command,
                "cwd": self._cwd(body),
                "startedAt": int(time.time() * 1000),
            }
        self._json(200, {"id": jid, "logPath": log_path})

    def _job_status(self, body):
        jid = str(body.get("id") or "")
        tail_lines = int(body.get("tail") or 80)
        with LOCK:
            job = JOBS.get(jid)
        if not job:
            return self._json(404, {"error": "job not found"})
        running = job["proc"].poll() is None
        tail, truncated = read_tail(job["log"], max(1, min(tail_lines, 400)))
        self._json(200, {
            "id": jid,
            "running": running,
            "exitCode": None if running else job["proc"].returncode,
            "command": job["command"],
            "cwd": job["cwd"],
            "logPath": job["log"],
            "startedAt": job["startedAt"],
            "logTail": tail,
            "truncated": truncated,
        })

    def _job_kill(self, body):
        jid = str(body.get("id") or "")
        with LOCK:
            job = JOBS.get(jid)
        if not job:
            return self._json(404, {"error": "job not found"})
        proc = job["proc"]
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        self._json(200, {"ok": True})


if __name__ == "__main__":
    port = int(os.environ.get("RK_EXEC_PORT", "18080"))
    os.makedirs(ROOT, exist_ok=True)
    os.makedirs(JOBS_DIR, exist_ok=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
