#!/usr/bin/env bash
# Connection probe testbed: Velocity + Connection Guard (probe on) + a minimal backend, and five players on emulated links.
# Docker Desktop only: netem and tunnels are set up with the Docker VM's own tc/ip (nothing is installed).
#
#   VELOCITY=/abs/velocity-3.5.1-615.jar PLUGIN=/abs/connection-guard-*-all.jar BENCH_HARNESS=/abs/mc-antivpn-bench/harness ./run.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE=${IMAGE:-mc-antivpn-bench:dev}
: "${VELOCITY:?}" "${PLUGIN:?}" "${BENCH_HARNESS:?}"
DELAY_MS=${DELAY_MS:-50} OBSERVE_S=${OBSERVE_S:-75}
NET=cgprobe
SCENARIOS=(near far proxy tunnel pppoe)

cleanup() { docker rm -f cgp-server cgp-relay cgp-vpn "${SCENARIOS[@]/#/cgp-}" >/dev/null 2>&1 || true; docker network rm $NET >/dev/null 2>&1 || true; }
cleanup; trap cleanup EXIT
docker network create --internal $NET >/dev/null
mounts=(-v "$HERE":/fixture:ro -v "$BENCH_HARNESS":/bench:ro)
vm() { docker run --rm --privileged --pid=host --entrypoint nsenter "$IMAGE" -t 1 -m -u -n -i -- "$@"; }
in_net() { local pid; pid=$(docker inspect -f '{{.State.Pid}}' "$1"); shift; vm nsenter -t "$pid" -n -- "$@"; }
ip_of() { docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$1"; }

docker run -d --name cgp-server --network $NET "${mounts[@]}" -v "$VELOCITY":/velocity.jar:ro -v "$PLUGIN":/plugin.jar:ro \
  -e GAP_THRESHOLD_MS="${GAP_THRESHOLD_MS:-40}" --entrypoint python3 "$IMAGE" /fixture/server.py >/dev/null
docker run -d --name cgp-relay --network $NET "${mounts[@]}" --entrypoint python3 "$IMAGE" /fixture/relay.py cgp-server 25577 >/dev/null
docker run -d --name cgp-vpn --network $NET --privileged --sysctl net.ipv4.ip_forward=1 --entrypoint sleep "$IMAGE" infinity >/dev/null
for s in "${SCENARIOS[@]}"; do docker run -d --name "cgp-$s" --network $NET --privileged "${mounts[@]}" --entrypoint sleep "$IMAGE" infinity >/dev/null; done

for _ in $(seq 1 120); do [ "$(docker logs cgp-server 2>&1 | grep -c 'Done (')" -ge 2 ] && break; sleep 2; done
docker logs cgp-server 2>&1 | grep -E "probe|Done \(|ERROR|Exception" | grep -v "first start" || true

server=$(ip_of cgp-server) vpn=$(ip_of cgp-vpn) tunnel=$(ip_of cgp-tunnel)
for s in far proxy tunnel; do in_net "cgp-$s" tc qdisc add dev eth0 root netem delay "${DELAY_MS}ms"; done
in_net cgp-pppoe ip link set dev eth0 mtu 1492
# An IP-in-IP tunnel with WireGuard's default MTU, NATed by the "VPN server": the server only sees the VPN's address.
in_net cgp-tunnel ip tunnel add tun0 mode ipip local "$tunnel" remote "$vpn"
in_net cgp-tunnel ip addr add 10.99.0.2/30 dev tun0
in_net cgp-tunnel ip link set tun0 mtu 1420 up
in_net cgp-tunnel ip route add "$server/32" dev tun0
in_net cgp-vpn ip tunnel add tun0 mode ipip local "$vpn" remote "$tunnel"
in_net cgp-vpn ip addr add 10.99.0.1/30 dev tun0
in_net cgp-vpn ip link set tun0 up
docker exec cgp-vpn iptables -t nat -A POSTROUTING -s 10.99.0.0/30 -o eth0 -j MASQUERADE

# macOS ships bash 3.2, which has no associative arrays.
target() { case $1 in proxy) echo cgp-relay;; tunnel) echo "$server";; *) echo cgp-server;; esac; }
player() { case $1 in near) echo Near;; far) echo Far;; proxy) echo Proxied;; tunnel) echo Tunnel;; pppoe) echo Pppoe;; esac; }
for s in "${SCENARIOS[@]}"; do
  docker exec -e OBSERVE_S="$OBSERVE_S" "cgp-$s" python3 /fixture/client.py "$(target "$s")" "$(player "$s")" > "$HERE/.client-$s.log" 2>&1 &
done
wait
cat "$HERE"/.client-*.log; rm -f "$HERE"/.client-*.log
echo "--- probe results (link delay ${DELAY_MS} ms where emulated)"
docker logs cgp-server 2>&1 | grep "Connection probe " | sed 's/^.*Connection probe /Connection probe /'
echo "--- journal (experiments/connection-probe-*.jsonl)"
mkdir -p "$HERE/.journal"
docker exec cgp-server sh -c 'cat /work/plugins/*/experiments/connection-probe-*.jsonl' > "$HERE/.journal/connection-probe-testbed.jsonl"
cat "$HERE/.journal/connection-probe-testbed.jsonl"
python3 "$HERE/analyze.py" "$HERE/.journal" --min 1 | head -12
rm -rf "${HERE:?}/.journal"
