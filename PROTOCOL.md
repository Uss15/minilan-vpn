# MiniLAN protocol v1

Both clients (desktop Node, Android Kotlin) implement exactly this. All integers are big-endian.

## Addressing

- Virtual subnet: `100.77.0.0/16`. Node index `n` (1-based) maps to `100.77.(n>>8).(n&255)`;
  indexes whose low byte is `0` or `255` are skipped. `100.77.255.255` is the subnet broadcast.
- Node ID: `u32`, assigned by the server, starting at 1.

## Control plane (HTTP JSON)

Authenticated calls send `Authorization: Bearer <token>`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/v1/register` | `{name, pub}` (pub = base64 X25519 public key) | `{id, ip, token, udpPort}` |
| POST | `/v1/endpoints` | `{local: ["192.168.1.5:41000", ...]}` | `{ok}` |
| POST | `/v1/networks` | `{name, password, public?, game?}` create (caller becomes admin). Public game rooms need a game id from `shared/games.json`; password optional | `{ok}` |
| GET | `/v1/public?game=ID` | list public game rooms | `{rooms: [{name, game, locked, members, online}]}` |
| GET | `/v1/games` | game profiles (`shared/games.json`) | `{id: {title, udp, tcp, tip}}` |
| POST | `/v1/networks/join` | `{name, password}` | `{ok}` |
| POST | `/v1/networks/leave` | `{name}` (admin leaving deletes the network if no other admin) | `{ok}` |
| GET | `/v1/state?since=V` | long-poll, returns when version > V or after ~25 s | see below |

State response:

```json
{ "version": 12,
  "self": {"id": 1, "ip": "100.77.0.1", "name": "alice", "publicEndpoint": "203.0.113.5:41000"},
  "networks": [{"name": "lan", "role": "admin", "members": [1, 2], "public": false, "game": null}],
  "peers": [{"id": 2, "name": "bob", "ip": "100.77.0.2", "pub": "<b64>",
             "online": true, "endpoints": ["198.51.100.7:52000", "192.168.1.9:52000"]}] }
```

Re-registering an existing public key requires the matching bearer token and returns the same id and IP.

## UDP

Each client uses one UDP socket for the server and all peers. The first byte is the packet type.

| Type | Name | Layout |
|---|---|---|
| `0x01` | UDP_REG (client→server) | `type, nodeId u32, ts u64(ms), mac[16]` mac = HMAC-SHA256(utf8(hex(SHA-256(token))), first 13 bytes)[0:16] |
| `0x02` | UDP_REG_ACK (server→client) | `type, ip[4], port u16` = the client's address as the server sees it |
| `0x03` | RELAY (both directions) | `type, src u32, dst u32, inner...` server forwards to dst only if the sender address matches src's registered endpoint and src and dst share a network |
| `0x10` | HS_INIT | `type, sender u32, receiver u32, ephemeral[32], ts u64` + mac[16] |
| `0x11` | HS_RESP | `type, sender u32, receiver u32, ephemeral[32], initTs u64` + mac[16] |
| `0x12` | DATA | `type, sender u32, counter u64` + AEAD ciphertext+tag |
| `0x14` | PUNCH | `type, sender u32`, which opens NAT mappings and is otherwise ignored |

The keepalive interval for UDP_REG is 15 s. The server treats a node as online if it was seen in the last 45 s.

## Crypto

- Every node has a static X25519 keypair `(s, S)`.
- `ss = X25519(s_self, S_peer)` (the same value on both sides). An all-zero result is rejected.
- `macKey = HKDF-SHA256(ikm=ss, salt=empty, info="minilan/v1/hs-mac", 32)`.
- Handshake mac = `HMAC-SHA256(macKey, packet[0:49])[0:16]`.
- The **initiator is the peer with the lower node ID.** The higher-ID peer only sends PUNCH until a session exists.
- HS_INIT `ts` must be strictly greater than the last accepted INIT ts from that peer, which prevents replay.
- HS_RESP must echo the `ts` of the initiator's pending INIT.
- `ee = X25519(e_self, E_peer)`.
- `okm = HKDF-SHA256(ikm = ss || ee, salt = E_init || E_resp, info = "minilan/v1/session", 64)`.
- `k_i2r = okm[0:32]`, `k_r2i = okm[32:64]`.
- DATA uses ChaCha20-Poly1305 with nonce = `00 00 00 00 || counter u64`, AAD = the 13-byte DATA header,
  and a 64-packet sliding replay window. A new session is negotiated every 120 s; the previous session stays valid for decryption.

## DATA payload

- First nibble `4`: a raw IPv4 packet.
- First byte `0x00`: control message `0x00, kind, ts u64` where kind `1` = ping and `2` = pong (echoes the ts).

## Paths

- The initiator sends INIT directly to every endpoint of the peer on attempts 1 and 2, and also through the relay from attempt 3 on.
- A reply is sent on the path its request arrived on.
- Any authenticated packet received directly from an address sets `path = direct(addr)`.
- If no direct packet has arrived for 15 s, the path falls back to the relay.
- While on the relay, each side sends an encrypted ping directly to the peer's endpoints every 5 s, so the path upgrades when hole-punching starts working.
- If nothing has been received for 30 s, the session is considered dead.

## Gaming LAN rules (client side)

- **Broadcast and multicast.** Outgoing IPv4 packets addressed to `255.255.255.255`, `100.77.255.255` or `224.0.0.0/4` are sent to every peer that has a session.
- **Trusted peers.** A peer you share at least one *private* network with is trusted, and all its traffic is allowed.
- **Strangers.** A peer you share *only* public game rooms with is a stranger. Inbound packets from a stranger are delivered only if they are:
  - ICMP, or
  - UDP/TCP whose destination port is in the union of the `udp`/`tcp` port lists for those rooms' games, or
  - UDP/TCP from a source port in that list. This covers replies to connections you opened.
- **Windows adapter.** On Windows the adapter gets interface metric 1, so `255.255.255.255` broadcasts and multicast leave through it.
