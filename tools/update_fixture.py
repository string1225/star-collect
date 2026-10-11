#!/usr/bin/env python3
"""Local emulator-only Release API fixture; never deployed to the production server."""
import argparse
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--bad-apk", required=True, type=Path)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    files = {"good": args.apk.read_bytes(), "bad_signer": args.bad_apk.read_bytes()}
    state = {"scenario": "good", "requests": 0}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def do_GET(self):
            url = urlsplit(self.path)
            if url.path == "/control":
                state["scenario"] = parse_qs(url.query).get("scenario", ["good"])[0]
                self.reply(json.dumps(state).encode(), "application/json")
                return
            if url.path == "/stats":
                self.reply(json.dumps(state).encode(), "application/json")
                return
            state["requests"] += 1
            content = files["bad_signer" if state["scenario"] == "bad_signer" else "good"]
            if url.path == "/latest":
                digest = "0" * 64 if state["scenario"] == "bad_digest" else hashlib.sha256(content).hexdigest()
                asset = {"name": "star-collect.apk", "size": len(content), "digest": "sha256:" + digest, "browser_download_url": "https://github.com/string1225/star-collect/releases/download/v0.1.1/star-collect.apk"}
                data = {"tag_name": "v0.1.1", "draft": False, "prerelease": False, "body": "手动更新测试", "assets": [] if state["scenario"] == "missing_apk" else [asset]}
                self.reply(json.dumps(data).encode(), "application/json")
            elif url.path == "/download/v0.1.1/star-collect.apk":
                self.reply(content, "application/vnd.android.package-archive")
            else:
                self.send_error(404)

        def reply(self, content, content_type):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(content)
            self.wfile.flush()
            self.close_connection = True

    print("Fixture ready at http://127.0.0.1:" + str(args.port), flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
