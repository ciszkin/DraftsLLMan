#!/usr/bin/env python3
"""
OpenRouter endpoint-visibility probe: a tiny reverse proxy that logs every koog <-> OpenRouter
exchange (timing, body sizes, finish_reason/usage hints) so the S1 hang / premature-loop-end can be
pinned to its exact HTTP step. Run it, point DRAFTS_OPENROUTER_ENDPOINT=http://127.0.0.1:9876, and
read this stdout.

Usage: python3 openrouter-probe.py [port]
Upstream: default https://openrouter.ai (set OPENROUTER_UPSTREAM to override). The proxy forwards
the request path as received (koog hits /v1/chat/completions on the base URL).
"""
import http.server
import json
import os
import sys
import time
import urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9876
UPSTREAM = os.environ.get("OPENROUTER_UPSTREAM", "https://openrouter.ai")


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _handle(self):
        t0 = time.time()
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        log(f"REQ  {self.command} {self.path} body={len(body)}B from={self.client_address[0]}")

        headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length", "connection", "accept-encoding")}
        url = UPSTREAM + self.path
        req = urllib.request.Request(url, data=body if body else None, headers=headers, method=self.command)
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                payload = resp.read()
                dt = time.time() - t0
                log(f"RESP {self.path} status={resp.status} body={len(payload)}B in={dt:.1f}s")
                tip = payload[:700].decode("utf-8", "replace")
                try:
                    obj = json.loads(payload)
                    log("  usage=" + json.dumps(obj.get("usage", {})) +
                        " finish=" + json.dumps([c.get("finish_reason") for c in obj.get("choices", [])]) +
                        " tool_calls=" + json.dumps(bool(obj.get("choices") and obj["choices"][0].get("message", {}).get("tool_calls"))))
                except Exception:
                    log("  (non-json body) " + tip[:200].replace("\n", " "))
                self.send_response(resp.status)
                for k, v in resp.headers.items():
                    if k.lower() not in ("content-length", "connection", "transfer-encoding"):
                        self.send_header(k, v)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        except urllib.error.HTTPError as e:
            dt = time.time() - t0
            body = e.read().decode("utf-8", "replace")
            log(f"UPSTREAM-HTTP {self.path} status={e.code} after {dt:.1f}s body={body[:500]}")
            self.send_response(e.code)
            for k, v in e.headers.items():
                if k.lower() not in ("content-length", "connection", "transfer-encoding"):
                    self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body.encode())
        except Exception as e:
            dt = time.time() - t0
            log(f"FAIL {self.path} after {dt:.1f}s: {type(e).__name__}: {e}")
            self.send_response(502)
            self.end_headers()
            self.wfile.write(str(e).encode())

    do_GET = _handle
    do_POST = _handle
    do_PUT = _handle
    do_DELETE = _handle
    do_OPTIONS = _handle

    def log_message(self, *a):  # silence base handler logging
        pass


if __name__ == "__main__":
    log(f"probe listening on 127.0.0.1:{PORT}, upstream {UPSTREAM}")
    http.server.HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()