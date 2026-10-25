#!/usr/bin/env python3
"""Which connection-probe signals are worth building? Reads the probe journal (experiments/connection-probe-*.jsonl)
and compares every signal with Connection Guard's own verdict for the same login.

    python3 analyze.py plugins/connectionguard/experiments [more dirs...] [--json out.json] [--min 20]

Labels: vpn (a source or the verdict said VPN/proxy/Tor/relay), hosting (data-centre address, no VPN verdict),
clean (checked and negative), unlabelled (no verdict for the address). For each signal the report shows how often it
fires per label, its lift (rate on vpn / rate on clean) and how many clean or unlabelled logins it would add: those
are the logins the current sources miss, if the signal is right. A signal is worth building when the lift is high,
it fires on enough vpn logins, and its clean rate is low. Per-network signals (accounts per prefix, shared skins,
spread of the players' round trips behind one prefix) are computed per hashed /24 or /48. Hashes are per server key,
so networks are only comparable within one server's journal.

Standard library only; the journal holds no addresses, names or UUIDs.
"""
import argparse
import glob
import json
import os
import statistics
import sys
from collections import Counter, defaultdict

VPN_TYPES = {"VPN", "PROXY", "TOR", "RELAY"}
LABELS = ("vpn", "hosting", "clean", "unlabelled")


