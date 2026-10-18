# How to host a MiniLAN server

Every MiniLAN group needs **one server**. It introduces players to each other and passes traffic along when two players can't connect directly. Game traffic normally flows **directly between players**, so the server doesn't need to be powerful. It just needs to be **on while people play**.

Pick the option that fits your group:

| | Who can join | Needs | Best for |
|---|---|---|---|
| **A. Your PC** | Same house right away. Internet friends after port forwarding. | PC on while playing | Most groups |
| **B. Your phone** | Same Wi-Fi, or your phone's **hotspot** | Wi-Fi or hotspot on | Playing together in one place, even with no internet |
| **C. A VPS** (rented server) | Everyone, 24/7 | ~$4–6/month, or a cloud free tier | Always online, like Radmin |

---

## A. Host on your PC (Windows)

### Step 1: start the server

Choose **one** of these:
- **Host and play on the same PC:** open `MiniLAN.bat`. At first start, choose **2) Host the server on THIS PC**. Already set up? Open the **Servers** tab and click **🏠 Host the server on this PC**.
- **Server only** (e.g. a spare PC that stays on): double-click **`MiniLAN Server.bat`**.

Click **Yes** on the admin prompt. MiniLAN opens the firewall ports it needs.

### Step 2: give friends your address

The **Servers** tab (or the black server window) shows the addresses:

- **Same house / same Wi-Fi:** use the address marked *same Wi-Fi*, e.g. `192.168.1.20`. This works right away.
- **Friends somewhere else (over the Internet):** use the address marked *internet*, e.g. `5.6.7.8`. It only works after Step 3.

Friends type that address in MiniLAN: **Join a server** on PC, or the **Server address** box on the phone.

### Step 3: port forwarding (only for friends over the Internet)

Your router blocks incoming connections until you allow them. You do this once:

1. Open your router's page in a browser. It's usually **http://192.168.1.1** or **http://192.168.0.1**; the address and password are often printed on a sticker on the router.
2. Find **Port Forwarding**. It may be called *Virtual Server*, *NAT* or *Applications & Gaming*.
3. Add two rules pointing to **your PC's same-Wi-Fi address** (the `192.168.x.x` one from Step 2):

   | Name | Protocol | External port | Internal port | Device / IP |
   |---|---|---|---|---|
   | MiniLAN API | **TCP** | 8080 | 8080 | your PC, e.g. 192.168.1.20 |
   | MiniLAN UDP | **UDP** | 8081 | 8081 | your PC, e.g. 192.168.1.20 |

4. Save. Optionally, in the router's DHCP settings, give your PC a **reserved / static IP** so the rule keeps working after a restart.

**Test it:**
1. On your phone, **turn off Wi-Fi** so it uses mobile data.
2. Open `http://YOUR-INTERNET-ADDRESS:8080/healthz` in the phone's browser.
3. If you see `{"ok":true,...}`, it works.

**If it doesn't work:**
- **Your ISP may use CGNAT.** Compare the internet address MiniLAN shows with the **WAN IP** on your router's status page. If they're different, or the WAN IP starts with `100.64`–`100.127`, port forwarding can't work. Ask your ISP for a public IP, or use **option C**.
- **Your home address changes** from time to time with most ISPs. When it does, send friends the new one. A free dynamic-DNS name (e.g. from duckdns.org) avoids this.
- **Privacy:** only send your internet address to people you trust. Don't post it publicly.

---

## B. Host on your phone (Android)

Great for playing together in one place: friends' phones and laptops connect to **your phone's hotspot** or the same Wi-Fi, and it works **even without internet**.

1. Turn on **Wi-Fi** (everyone on the same network) **or** your phone's **mobile hotspot**, and have friends connect to it.
2. Open MiniLAN and tap **📶 Host the server on this phone**. Accept the VPN prompt.
3. The **Servers** tab shows your address, e.g. `192.168.43.1`. Friends enter it as the server address on their phone or PC.
4. Keep MiniLAN open. The server stops when you tap **Stop hosting**.

**Limits:**
- Phones on **mobile data can't be reached from the Internet**, because carriers block incoming connections. So phone hosting only works for people on the same Wi-Fi or hotspot.
- Some phones turn off the hotspot when idle. Keep the screen on, or disable "turn off hotspot automatically".
- Hosting uses a little extra battery. Plug in for long sessions.

---

## C. Host on a VPS (always online)

Rent a small Linux server (Ubuntu or Debian). Any provider works: Oracle Cloud free tier, Google Cloud e2-micro free tier, Hetzner, Vultr, DigitalOcean, etc.

1. In the provider's **firewall / security group**, allow **TCP 8080** and **UDP 8081** from anywhere.
2. Connect to the server with SSH and run:
   ```bash
   curl -fsSL https://raw.githubusercontent.com/Uss15/minilan-vpn/main/deploy/install.sh | sudo bash
   ```
   Or upload `minilan-server-install.sh` from the Windows release and run `sudo bash minilan-server-install.sh`.
3. The installer prints the address players use, e.g. `http://203.0.113.10:8080`. It runs as a service and restarts automatically.

**Docker instead:**
```bash
docker run -d --restart unless-stopped -p 8080:8080 -p 8081:8081/udp -v minilan:/data $(docker build -q https://github.com/Uss15/minilan-vpn.git)
```

**More regions (optional):** start the server with `--relay-secret SECRET` and add relay servers in other countries. Players automatically use the fastest one. See the README.

---

## Making games work

1. **Everyone joins the same network or game room** in MiniLAN. Check the **Networks** tab: each player should show 🟢 **Direct** or 🟠 **Relay**.
2. **Start the game and use its LAN / local network mode.** If the game asks for an IP, use the **host player's `100.77.x.x` address**. Every player sees their own at the top of the Networks tab.
3. **Same game version and mods for everyone.** Mismatched versions are the #1 reason games don't see each other.

**C&C Generals / Zero Hour:**
1. Go to **Options → IP Address** and pick your **`100.77.x.x`** address.
2. Then go to **Multiplayer → Network**.
3. Use the matching MiniLAN game room (1.08, ZH 1.04, Steam/EA, TheSuperHackers patch, or the exact mod).

**Minecraft Bedrock (phone ↔ PC):** *Friends → LAN Games*. If the game doesn't appear, use *Add Server* with the host's `100.77` IP and port `19132`.

**Game not showing up?**
- **Check the connection:** press **Ping** next to the player in MiniLAN. If it works, the tunnel is fine and the problem is in the game or the firewall.
- **Windows Firewall:** allow the game (*Windows Security → Firewall → Allow an app*). MiniLAN marks its adapter as a Private network, but some games still need permission.
- **Use direct IP:** join by typing the host's `100.77.x.x` address instead of relying on the LAN list.
- **Close other VPNs:** Radmin VPN, Hamachi, ZeroTier and others can steal LAN broadcasts while they run.
- **Strangers in a public game room** can only reach that game's ports. To play something else with them, create a **private network** with a password instead.
