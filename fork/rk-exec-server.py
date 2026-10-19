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
import hmac
import tempfile
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
SLOTS = threading.BoundedSemaphore(max(1, int(os.environ.get("RK_EXEC_MAX_JOBS", "4"))))
MAX_REQUEST = 1024 * 1024
KEEP_SECONDS = 7 * 86400



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
        return bool(TOKEN) and hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {TOKEN}")

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"ok": True})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?", 1)[0].replace("/rk-exec", "", 1)
        if path not in ("/exec", "/job/start", "/job/status", "/job/kill"):
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if not 0 < length <= MAX_REQUEST:
                self.close_connection = True
                return self._json(413, {"error": "request size must be 1..1048576 bytes"})
        except ValueError:
            self.close_connection = True
            return self._json(400, {"error": "invalid content length"})
        self.connection.settimeout(30)
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            return self._json(400, {"error": "invalid json"})

        if not isinstance(body, dict):
            return self._json(400, {"error": "request must be an object"})
        try:
            self._dispatch(path, body)
        except (ValueError, TypeError) as exc:
            self._json(400, {"error": str(exc)})
        except OSError:
            self._json(500, {"error": "execution or storage failure"})

    def _dispatch(self, path, body):
        if path == "/exec":
            return self._exec(body)
        if path == "/job/start":
            return self._job_start(body)
        if path == "/job/status":
            return self._job_status(body)
        return self._job_kill(body)

    def _cwd(self, body) -> str:
        cwd = os.path.abspath(os.path.expanduser(str(body.get("cwd") or ROOT)))
        os.makedirs(ROOT, exist_ok=True)
        if not os.path.isdir(cwd):
            raise ValueError("Working directory does not exist")
        return cwd

    def _exec(self, body):
        command = str(body.get("command") or "")
        if not command.strip():
            return self._json(400, {"error": "command is required"})
        timeout = int(body.get("timeout") or 0) or None
        if timeout is not None and timeout < 0:
            raise ValueError("timeout must not be negative")
        if not SLOTS.acquire(blocking=False):
            return self._json(429, {"error": "execution slots full"})
        started = time.time()
        try:
            # Spool output to disk instead of buffering an unbounded build log in RAM.
            with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
                proc = subprocess.Popen(["bash", "-lc", command], cwd=self._cwd(body),
                    stdin=subprocess.DEVNULL, stdout=out, stderr=err, start_new_session=True)
                timed_out = False
                try:
                    proc.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    timed_out = True
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait()
                out.seek(0); err.seek(0)
                stdout, cut1 = trim(out.read(MAX_OUTPUT + 1))
                stderr, cut2 = trim(err.read(MAX_OUTPUT + 1))
                self._json(200, {"exitCode": -1 if timed_out else proc.returncode,
                    "stdout": stdout, "stderr": stderr, "timedOut": timed_out,
                    "truncated": cut1 or cut2, "elapsedMs": int((time.time() - started) * 1000)})
        finally:
            SLOTS.release()

    def _job_start(self, body):
        command = str(body.get("command") or "")
        if not command.strip():
            return self._json(400, {"error": "command is required"})
        cwd = self._cwd(body)
        if not SLOTS.acquire(blocking=False):
            return self._json(429, {"error": "execution slots full"})
        os.makedirs(JOBS_DIR, mode=0o700, exist_ok=True)
        jid = uuid.uuid4().hex
        log_path = os.path.join(JOBS_DIR, jid + ".log")
        try:
            with open(log_path, "xb") as log_file:
                os.chmod(log_path, 0o600)
                proc = subprocess.Popen(["bash", "-lc", command], cwd=cwd,
                    stdout=log_file, stderr=subprocess.STDOUT,
                    stdin=subprocess.DEVNULL, start_new_session=True)
        except Exception:
            SLOTS.release()
            raise
        job = {"proc": proc, "log": log_path, "command": command, "cwd": cwd,
            "startedAt": int(time.time() * 1000)}
        with LOCK:
            JOBS[jid] = job
        def reap():
            try:
                proc.wait()
            finally:
                SLOTS.release()
                # Bounded history; never remove running jobs or their logs.
                with LOCK:
                    completed = [(k, v) for k, v in JOBS.items() if v["proc"].poll() is not None]
                    completed.sort(key=lambda item: item[1]["startedAt"], reverse=True)
                    expired = [k for index, (k, v) in enumerate(completed)
                        if index >= 1000 or time.time() - v["startedAt"] / 1000 > KEEP_SECONDS]
                    for key in expired:
                        old = JOBS.pop(key, None)
                        if old:
                            try: os.unlink(old["log"])
                            except FileNotFoundError: pass
        threading.Thread(target=reap, daemon=True).start()
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
    if not TOKEN.strip():
        raise SystemExit("RK_EXEC_TOKEN must be configured; refusing unauthenticated execution")
    port = int(os.environ.get("RK_EXEC_PORT", "18080"))
    os.makedirs(ROOT, exist_ok=True)
    os.makedirs(JOBS_DIR, exist_ok=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
