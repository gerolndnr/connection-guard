# Controlled native comparison — 5 October 2026

Controlled fixtures, not a production VPN accuracy study. ALLOW means the proxy login gate passed; no backend join or real account authentication is tested.

| Product | Version | Passed | Failed | Unsupported | Setup error | Not tested |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| connection-guard | 0.5.0 | 24 | 0 | 1 | 0 | 0 |
| georestrict | 2.0.2 | 19 | 1 | 5 | 0 | 0 |
| sqidgeon-antivpn | 1.0.0 | 8 | 0 | 15 | 2 | 0 |

Percentiles below are exploratory local timings. Small samples do not establish a reliable p99 or a product ranking. Resource figures cover the whole proxy and are observations, not peak memory or plugin allocations.

| Case | Product | Verdict | Measured / expected | p50 ms | p95 ms | p99 ms | Max batch requests | Observed RSS MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| vpn_positive_v4 | connection-guard | pass | 12 / 12 | 3.14 | 6.82 | 8.23 | 1 | 191.7 |
| vpn_positive_v4 | georestrict | pass | 12 / 12 | 2.40 | 3.52 | 4.36 | 1 | 171.2 |
| vpn_positive_v4 | sqidgeon-antivpn | pass | 12 / 12 | 1.40 | 1.52 | 1.53 | 0 | 166.7 |
| vpn_negative_v4 | connection-guard | pass | 12 / 12 | 2.65 | 4.88 | 5.43 | 1 | 183.6 |
| vpn_negative_v4 | georestrict | pass | 12 / 12 | 2.21 | 2.47 | 2.48 | 1 | 163.0 |
| vpn_negative_v4 | sqidgeon-antivpn | pass | 12 / 12 | 1.39 | 1.55 | 1.57 | 0 | 168.5 |
| vpn_positive_v6 | connection-guard | pass | 12 / 12 | 3.26 | 4.68 | 5.15 | 1 | 196.3 |
| vpn_positive_v6 | georestrict | pass | 12 / 12 | 2.43 | 3.85 | 4.77 | 1 | 165.2 |
| vpn_positive_v6 | sqidgeon-antivpn | pass | 12 / 12 | 1.43 | 1.62 | 1.74 | 0 | 167.5 |
| vpn_negative_v6 | connection-guard | pass | 12 / 12 | 2.82 | 3.44 | 3.62 | 1 | 187.4 |
| vpn_negative_v6 | georestrict | pass | 12 / 12 | 2.34 | 3.00 | 3.39 | 1 | 161.6 |
| vpn_negative_v6 | sqidgeon-antivpn | pass | 12 / 12 | 1.41 | 1.55 | 1.58 | 0 | 166.9 |
| vpn_mapped_v4 | connection-guard | pass | 12 / 12 | 2.94 | 3.16 | 3.19 | 1 | 188.6 |
| vpn_mapped_v4 | georestrict | pass | 12 / 12 | 2.57 | 2.83 | 2.90 | 1 | 172.6 |
| vpn_mapped_v4 | sqidgeon-antivpn | pass | 12 / 12 | 1.42 | 1.72 | 1.80 | 0 | 166.4 |
| provider_503_open | connection-guard | pass | 12 / 12 | 3.10 | 3.45 | 3.61 | 1 | 185.6 |
| provider_503_open | georestrict | pass | 12 / 12 | 2.38 | 2.68 | 2.74 | 1 | 164.8 |
| provider_503_open | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| provider_503_closed | connection-guard | pass | 12 / 12 | 2.69 | 3.12 | 3.15 | 1 | 182.7 |
| provider_503_closed | georestrict | pass | 12 / 12 | 2.39 | 3.60 | 4.60 | 1 | 166.0 |
| provider_503_closed | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| provider_429_open | connection-guard | pass | 12 / 12 | 1.83 | 2.41 | 2.59 | 0 | 187.1 |
| provider_429_open | georestrict | pass | 12 / 12 | 2.15 | 2.70 | 3.08 | 1 | 183.0 |
| provider_429_open | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| provider_timeout_open | connection-guard | pass | 12 / 12 | 510.56 | 515.80 | 518.87 | 1 | 172.6 |
| provider_timeout_open | georestrict | pass | 12 / 12 | 508.04 | 509.70 | 510.13 | 1 | 165.0 |
| provider_timeout_open | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| provider_timeout_closed | connection-guard | pass | 12 / 12 | 512.23 | 515.86 | 516.10 | 1 | 177.2 |
| provider_timeout_closed | georestrict | pass | 12 / 12 | 508.15 | 509.77 | 510.42 | 1 | 157.8 |
| provider_timeout_closed | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| provider_malformed_open | connection-guard | pass | 12 / 12 | 2.67 | 3.00 | 3.02 | 1 | 191.0 |
| provider_malformed_open | georestrict | pass | 12 / 12 | 2.19 | 2.52 | 2.57 | 1 | 160.8 |
| provider_malformed_open | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| provider_missing_open | connection-guard | pass | 12 / 12 | 2.71 | 3.02 | 3.08 | 1 | 180.9 |
| provider_missing_open | georestrict | pass | 12 / 12 | 2.26 | 2.48 | 2.52 | 1 | 159.7 |
| provider_missing_open | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| provider_slow_positive | connection-guard | pass | 12 / 12 | 60.55 | 66.63 | 66.89 | 1 | 198.0 |
| provider_slow_positive | georestrict | pass | 12 / 12 | 59.31 | 63.07 | 63.27 | 1 | 181.7 |
| provider_slow_positive | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| rules_manual_deny | connection-guard | pass | 12 / 12 | 1.53 | 1.75 | 1.81 | 0 | 182.5 |
| rules_manual_deny | georestrict | unsupported | 0 / 12 | — | — | — | — | — |
| rules_manual_deny | sqidgeon-antivpn | error | 0 / 12 | — | — | — | — | — |
| rules_manual_allow | connection-guard | pass | 12 / 12 | 1.54 | 1.64 | 1.67 | 0 | 181.0 |
| rules_manual_allow | georestrict | unsupported | 0 / 12 | — | — | — | — | — |
| rules_manual_allow | sqidgeon-antivpn | error | 0 / 12 | — | — | — | — | — |
| rules_deny_allow_conflict | connection-guard | pass | 12 / 12 | 1.39 | 1.55 | 1.56 | 0 | 196.0 |
| rules_deny_allow_conflict | georestrict | unsupported | 0 / 12 | — | — | — | — | — |
| rules_deny_allow_conflict | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| rules_expiry | connection-guard | pass | 12 / 12 | 3.11 | 3.79 | 4.06 | 1 | 206.0 |
| rules_expiry | georestrict | unsupported | 0 / 12 | — | — | — | — | — |
| rules_expiry | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| geo_fixture_gb | connection-guard | unsupported | 0 / 12 | — | — | — | — | — |
| geo_fixture_gb | georestrict | unsupported | 0 / 12 | — | — | — | — | — |
| geo_fixture_gb | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| geo_block_gb | connection-guard | pass | 12 / 12 | 2.34 | 4.03 | 4.50 | 0 | 197.9 |
| geo_block_gb | georestrict | pass | 12 / 12 | 2.46 | 2.83 | 2.84 | 1 | 164.6 |
| geo_block_gb | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| geo_allow_gb | connection-guard | pass | 12 / 12 | 2.04 | 2.26 | 2.28 | 0 | 183.4 |
| geo_allow_gb | georestrict | pass | 12 / 12 | 2.37 | 8.77 | 14.50 | 1 | 166.9 |
| geo_allow_gb | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| geo_empty_whitelist | connection-guard | pass | 12 / 12 | 2.39 | 2.78 | 2.95 | 0 | 185.1 |
| geo_empty_whitelist | georestrict | pass | 12 / 12 | 2.33 | 2.61 | 2.62 | 1 | 163.2 |
| geo_empty_whitelist | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| cache_disabled | connection-guard | pass | 12 / 12 | 3.12 | 3.66 | 3.70 | 1 | 180.4 |
| cache_disabled | georestrict | pass | 12 / 12 | 2.38 | 6.14 | 7.56 | 1 | 163.6 |
| cache_disabled | sqidgeon-antivpn | pass | 12 / 12 | 1.52 | 1.85 | 1.85 | 0 | 165.6 |
| cache_warm | connection-guard | pass | 12 / 12 | 2.34 | 2.93 | 3.09 | 0 | 195.1 |
| cache_warm | georestrict | pass | 12 / 12 | 1.69 | 1.94 | 2.08 | 0 | 162.2 |
| cache_warm | sqidgeon-antivpn | unsupported | 0 / 12 | — | — | — | — | — |
| same_ip_16 | connection-guard | pass | 192 / 192 | 58.74 | 65.57 | 66.90 | 1 | 191.9 |
| same_ip_16 | georestrict | fail | 192 / 192 | 59.53 | 62.23 | 62.81 | 4 | 165.7 |
| same_ip_16 | sqidgeon-antivpn | pass | 192 / 192 | 2.97 | 4.38 | 4.81 | 0 | 170.3 |
| unique_ip_16 | connection-guard | pass | 192 / 192 | 144.66 | 235.75 | 241.07 | 16 | 190.5 |
| unique_ip_16 | georestrict | pass | 192 / 192 | 145.66 | 235.04 | 251.95 | 16 | 173.5 |
| unique_ip_16 | sqidgeon-antivpn | pass | 192 / 192 | 3.02 | 4.25 | 4.74 | 0 | 171.7 |

