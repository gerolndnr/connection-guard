# Connection probe testbed

Proof of concept for two observe-only signals on Velocity (`experimental.connection-probe.enabled: true`, `/cg probe`):

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
