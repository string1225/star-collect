#!/usr/bin/env python3
"""Small allowlisted GitHub Release cache, mounted at /github-release/ by nginx."""
import hashlib
import json
import os
import re
import shutil
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler

MAX_APK = 100 * 1024 * 1024
MAX_JSON = 1024 * 1024
PREFIX = "/github-release"
PUBLIC_BASE = os.environ.get("GITHUB_RELEASE_PUBLIC_BASE", "https://home.sunny-string.cn")
ALLOWED = set(os.environ.get("GITHUB_RELEASE_REPOSITORIES", "string1225/star-collect").split(","))
CACHE = Path(os.environ.get("GITHUB_RELEASE_CACHE", "/var/lib/github-release"))
metadata_cache = {}
metadata_lock = threading.Lock()
download_lock = threading.Lock()


class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        parsed = urlsplit(newurl)
        if parsed.scheme != "https" or parsed.hostname not in {
            "github.com", "api.github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
        } or parsed.username or parsed.port not in (None, 443):
            raise ValueError("Unexpected upstream redirect")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def upstream(url):
    return build_opener(SafeRedirect()).open(Request(url, headers={
        "Accept": "application/vnd.github+json", "User-Agent": "StarCollect-ReleaseMirror"
    }), timeout=30)


def release(repo, tag="latest"):
    key = (repo, tag)
    with metadata_lock:
        item = metadata_cache.get(key)
        if item and time.monotonic() - item[0] < 60:
            return item[1]
        endpoint = "latest" if tag == "latest" else "tags/" + quote(tag, safe="")
        with upstream(f"https://api.github.com/repos/{repo}/releases/{endpoint}") as response:
            body = response.read(MAX_JSON + 1)
        if len(body) > MAX_JSON:
            raise ValueError("Release metadata too large")
        data = json.loads(body)
        if data.get("draft") or data.get("prerelease") or not re.fullmatch(r"v?\d+\.\d+\.\d+", data.get("tag_name", "")):
            raise ValueError("Only stable releases are supported")
        if tag != "latest" and data["tag_name"] != tag:
            raise ValueError("Release tag mismatch")
        for asset in data.get("assets", []):
            name = asset.get("name", "")
            if re.fullmatch(r"[A-Za-z0-9_.-]+", name):
                asset["mirror_download_url"] = f"{PUBLIC_BASE}{PREFIX}/{repo}/download/{data['tag_name']}/{name}"
        if len(metadata_cache) >= 64:
            metadata_cache.clear()
        metadata_cache[key] = (time.monotonic(), data)
        return data


def cached_asset(repo, tag, name):
    data = release(repo, tag)
    asset = next((a for a in data.get("assets", []) if a.get("name") == name), None)
    if asset is None or not name.endswith(".apk"):
        raise FileNotFoundError("APK asset not found")
    expected_url = f"https://github.com/{repo}/releases/download/{tag}/{name}"
    digest = asset.get("digest", "")
    size = asset.get("size", 0)
    if asset.get("browser_download_url") != expected_url or not re.fullmatch(r"sha256:[a-fA-F0-9]{64}", digest):
        raise ValueError("Invalid asset origin or digest")
    if not isinstance(size, int) or not 0 < size <= MAX_APK or not isinstance(asset.get("id"), int):
        raise ValueError("Invalid asset size or identifier")
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / f"{asset['id']}-{digest[7:].lower()}.apk"
    with download_lock:
        if target.is_file() and target.stat().st_size == size:
            return target, digest
        part = target.with_suffix(".part")
        try:
            checksum, received = hashlib.sha256(), 0
            with upstream(expected_url) as source, part.open("wb") as output:
                while chunk := source.read(64 * 1024):
                    received += len(chunk)
                    if received > size:
                        raise ValueError("Asset exceeds declared size")
                    output.write(chunk)
                    checksum.update(chunk)
            if received != size or checksum.hexdigest() != digest[7:].lower():
                raise ValueError("Asset checksum mismatch")
            os.replace(part, target)
        finally:
            part.unlink(missing_ok=True)
    return target, digest


def route(path):
    parsed = urlsplit(path)
    if parsed.query or len(parsed.path) > 300:
        return None
    match = re.fullmatch(r"/github-release/([A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+)/(latest|download/(v?\d+\.\d+\.\d+)/([A-Za-z0-9_.-]+\.apk))", parsed.path)
    if not match:
        return None
    owner, name, action, tag, asset = match.groups()
    repo = owner + "/" + name
    if repo not in ALLOWED:
        return None
    return repo, tag, asset


class Handler(BaseHTTPRequestHandler):
    server_version = "GitHubReleaseMirror/1"
    def do_GET(self):
        self.handle_read(False)

    def do_HEAD(self):
        self.handle_read(True)

    def send_json(self, status, value, head=False):
        body = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def handle_read(self, head):
        try:
            if self.path == PREFIX + "/health":
                self.send_json(200, {"status": "ok"}, head)
                return
            selected = route(self.path)
            if selected is None:
                self.send_json(404, {"message": "Release path not enabled"}, head)
                return
            repo, tag, name = selected
            if tag is None:
                self.send_json(200, release(repo), head)
                return
            file, digest = cached_asset(repo, tag, name)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", f'attachment; filename="{name}"')
            self.send_header("Content-Length", str(file.stat().st_size))
            self.send_header("Cache-Control", "public, max-age=3600")
            self.send_header("ETag", '"' + digest + '"')
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            if not head:
                with file.open("rb") as stream:
                    shutil.copyfileobj(stream, self.wfile)
        except HTTPError as e:
            self.send_json(404 if e.code == 404 else 502, {"message": "No published release" if e.code == 404 else "GitHub temporarily unavailable"}, head)
        except FileNotFoundError:
            self.send_json(404, {"message": "APK asset not found"}, head)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.send_json(502, {"message": "Release fetch or verification failed; retry later"}, head)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("PORT", "3043"))), Handler).serve_forever()