## Qualification problems

- sqidgeon-antivpn, rules_manual_deny: artifact_runtime_compatibility
- sqidgeon-antivpn, rules_manual_allow: artifact_runtime_compatibility

## Evidence

- connection-guard: artifact `172d6e4dc7b925efc8498cab46f28807dba47f20750fbfbdb3c56a64002bb503`, dataset `51500dfcae890667386b72755fb183dcfad11875b87142db7bffc532c04f0a96`, adapter `523782f98aad4df41acbac6fd29dfa3187e366528ab577c60d41dd21c929fcac`, environment `569568b26f2b010bd670353d34ddd89661b2674a2e02d181cbc56164fc92ab24`.
- georestrict: artifact `4907800be78f298616b0be0656c7e4b91ed91bc1eb04990d454acfe9886a9196`, dataset `51500dfcae890667386b72755fb183dcfad11875b87142db7bffc532c04f0a96`, adapter `523782f98aad4df41acbac6fd29dfa3187e366528ab577c60d41dd21c929fcac`, environment `569568b26f2b010bd670353d34ddd89661b2674a2e02d181cbc56164fc92ab24`.
- sqidgeon-antivpn: artifact `c66e6da19707a702ddcd45fdc0c9a60624dfb00a1e6330ae8d6efaf0b1410a8d`, dataset `51500dfcae890667386b72755fb183dcfad11875b87142db7bffc532c04f0a96`, adapter `523782f98aad4df41acbac6fd29dfa3187e366528ab577c60d41dd21c929fcac`, environment `569568b26f2b010bd670353d34ddd89661b2674a2e02d181cbc56164fc92ab24`.
