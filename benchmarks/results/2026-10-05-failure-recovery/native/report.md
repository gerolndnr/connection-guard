# Controlled benchmark results

Controlled fixtures, not a production VPN accuracy study. ALLOW means the proxy login gate passed; no backend join or real account authentication is tested.

| Product | Version | Passed | Failed | Unsupported | Setup error | Not tested |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| connection-guard | 0.5.0 | 8 | 0 | 0 | 0 | 0 |
| georestrict | 2.0.2 | 6 | 2 | 0 | 0 | 0 |

Percentiles below are exploratory local timings. Small samples do not establish a reliable p99 or a product ranking. Resource figures cover the whole proxy and are observations, not peak memory or plugin allocations.

| Case | Product | Verdict | Measured / expected | p50 ms | p95 ms | p99 ms | Max batch requests | Observed RSS MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| failure_429_open | connection-guard | pass | 12 / 12 | 2.25 | 5.84 | 6.13 | 0 | 185.0 |
| failure_429_open | georestrict | fail | 12 / 12 | 2.62 | 9.76 | 12.54 | 1 | 156.0 |
| failure_429_closed | connection-guard | pass | 12 / 12 | 2.52 | 6.12 | 6.88 | 0 | 186.6 |
| failure_429_closed | georestrict | fail | 12 / 12 | 2.72 | 3.90 | 4.59 | 1 | 165.3 |
| failure_malformed_open | connection-guard | pass | 12 / 12 | 4.48 | 7.09 | 7.58 | 1 | 184.8 |
| failure_malformed_open | georestrict | pass | 12 / 12 | 2.77 | 5.07 | 6.62 | 1 | 161.7 |
| failure_malformed_closed | connection-guard | pass | 12 / 12 | 3.59 | 5.88 | 6.27 | 1 | 189.1 |
| failure_malformed_closed | georestrict | pass | 12 / 12 | 3.70 | 9.52 | 13.31 | 1 | 162.8 |
| failure_missing_open | connection-guard | pass | 12 / 12 | 3.67 | 7.42 | 8.78 | 1 | 182.5 |
| failure_missing_open | georestrict | pass | 12 / 12 | 4.14 | 6.47 | 7.05 | 1 | 151.6 |
| failure_missing_closed | connection-guard | pass | 12 / 12 | 3.57 | 4.08 | 4.13 | 1 | 184.9 |
| failure_missing_closed | georestrict | pass | 12 / 12 | 3.63 | 6.42 | 8.45 | 1 | 158.9 |
| positive_cache_control | connection-guard | pass | 12 / 12 | 3.25 | 5.75 | 5.80 | 0 | 184.1 |
| positive_cache_control | georestrict | pass | 12 / 12 | 1.93 | 2.81 | 2.90 | 0 | 150.1 |
| negative_cache_control | connection-guard | pass | 12 / 12 | 2.78 | 4.70 | 4.90 | 0 | 182.3 |
| negative_cache_control | georestrict | pass | 12 / 12 | 2.04 | 4.82 | 6.58 | 0 | 144.7 |

## Qualification problems


## Failed product contracts

- georestrict, failure_429_open: retry_after_pause_not_preserved
- georestrict, failure_429_closed: retry_after_pause_not_preserved

## Evidence

- connection-guard: artifact `172d6e4dc7b925efc8498cab46f28807dba47f20750fbfbdb3c56a64002bb503`, dataset `9f78f0c1d1f84ac90ba04ceab10791bc8dc4b4404c30f44d844f88a9ec3eea26`, adapter `208ad11b2af161343038a1df4416ca5029a273dcc1bb5c8b78c192f74ac073c0`, environment `569568b26f2b010bd670353d34ddd89661b2674a2e02d181cbc56164fc92ab24`.
- georestrict: artifact `4907800be78f298616b0be0656c7e4b91ed91bc1eb04990d454acfe9886a9196`, dataset `9f78f0c1d1f84ac90ba04ceab10791bc8dc4b4404c30f44d844f88a9ec3eea26`, adapter `208ad11b2af161343038a1df4416ca5029a273dcc1bb5c8b78c192f74ac073c0`, environment `569568b26f2b010bd670353d34ddd89661b2674a2e02d181cbc56164fc92ab24`.
