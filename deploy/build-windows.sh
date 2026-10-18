#!/usr/bin/env bash
# Builds release/MiniLAN-windows-x64.zip: portable Node + app + koffi + wintun + double-click launchers.
# Prereqs: `npm install` done (koffi), client/tun/wintun.dll present, Node 22+ for x64 at $NODE_EXE.
set -euo pipefail
cd "$(dirname "$0")/.."
NODE_EXE="${NODE_EXE:-/c/Program Files/nodejs/node.exe}"
VERSION="$(node -p "require('./package.json').version")"
OUT=release/MiniLAN-windows-x64
rm -rf "$OUT" "$OUT.zip"
mkdir -p "$OUT/app/node_modules/@koromix" "$OUT/runtime"

cp "$NODE_EXE" "$OUT/runtime/node.exe"
cp "$(dirname "$NODE_EXE")/LICENSE" "$OUT/runtime/NODE-LICENSE.txt" 2>/dev/null || true
cp -r client server shared package.json "$OUT/app/"
cp -r node_modules/koffi "$OUT/app/node_modules/koffi"
rm -rf "$OUT/app/node_modules/koffi/vendor" "$OUT/app/node_modules/koffi/doc"
cp -r node_modules/@koromix/koffi-win32-x64 "$OUT/app/node_modules/@koromix/"
test -f "$OUT/app/client/tun/wintun.dll" || { echo "missing client/tun/wintun.dll"; exit 1; }
[ -f default-server.txt ] && cp default-server.txt "$OUT/default-server.txt"
cp HOSTING.md "$OUT/HOW TO HOST.md"

# Launchers (CRLF). Self-elevate: creating the network adapter needs Administrator.
crlf() { sed 's/$/\r/'; }
crlf > "$OUT/MiniLAN.bat" <<'BAT'
@echo off
title MiniLAN
net session >nul 2>&1 && goto elevated
if "%~1"=="" (powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs") else (powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs")
exit /b
:elevated
cd /d "%~dp0"
"runtime\node.exe" "app\client\launcher.js" %*
:loop
rem exit code 3 = host/join mode was switched in the app: restart it
if errorlevel 3 if not errorlevel 4 (set MINILAN_RESTARTED=1& "runtime\node.exe" "app\client\launcher.js" & goto loop)
pause
BAT
crlf > "$OUT/MiniLAN Server.bat" <<'BAT'
@echo off
title MiniLAN Server
net session >nul 2>&1 || (powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs" & exit /b)
cd /d "%~dp0"
"runtime
ode.exe" "app\server\launcher.js"
pause
BAT
crlf > "$OUT/MiniLAN settings.bat" <<'BAT'
@echo off
call "%~dp0MiniLAN.bat" --setup
BAT
crlf > "$OUT/README.txt" <<TXT
MiniLAN ${VERSION} for Windows (x64)

1. Double-click MiniLAN.bat and click "Yes" on the Windows admin prompt
   (needed to create the "MiniLAN" network adapter).
2. First run asks your name and whether you JOIN a server or HOST it on this PC.
   (Server only, without playing on this PC: double-click "MiniLAN Server.bat".
    On the PC that runs the server, players connect to 127.0.0.1.)
3. Your browser opens http://127.0.0.1:7777: create/join a network or a game room.
   Keep the black window open while playing; close it to disconnect.

Hosting: see "HOW TO HOST.md" (PC, phone hotspot or VPS; port forwarding steps).
Hosting for friends over the Internet: forward TCP 8080 + UDP 8081 on your router to this PC.
Change name/server later: "MiniLAN settings.bat".
Generals / Zero Hour: Options -> IP Address -> pick your 100.77.x.x address, then Network.

Includes Node.js (MIT), koffi (MIT) and Wintun (prebuilt binaries license, see app\client\tun\wintun-LICENSE.txt).
Source and help: https://github.com/Uss15/minilan-vpn
TXT

(cd release && powershell -NoProfile -Command "Compress-Archive -Path 'MiniLAN-windows-x64' -DestinationPath 'MiniLAN-windows-x64.zip' -Force")
ls -la release/
