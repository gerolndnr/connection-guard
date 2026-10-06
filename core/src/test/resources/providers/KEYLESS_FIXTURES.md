# Keyless response regressions

`keyless-recorded.json` contains 82 subjects with genuine answers from the existing
read-only Docker benchmark cache: 82 Blackbox, 75 ip-check.net, 68 zowi and 82
IPQuery responses. Cohort IDs come from the currently available private dataset;
the dataset has changed since some earlier captures, so this overlap does not
reconstruct every subject of the earlier 140-address run. Exact text flags and
allowlisted security booleans are preserved; queried IPs are documentation literals.
Original response-body hashes and capture timestamps remain attributable.

The separately authorized ten HTTPS requests on 2026-10-06 produced
`keyless-vpn-recapture.json`: five subjects selected from the existing VPN cohort
and its earlier Blackbox-positive/IPQuery-negative summary. All five NEW captures
have Blackbox Y and IPQuery's three negative security flags. Their native Blackbox
requests are replayed on owned loopback HTTP and compared with the captured
IPQuery parser verdict. They do **not** purport to be the unavailable original five
miss bodies or a blind held-out cohort. The full 692-subject benchmark, with and
without a key, false-positive comparison, burst coverage and five-second timeout
acceptance remain mandatory before merging the candidate.

Parser/transport fault fixtures (E, invalid text/JSON, absent fields, hosting-only,
429 with Retry-After, redirect, oversized body and timeout) are explicitly synthetic.
No production provider is contacted by tests. Known positive flags on subjects
labelled residential are retained, not relabelled or removed to improve a score.
All source names, keys, full URLs, headers, player data and unrelated response
metadata are excluded from committed fixtures.
