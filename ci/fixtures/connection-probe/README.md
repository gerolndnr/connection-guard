# Connection probe testbed

Observe-only experiment on Velocity (`experimental.connection-probe.enabled: true`, `/cg probe`). It collects signals that may identify proxies and VPNs, so real data can show which of them are worth building. It never blocks a login and never changes a verdict. The two signals it started with:

- **Ping gap:** the kernel's TCP round trip ends at the TCP peer, while the keep-alive ping reaches the player. A ping far above the TCP round trip means a TCP-terminating proxy (SOCKS, residential proxy, booster) between player and server.
- **Segment size:** the MSS in the player's SYN shows the link's MTU. A tunnel lowers it (WireGuard's default MTU 1420 gives 1380); PPPoE (1452) and DS-Lite (1420) are named so they are not mistaken for tunnels.

`run.sh` starts Velocity with the plugin, a minimal 1.21.11 backend and five bench clients on an internal Docker network, emulates their links with the Docker VM's `tc`/`ip`, and prints the probe line per player.

```
VELOCITY=/abs/velocity-3.5.1-615.jar PLUGIN=/abs/connection-guard-0.6.2-SNAPSHOT-all.jar \
  BENCH_HARNESS=/abs/mc-antivpn-bench/harness ./run.sh
```

Result of 25 Oct 2026 (50 ms emulated delay):

| Player | Link | Probe |
|---|---|---|
| Near | direct | peerMSS=1460 (standard) tcpRTT=3.0ms minPing=4ms gap=1ms |
| Far | direct, +50 ms | peerMSS=1460 (standard) tcpRTT=56.1ms minPing=59ms gap=3ms |
| Pppoe | direct, MTU 1492 | peerMSS=1452 (pppoe) tcpRTT=3.2ms minPing=6ms gap=3ms |
| Tunnel | IP-in-IP, MTU 1420, NAT at the "VPN" | peerMSS=1380 (wireguard) tcpRTT=56.4ms minPing=59ms gap=3ms |
| Proxied | +50 ms, then a TCP relay | peerMSS=1460 (standard) tcpRTT=2.1ms minPing=60ms gap=58ms, proxy suspected |

Limits:

- The bench client answers keep-alives at once. A real client handles them in its game loop (one frame, more under load), so real gaps are noisier; the minimum over several pings and the 40 ms threshold are first guesses to measure on real players.
- A proxy close to the server barely widens the gap; a proxy close to the player is the case it catches.
- The segment size sees the TCP peer: through a TCP-terminating proxy it is the proxy's MSS. A VPN that clamps MSS to 1460, or a player whose own MTU is lowered for other reasons, is not told apart.
- Linux with Netty's epoll transport only. HAProxy/TCPShield in front, or a backend behind a proxy, are skipped (`behind-tcp-proxy`). Velocity only; Paper and BungeeCord are not wired.

## Experiment journal

With the probe on, every login becomes one JSON line in `plugins/connection-guard/experiments/connection-probe-<UTC day>.jsonl`. The journal stays on the server: nothing is sent, the cloud link is not involved.

```yaml
experimental:
  connection-probe:
    enabled: false        # the whole experiment; off by default
    gap-threshold-ms: 40
    journal: true         # write the local journal (when enabled)
    reverse-dns: false    # look up each joining address's PTR name (asks the resolver); only the class is kept
```

Each line holds:

| Field | Signal | Idea |
|---|---|---|
| `tcp.peer_mss`, `mss_class` | segment size from the SYN | A1: tunnels lower the MTU |
| `tcp.timestamps`, `sack`, `peer_wscale` | TCP options of the peer | A1: the TCP stack of a proxy or VPN server differs from consumer devices |
| `tcp.login_rtt_ms`, `rtt_ms`, `rttvar_ms` | kernel round trip at login and later | A2, A4 |
| `ping.min_ms`, `median_ms`, `gap_ms`, `proxy_suspected` | keep-alive ping vs. kernel round trip | A2: TCP-terminating proxy |
| `ptr` | class of the reverse-DNS name (vpn, server, residential, other, none) | B8 |
| `client.protocol`, `brand`, `locale`, `vhost`, `bedrock_os` | what the client says; `vhost` only says whether a name or a bare address was typed | locale vs. country, phones on data-centre addresses, scripted clients |
| `client.skin` | keyed hash of the skin URL | D10: one skin on many accounts |
| `client.handshake_to_login_ms`, `login_to_join_ms` | login timing | bots and relays |
| `acct`, `new_acct`, `days_running` | keyed hash of the account; first time seen | C9: fresh accounts per network |
| `net`, `family` | keyed hash of the /24 or /48 | per-network aggregates (A4, C9, D10) |
| `joined`, `complete`, `behind_tcp_proxy` | login never joined (denied, failed) / left before the last sample / TCP fields skipped | |
| `verdict` | Connection Guard's own decision for the address: outcome, reason, flags, each source's status and types, ASN, country | the label every signal is measured against |

Privacy: no address, name, UUID, skin URL or server name is written. Accounts, networks and skins are HMAC-SHA256 hashes (16 hex digits) under a random key in `experiments/key` that never leaves the server, so lines can be compared with each other but not with another server's. Day files are deleted after 14 days, a day stops at 32 MiB, and the `seen-accounts` list (hashes only) keeps at most 200,000 entries. Deleting the `experiments` folder resets everything.

### Deciding what to build

```
python3 analyze.py plugins/connection-guard/experiments [more dirs] [--json report.json] [--min 20]
```

`analyze.py` labels each line from the verdict (vpn, hosting, clean, unlabelled) and prints, per signal and value, how often it fires per label, the lift (rate on vpn logins / rate on clean logins) and how many clean or unlabelled logins it would add, i.e. the ones the current sources miss. A signal is worth building when its lift is high, it fires on a useful share of vpn logins, and its rate on clean logins stays low. Per-network signals (accounts per prefix, share of new accounts, spread of the players' pings behind one prefix, skins shared by three or more accounts) are computed from the hashes. The labels come from the configured sources, so a signal that agrees with them perfectly adds nothing; the interesting rows are high lift with a non-zero "would add".

Testbed run of 25 Oct 2026 (same five players, journal and reverse DNS on): five lines, one per player, with the probe's values as above; `ptr` read `vpn` for the tunnel exit and `residential` for the PPPoE player only because Docker names the containers `cgp-vpn` and `cgp-pppoe`. All verdicts were unlabelled, because the testbed runs with lookups off (`LOCAL_ONLY`).

Further limits of the journal:

- `handshake_to_login_ms` is missing when Velocity handles the handshake event after the login start (it fires that event without waiting), which happens on very fast connections.
- `new_acct` only means something after the journal has run for a while; `analyze.py` ignores it for the first seven days.
- Labels are only as good as the configured sources; with `LOCAL_ONLY` or few lookups most lines stay unlabelled.
