# MiniLAN + Generals Zero Hour Android port

This is for [MYSOREZ/GeneralsZH-Android-Port](https://github.com/MYSOREZ/GeneralsZH-Android-Port), the native Android build of the real Zero Hour engine.

## Why LAN games don't show over MiniLAN today

- **The game hides VPN addresses.** `Core/GameEngine/Source/GameNetwork/IPEnumeration.cpp` lists only Wi-Fi, hotspot, Ethernet and USB interfaces, and skips VPN tunnels (`tun*`). So MiniLAN's `100.77.x.x` address never appears under **Options → IP Address**, and the lobby uses the Wi-Fi address.
- **So broadcasts stay on Wi-Fi.** A socket bound to the Wi-Fi address sends its LAN broadcasts (UDP 8086) out of Wi-Fi only; that's how Linux handles limited broadcast. MiniLAN never sees them, so other MiniLAN players can't see games hosted on the phone.
- **Joining fails too.** The phone would identify itself to PCs by its private Wi-Fi address, which they can't reach.

The port already *receives* broadcasts correctly: it has a second socket on `255.255.255.255:8086`. Since MiniLAN Android 0.3.4, MiniLAN delivers incoming broadcasts both as-is and re-addressed to the phone, so receiving works with this port and with Winlator.

## The fix (game side)

[`0001-list-virtual-lan-vpn-addresses.patch`](0001-list-virtual-lan-vpn-addresses.patch) also lists `tun*` interfaces whose address is in `100.64.0.0/10`, the range MiniLAN, Tailscale and ZeroTier use for virtual LANs.

- Privacy VPNs (other address ranges) and mobile data (`rmnet*` / `ccmni*`, which can also use 100.64/10) stay excluded, as the port intends.
- The list is sorted and the LAN lobby uses the first entry, so with MiniLAN connected `100.77.x.x` becomes the default.

**Ready-made build:** [Zero Hour Android 1.4.0 + MiniLAN](https://github.com/Uss15/GeneralsZH-Android-Port/releases/tag/v1.4.0-minilan) (`GeneralsZH-1.4.0-minilan2.apk`).

- **What it is:** the **official 1.4.0 APK** with a 7-byte patch, signed with the port's public debug key, so it installs over the official app.
  - In both engines, the interface label `ncm` becomes `tun`.
  - versionCode is raised to 10402.
- **Trade-off:** any VPN `tun` interface is listed, not only 100.64.0.0/10 ones, and USB NCM tethering drops off the list.
- **Why not build from source:** a GitHub Actions build of the port's current `main` started on one phone but crashed on another before the main menu. The official release build is the one known to run.
- **Everyone in a match must install the same APK**, because Zero Hour only joins identical builds.

The proper source fix below is proposed upstream as [MYSOREZ/GeneralsZH-Android-Port#54](https://github.com/MYSOREZ/GeneralsZH-Android-Port/pull/54).

Or apply the patch to a checkout of the port yourself:

```bash
git apply 0001-list-virtual-lan-vpn-addresses.patch
```

## Playing

1. Connect MiniLAN on the phone and join the same network or room as the other players (PCs or phones).
2. Start Zero Hour, open **Options → IP Address** and check it shows the phone's **100.77.x.x** address. Then go to **Multiplayer → Network**.
3. Everyone needs the same game build. On PC, use the same Zero Hour version as the port (1.04 data).
