# Controlled typed-source CG baseline — 5 October 2026

Controlled fixtures, not a production VPN accuracy study. ALLOW means the proxy login gate passed; no backend join or real account authentication is tested.

| Product | Version | Passed | Failed | Unsupported | Setup error | Not tested |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| connection-guard | 0.5.0 | 17 | 0 | 8 | 0 | 0 |

Percentiles below are exploratory local timings. Small samples do not establish a reliable p99 or a product ranking. Resource figures cover the whole proxy and are observations, not peak memory or plugin allocations.

| Case | Product | Verdict | Measured / expected | p50 ms | p95 ms | p99 ms | Max batch requests | Observed RSS MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| vpn_positive_v4 | connection-guard | pass | 12 / 12 | 0.15 | 0.29 | 0.32 | 1 | — |
| vpn_negative_v4 | connection-guard | pass | 12 / 12 | 0.14 | 0.16 | 0.17 | 1 | — |
| vpn_positive_v6 | connection-guard | pass | 12 / 12 | 0.16 | 0.20 | 0.22 | 1 | — |
| vpn_negative_v6 | connection-guard | pass | 12 / 12 | 0.14 | 0.18 | 0.18 | 1 | — |
| vpn_mapped_v4 | connection-guard | pass | 12 / 12 | 0.12 | 0.27 | 0.32 | 1 | — |
| provider_503_open | connection-guard | pass | 12 / 12 | 0.11 | 0.15 | 0.15 | 1 | — |
| provider_503_closed | connection-guard | pass | 12 / 12 | 0.11 | 0.19 | 0.19 | 1 | — |
| provider_429_open | connection-guard | pass | 12 / 12 | 0.10 | 0.33 | 0.36 | 1 | — |
| provider_timeout_open | connection-guard | pass | 12 / 12 | 354.22 | 358.16 | 359.99 | 1 | — |
| provider_timeout_closed | connection-guard | pass | 12 / 12 | 354.18 | 355.76 | 355.96 | 1 | — |
| provider_malformed_open | connection-guard | pass | 12 / 12 | 0.24 | 0.45 | 0.49 | 1 | — |
| provider_missing_open | connection-guard | pass | 12 / 12 | 0.19 | 0.25 | 0.27 | 1 | — |
| provider_slow_positive | connection-guard | pass | 12 / 12 | 55.53 | 57.03 | 57.45 | 1 | — |
| rules_manual_deny | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| rules_manual_allow | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| rules_deny_allow_conflict | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| rules_expiry | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| geo_fixture_gb | connection-guard | pass | 12 / 12 | 0.19 | 0.30 | 0.32 | 1 | — |
| geo_block_gb | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| geo_allow_gb | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| geo_empty_whitelist | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| cache_disabled | connection-guard | pass | 12 / 12 | 0.18 | 0.26 | 0.33 | 1 | — |
| cache_warm | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| same_ip_16 | connection-guard | pass | 192 / 192 | 54.19 | 55.84 | 55.98 | 1 | — |
| unique_ip_16 | connection-guard | pass | 192 / 192 | 54.60 | 57.61 | 57.67 | 16 | — |

## Qualification problems


## Evidence

- connection-guard: artifact `172d6e4dc7b925efc8498cab46f28807dba47f20750fbfbdb3c56a64002bb503`, dataset `769333325ea6c273f4e61ce8e058671f2abde00cb7e0aa1b320444678f4da5ce`, adapter `0d965f3bb4b5a026d5f114e60f166071218e7f5ff4435067aca8926ad74a53a6`, environment `9b03834e874cfd5f61ce41f02ec51ee469a5b862c3bb062ee61bc830d55690f6`.
