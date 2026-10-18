#!/usr/bin/env bash
# Builds release/minilan-server-install.sh: install.sh with the server code embedded (no GitHub needed).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p release
OUT=release/minilan-server-install.sh
{ cat deploy/install.sh; echo '__MINILAN_PAYLOAD__'; tar -cz server shared package.json | base64 -w 76; } > "$OUT"
sed -i 's/\r$//' "$OUT"
ls -la "$OUT"
