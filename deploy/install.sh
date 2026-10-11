#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 -m unittest discover -s server -p 'test_*.py'
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="/opt/github-release/backups/$stamp"
sudo install -d -m 755 /opt/github-release "$backup"
for destination in /opt/github-release/release_proxy.py /etc/systemd/system/github-release.service /etc/nginx/conf.d/01-github-release-limits.conf /etc/nginx/snippets/github-release-location.conf; do
    if [ -f "$destination" ]; then sudo cp "$destination" "$backup/$(basename "$destination")"; fi
done
sudo cp /etc/nginx/conf.d/sunny-string.conf "$backup/sunny-string.conf"
sudo install -m 644 server/release_proxy.py /opt/github-release/release_proxy.py
sudo install -m 644 deploy/github-release.service /etc/systemd/system/github-release.service
sudo install -m 644 deploy/github-release-limits.conf /etc/nginx/conf.d/01-github-release-limits.conf
sudo install -m 644 deploy/github-release-location.conf /etc/nginx/snippets/github-release-location.conf
sudo python3 - <<'PY'
from pathlib import Path
config = Path('/etc/nginx/conf.d/sunny-string.conf')
text = config.read_text()
include = '    include /etc/nginx/snippets/github-release-location.conf;\n'
if include not in text:
    marker = '    include /etc/nginx/snippets/remote-meeting-location.conf;\n'
    if text.count(marker) != 1:
        raise SystemExit('Expected HTTPS location marker is missing or ambiguous')
    config.write_text(text.replace(marker, include + marker))
PY
if ! sudo nginx -t; then
    sudo cp "$backup/sunny-string.conf" /etc/nginx/conf.d/sunny-string.conf
    if [ -f "$backup/01-github-release-limits.conf" ]; then
        sudo cp "$backup/01-github-release-limits.conf" /etc/nginx/conf.d/01-github-release-limits.conf
    else
        sudo rm -f /etc/nginx/conf.d/01-github-release-limits.conf
    fi
    exit 1
fi
sudo systemctl daemon-reload
sudo systemctl enable --now github-release.service
sudo systemctl restart github-release.service
sudo systemctl reload nginx
printf 'Deployed /github-release/; backup: %s\n' "$backup"
