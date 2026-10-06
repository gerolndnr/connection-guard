# Recorded ProxyCheck VPN regression answers

`proxycheck-recorded-v2-v3.json` contains all 18 VPN misses from
`mc-antivpn-bench` run `pr75-1242139-detection140` (candidate source
1242139dbc12b144a72104950d485122141d8737 / JAR 5978bfdbe7fb…).
They comprise 8 commercial IPv4, 9 VPN IPv6 and 1 fresh VPN case.
All have a recorded v2 `vpn=1` reply with `proxy=yes, type=VPN`.
13 also have recorded v3 replies; the other 5 v3 replies were not stored
and are deliberately absent. Do not invent replies for missing records.
52 additional v2 negative controls comprise 21 residential IPv4,
17 mobile/CGNAT and 14 residential IPv6 subjects. Recorded types include
Business, Residential and Wireless. 44 of these have recorded v3 replies.

The importer reads the existing benchmark SQLite volume in read-only mode,
without upstream calls. Response bodies are restricted to classification,
country code, ASN/ISP, operator/services and risk/confidence fields. Every
IP key is replaced with a documentation address; no request URLs, keys,
headers, city/coordinate data or private source addresses are committed.
Each record retains the original body SHA-256 and provider-capture time.
Source subject IDs identify the already existing dataset cases.

These cases are regression evidence for parsing the recorded provider signal,
not an independent, held-out detection benchmark or proof of competitive
acceptance. The original 692-subject benchmark and false-positive cohort
remain the external acceptance gate. Provider false positives are not
removed or re-labelled in that benchmark.