def load(dirs):
    records = []
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, "connection-probe-*.jsonl"))):
            with open(path, encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        try:
                            records.append(json.loads(line))
                        except json.JSONDecodeError:
                            pass
    return records


def label(r):
    v = r.get("verdict")
    if not v:
        return "unlabelled"
    types = set()
    positive = False
    for s in v.get("sources", []):
        t = set(s.get("types", []))
        if s.get("status") == "POSITIVE":
            positive = True
            types |= t
        elif t:
            types |= t
    if v.get("vpn") == "POSITIVE" or v.get("reason") == "VPN_FLAG" or (positive and types & VPN_TYPES):
        return "vpn"
    if "HOSTING" in types:
        return "hosting"
    if v.get("vpn") in ("NEGATIVE", "KNOWN"):
        return "clean"
    return "unlabelled"


def bucket(value, edges):
    if value is None:
        return None
    for edge in edges:
        if value < edge:
            return "<" + str(edge)
    return ">=" + str(edges[-1])


def features(r, net):
    """Every signal as (name, value) pairs; None values are skipped."""
    tcp, ping, client, verdict = r.get("tcp", {}), r.get("ping", {}), r.get("client", {}), r.get("verdict") or {}
    out = {
        "joined": r.get("joined"),
        "left_early": r.get("joined") and not r.get("complete"),
        "behind_tcp_proxy": r.get("behind_tcp_proxy"),
        "family": r.get("family"),
        # A1: segment size and TCP stack
        "mss_class": tcp.get("mss_class"),
        "tcp_timestamps": tcp.get("timestamps"),
        "tcp_sack": tcp.get("sack"),
        "peer_wscale": tcp.get("peer_wscale"),
        # A2: ping gap
        "ping_gap": bucket(ping.get("gap_ms"), [10, 20, 40, 80, 150]),
        "proxy_suspected": ping.get("proxy_suspected"),
        "tcp_rttvar": bucket(tcp.get("rttvar_ms"), [2, 5, 10, 25]),
        # B8: reverse DNS class
        "ptr": r.get("ptr"),
        # Client
        "vhost": client.get("vhost"),
        "brand": brand(client.get("brand")),
        "bedrock_os": client.get("bedrock_os"),
        "protocol": client.get("protocol"),
        "handshake_to_login": bucket(client.get("handshake_to_login_ms"), [50, 200, 1000, 5000]),
        "login_to_join": bucket(client.get("login_to_join_ms"), [200, 500, 1000, 3000]),
        # C9: new accounts (only meaningful after a week of journal)
        "new_account": r.get("new_acct") if r.get("days_running", 0) >= 7 else None,
    }
    locale, country = client.get("locale"), verdict.get("country")
    if locale and country and "_" in locale:
        out["locale_country_mismatch"] = locale.split("_", 1)[1].lower() != country.lower()
    if client.get("bedrock_os") in ("GOOGLE", "IOS", "AMAZON", "WINDOWS_PHONE") and label(r) == "hosting":
        out["mobile_on_hosting"] = True
    n = net.get(r.get("net"))
    if n:
        out["accounts_per_net"] = bucket(n["accounts"], [2, 5, 20, 100])
        out["new_account_share_per_net"] = bucket(n["new_share"], [0.2, 0.5, 0.8]) if n["accounts"] >= 5 else None
        out["ping_spread_per_net"] = bucket(n["ping_spread"], [10, 30, 80]) if n["ping_spread"] is not None else None
    skin = client.get("skin")
    if skin and r.get("_skin_accounts", 0) >= 3:
        out["skin_shared_by_3plus_accounts"] = True
    return {k: v for k, v in out.items() if v is not None}


def brand(value):
    if not value:
        return None
    v = value.lower()
    for known in ("vanilla", "fabric", "forge", "neoforge", "quilt", "lunarclient", "badlion", "feather", "geyser", "labymod"):
        if known in v:
            return known
    return "other"


def per_network(records):
    nets = defaultdict(lambda: {"accounts": set(), "new": set(), "pings": []})
    skins = defaultdict(set)
    for r in records:
        acct = r.get("acct")
        if not acct:
            continue
        n = nets[r["net"]]
        n["accounts"].add(acct)
        if r.get("new_acct"):
            n["new"].add(acct)
        if r.get("ping", {}).get("min_ms") is not None:
            n["pings"].append(r["ping"]["min_ms"])
        skin = r.get("client", {}).get("skin")
        if skin:
            skins[skin].add(acct)
    for r in records:
        skin = r.get("client", {}).get("skin")
        if skin:
            r["_skin_accounts"] = len(skins[skin])
    out = {}
    for key, n in nets.items():
        # One exit, players all over the world: their pings spread far wider than behind one home connection.
        spread = statistics.pstdev(n["pings"]) if len(n["pings"]) >= 3 and len(n["accounts"]) >= 3 else None
        out[key] = {"accounts": len(n["accounts"]), "new_share": len(n["new"]) / len(n["accounts"]), "ping_spread": spread}
    return out


def analyze(records, minimum):
    net = per_network(records)
    totals = Counter(label(r) for r in records)
    table = defaultdict(lambda: Counter())
    for r in records:
        lab = label(r)
        for name, value in features(r, net).items():
            table[(name, str(value).lower())][lab] += 1
    rows = []
    for (name, value), counts in table.items():
        rate = {lab: counts[lab] / totals[lab] if totals[lab] else None for lab in LABELS}
        lift = None
        if rate["vpn"] is not None and rate["clean"]:
            lift = rate["vpn"] / rate["clean"]
        elif rate["vpn"] and rate["clean"] == 0:
            lift = float("inf")
        rows.append({"signal": name, "value": value, **{lab: counts[lab] for lab in LABELS},
                     "vpn_rate": rate["vpn"], "clean_rate": rate["clean"], "lift": lift,
                     "would_add": counts["clean"] + counts["unlabelled"], "enough": counts["vpn"] + counts["clean"] >= minimum})
    rows.sort(key=lambda row: (row["signal"], -(row["lift"] or 0) if row["lift"] != float("inf") else -1e9))
    return totals, rows


def pct(x):
    return "-" if x is None else f"{100 * x:.1f}%"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--json")
    ap.add_argument("--min", type=int, default=20, help="logins (vpn+clean) a value needs before its lift is shown as reliable")
    args = ap.parse_args()
    records = load(args.dirs)
    if not records:
        sys.exit("No journal lines found (experiments/connection-probe-*.jsonl).")
    totals, rows = analyze(records, args.min)
    print(f"{len(records)} logins: " + ", ".join(f"{lab}={totals[lab]}" for lab in LABELS))
    print(f"{'signal':28} {'value':12} {'vpn':>6} {'host':>6} {'clean':>6} {'unlab':>6} {'vpn%':>7} {'clean%':>7} {'lift':>6}  would add")
    last = None
    for row in rows:
        if row["signal"] != last:
            print()
            last = row["signal"]
        lift = "-" if row["lift"] is None else ("inf" if row["lift"] == float("inf") else f"{row['lift']:.1f}")
        flag = "" if row["enough"] else "  (few)"
        print(f"{row['signal']:28} {row['value'][:12]:12} {row['vpn']:6} {row['hosting']:6} {row['clean']:6} {row['unlabelled']:6} "
              f"{pct(row['vpn_rate']):>7} {pct(row['clean_rate']):>7} {lift:>6}  {row['would_add']}{flag}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"totals": totals, "rows": [{**r, "lift": None if r["lift"] == float("inf") else r["lift"]} for r in rows]}, f, indent=1)


if __name__ == "__main__":
    main()
