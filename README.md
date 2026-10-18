# MiniLAN

A free, open-source virtual LAN for gaming, in the spirit of Radmin VPN and Hamachi. Friends join a network by name and password, each gets a `100.77.x.x` address, and LAN games see each other as if everyone were on the same switch. It works on **Windows PCs and Android phones**, together.

- 🎮 **Game rooms.** Public rooms per game: C&C Generals (every version and major Zero Hour mod), Red Alert 2, Warcraft III, StarCraft, AoE II, Minecraft Java and Bedrock, Among Us, CS 1.6/Source, Terraria, Factorio, Valheim and more.
- 🔒 **Encrypted, mostly peer-to-peer.**
  - Players connect directly to each other; a relay is used only if that's impossible.
  - Traffic is encrypted end to end (X25519 + ChaCha20-Poly1305).
  - Strangers in public rooms can only reach that game's ports.
- 🖥️ **Host it yourself.** Any player's PC can be the server with one click, or you can run it on a VPS or a network of relay servers.

> ❤ **Like MiniLAN? [Support the project](https://github.com/sponsors/Uss15).** Click the **Sponsor** button at the top of this page.

---

## Download and play

Get both from the **[Releases](https://github.com/Uss15/minilan-vpn/releases)** page.

### Windows (10/11, 64-bit)

1. Download **`MiniLAN-windows-x64.zip`** and unzip it anywhere (e.g. `D:\MiniLAN`).
2. Double-click **`MiniLAN.bat`** and click **Yes** on the admin prompt. Admin is needed to create the "MiniLAN" network adapter.
3. First run asks:
   - your **player name**
   - **1) Join a server**: type the address your friend gave you, or
   - **2) Host the server on this PC**: you play *and* host. Keep MiniLAN open while others play.
4. Your browser opens the MiniLAN window (`http://127.0.0.1:7777`):
   - **Networks:** create a private network (name + password) or join one.
   - **Game rooms:** pick your game, then join or host a public room.
   - **Servers:** switch between **hosting on this PC** and **joining a server** at any time. When hosting, it shows the addresses to give your friends.
5. Keep the black window open while playing; close it to disconnect.

To host *without* playing on that PC (e.g. an always-on spare PC), double-click **`MiniLAN Server.bat`**. On that PC itself, players connect to `127.0.0.1`.

### Android (8.0+)

1. Download **`MiniLAN-android.apk`** on your phone and open it. Allow "install unknown apps" for your browser when asked.
2. Enter the server address and your name, tap **Connect**, and accept the VPN prompt.
3. Join a network, or open **Game rooms**, pick a game, then join or host.
4. Switch to your game. MiniLAN keeps running with a notification, and phones and PCs see each other.
   - **Minecraft Bedrock:** *Friends → LAN Games*, or *Add Server* with the host's `100.77` IP, port 19132.
   - **Among Us (Local), Mindustry, BombSquad, Terraria:** use LAN, or join by IP with the host's `100.77` address.
   - Games that only broadcast to the Wi-Fi subnet won't show up in the LAN list; join by IP.

### Hosting for friends over the Internet

If the server runs on a home PC, friends outside your house can reach it only after you **forward TCP 8080 and UDP 8081** on your router to that PC. Your router's admin page is usually `192.168.1.1` or `192.168.0.1`; look for *Port Forwarding* or *Virtual Server*. Then give friends your **public IP**, which MiniLAN shows in the **Servers** tab.

- Some ISPs use CGNAT, which makes port forwarding impossible. In that case host on a cheap VPS instead (below).
- Don't post your home IP publicly. Send it to friends privately.

### C&C Generals / Zero Hour

1. In the game: **Options → IP Address**, pick your `100.77.x.x` address, then go to **Multiplayer → Network**.
2. **Everyone must run the identical build:** 1.08, ZH 1.04, the same Steam/EA or community patch, and the same mod version. Use the matching **Game rooms** entry so you only meet compatible players.
3. MiniLAN sets its adapter metric to 1 so lobby broadcasts (UDP 8086) go through the virtual LAN.

Red Alert 2 / Tiberian Sun need an IPX-over-UDP wrapper (as in CnCNet) first.

### Windows Firewall

MiniLAN adds firewall rules for itself and marks its adapter as a *Private* network, so games accept LAN traffic. If a game still can't be joined, allow that game in Windows Firewall.

---

## Running a server on a VPS (always online)

Use any Ubuntu/Debian VM with Node 22+ (Oracle/Google free tier, Hetzner, Vultr, DigitalOcean …). Open **TCP 8080 and UDP 8081** in the provider's firewall, then run one of:

