#!/usr/bin/env python3
"""Publish a verified APK using existing GitHub credentials; never print/persist tokens."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = "string1225/star-collect"


def github_environment():
    env = os.environ.copy()
    if not (env.get("GH_TOKEN") or env.get("GITHUB_TOKEN")):
        options = dict(env, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never")
        result = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", text=True, capture_output=True, env=options, cwd=ROOT, timeout=20)
        fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        token = fields.get("password")
        if result.returncode or not token:
            raise SystemExit("No cached GitHub credential. Set GH_TOKEN with Contents write permission.")
        env["GH_TOKEN"] = token
    return env


def gh(env, *args):
    result = subprocess.run(["gh", *args], env=env, cwd=ROOT, text=True, capture_output=True)
    if result.returncode:
        raise SystemExit("GitHub operation failed: " + result.stderr.strip())
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-auth", action="store_true")
    parser.add_argument("--tag")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    env = github_environment()
    if args.check_auth:
        print("GitHub credential ready for " + gh(env, "api", "user", "--jq", ".login"))
        return
    if not args.tag or not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag) or not args.apk or not args.notes:
        raise SystemExit("Use --tag vX.Y.Z --apk SIGNED_APK --notes NOTES_FILE")
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip():
        raise SystemExit("Commit and push all intended changes before publishing.")
    remote = gh(env, "api", f"repos/{REPO}/git/ref/heads/main", "--jq", ".object.sha")
    if remote != sha:
        raise SystemExit("Remote main does not match local HEAD; push first.")
    apk = args.apk.resolve()
    if not apk.is_file() or apk.stat().st_size <= 0:
        raise SystemExit("APK missing or empty")
    asset = ROOT / "artifacts" / "star-collect.apk"
    asset.parent.mkdir(exist_ok=True)
    if apk != asset:
        import shutil
        shutil.copyfile(apk, asset)
    sums = asset.parent / "SHA256SUMS"
    sums.write_text(hashlib.sha256(asset.read_bytes()).hexdigest() + "  star-collect.apk\n", encoding="utf-8")
    # A draft remains invisible to latest until both files are uploaded.
    gh(env, "release", "create", args.tag, str(asset), str(sums), "--repo", REPO, "--target", sha, "--title", "攒星星 " + args.tag,
       "--notes-file", str(args.notes.resolve()), "--draft")
    gh(env, "release", "edit", args.tag, "--repo", REPO, "--draft=false", "--latest")
    print("https://github.com/" + REPO + "/releases/tag/" + args.tag)


if __name__ == "__main__":
    main()
