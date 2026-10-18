#!/usr/bin/env bash
# Installs the MiniLAN coordinator (+ built-in relay) on a fresh Ubuntu/Debian VM as a systemd service.
#   curl -fsSL https://raw.githubusercontent.com/Uss15/minilan-vpn/main/deploy/install.sh | sudo bash
# Opens TCP 8080 (API) and UDP 8081 (discovery/relay). Also open them in your cloud provider's firewall
# (Oracle: VCN → Security List → Ingress rules).
set -euo pipefail
REPO="${MINILAN_REPO:-https://github.com/Uss15/minilan-vpn.git}"

apt-get update -y
apt-get install -y ca-certificates curl git
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi

id minilan >/dev/null 2>&1 || useradd --system --home /var/lib/minilan --create-home minilan
rm -rf /opt/minilan && mkdir -p /opt/minilan
if grep -q '^__MINILAN_PAYLOAD__$' "$0" 2>/dev/null; then
  # self-contained installer: server code is embedded after the marker line
  sed '1,/^__MINILAN_PAYLOAD__$/d' "$0" | base64 -d | tar -xz -C /opt/minilan
else
  git clone --depth 1 "$REPO" /opt/minilan
fi

cat >/etc/systemd/system/minilan.service <<UNIT
[Unit]
Description=MiniLAN coordinator
After=network-online.target
Wants=network-online.target

[Service]
User=minilan
ExecStart=/usr/bin/node /opt/minilan/server/server.js --http 8080 --udp 8081 --data /var/lib/minilan/data.json
Restart=always
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/minilan

[Install]
WantedBy=multi-user.target
UNIT

# Host firewall (Oracle Ubuntu images ship with restrictive iptables rules)
if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow 8080/tcp; ufw allow 8081/udp; fi
if command -v iptables >/dev/null; then
  iptables -C INPUT -p tcp --dport 8080 -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
  iptables -C INPUT -p udp --dport 8081 -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport 8081 -j ACCEPT
  command -v netfilter-persistent >/dev/null && netfilter-persistent save || true
fi

systemctl daemon-reload
systemctl enable --now minilan
sleep 1
curl -fsS http://127.0.0.1:8080/healthz && echo
echo "MiniLAN server is running. Players use:  http://$(curl -fsS https://api.ipify.org 2>/dev/null || hostname -I | cut -d' ' -f1):8080"
exit 0