```bash
curl -fsSL https://raw.githubusercontent.com/Uss15/minilan-vpn/main/deploy/install.sh | sudo bash
```

or upload the self-contained `minilan-server-install.sh` from the Windows release and run `sudo bash minilan-server-install.sh`.

The installer creates a `minilan` systemd service. Docker alternative:

```bash
docker build -t minilan . && docker run -d -p 8080:8080 -p 8081:8081/udp -v minilan:/data minilan
```

**A network of relay servers** (like Radmin's): start the coordinator with `--relay-secret SECRET`, then on each extra server:

```bash
node server/relay.js --coord http://COORD:8080 --secret SECRET --name eu-1 --region eu --udp 8082 --public eu1.example.com:8082
```

Clients measure their ping to every relay and use the fastest one. Players can also pin a relay in the **Servers** tab.

**HTTPS:** for a public server, put the API behind TLS (e.g. Caddy `reverse_proxy localhost:8080`) and use `https://…` as the server address.

---

## How it works

```
 PC (Windows) ──┐         coordinator (API + built-in relay)          ┌── Android phone
  MiniLAN app   │   HTTP: accounts, networks, rooms, peer list        │   MiniLAN app
  + Wintun      ├── UDP: endpoint discovery ─────────────────────────┤   + VpnService
                │                                                     │
                └════ encrypted UDP, direct (hole-punched) ═══════════┘
                       or via the fastest relay node when direct fails
```

1. Each app registers with the **coordinator**, which assigns a `100.77.x.x` address and tells it who else is in its networks.
2. Peers punch through NAT and talk **directly**. If that fails within about 2 s, packets go through a **relay**, which only sees ciphertext. The apps keep retrying direct and switch over when it works.
3. Packets for `100.77.0.0/16`, plus LAN broadcasts and multicast, are carried over the tunnel, so game lobbies find each other.

The full wire format is in [PROTOCOL.md](PROTOCOL.md).

| Part | Path |
|---|---|
| Coordinator (API, discovery, built-in relay) | `server/server.js`, `server/udp.js` |
| Standalone relay nodes | `server/relay.js` |
| Windows server launcher | `server/launcher.js` |
| Desktop engine, web UI, launcher | `client/engine.js`, `client/main.js`, `client/ui.html`, `client/launcher.js` |
| Windows adapter (Wintun via koffi) | `client/tun/wintun.js` |
| Android app (Kotlin, VpnService) | `android/` |
| Game profiles | `shared/games.json` |

### Security notes

- **Data plane:**
  - Static X25519 identity keys.
  - Per-session ephemeral X25519 → HKDF-SHA256 → ChaCha20-Poly1305.
  - 64-packet replay window, rekey every 2 minutes.
- **Control plane:** bearer tokens (the server stores only their SHA-256) and scrypt-hashed network passwords. Relaying is allowed only between members of a shared network.
- **Not audited.** It's a custom protocol modelled on Noise/WireGuard ideas, but no one has reviewed it. Treat it as a hobby VPN, not for sensitive traffic.

## Building from source

```bash
npm install            # optional koffi (Windows adapter)
npm test               # server + relay + clients end-to-end on localhost
node client/main.js --server http://HOST:8080 --tun virtual   # run without admin, for testing
```

- **Windows package:** put `wintun.dll` (from https://www.wintun.net) in `client/tun/`, then run `bash deploy/build-windows.sh`, which produces `release/MiniLAN-windows-x64.zip`.
- **Android:** open `android/` in Android Studio, or use JDK 17 + Android SDK 34:
  ```bash
  cd android && ./gradlew testDebugUnitTest assembleRelease
  ```
  - `ProtoVectorsTest` checks the Kotlin crypto byte-for-byte against the Node implementation; regenerate the vectors with `npm run vectors`.
  - `InteropTest` runs the Android engine against a Node peer.
  - Release signing reads `MINILAN_KEYSTORE_PROPS`; without it the build is debug-signed.

## Status and limits

**Tested:**
- Server, relays, desktop engine and UI on localhost.
- The Android engine talking to the desktop engine (JVM interop test).
- The Android crypto matching the desktop byte-for-byte.

**Not yet field-tested:**
- The Wintun adapter on a real Windows install.
- The APK on physical phones.
- LAN-broadcast capture on different Android versions.

**Not implemented yet:**
- TCP fallback when all UDP is blocked
- IPv6 inside the tunnel
- Admin kick/ban
- Text chat
- An installer or tray icon

Bug reports and pull requests are welcome.

## License

MIT. Bundled in the Windows release: Node.js (MIT), koffi (MIT), Wintun (WireGuard LLC prebuilt-binaries license).
