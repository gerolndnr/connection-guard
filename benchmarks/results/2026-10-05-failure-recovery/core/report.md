# Controlled benchmark results

Controlled fixtures, not a production VPN accuracy study. ALLOW means the proxy login gate passed; no backend join or real account authentication is tested.

| Product | Version | Passed | Failed | Unsupported | Setup error | Not tested |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| connection-guard-core | 0.5.0 | 6 | 0 | 2 | 0 | 0 |

Percentiles below are exploratory local timings. Small samples do not establish a reliable p99 or a product ranking. Resource figures cover the whole proxy and are observations, not peak memory or plugin allocations.

| Case | Product | Verdict | Measured / expected | p50 ms | p95 ms | p99 ms | Max batch requests | Observed RSS MiB |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| failure_429_open | connection-guard-core | pass | 12 / 12 | 0.13 | 0.32 | 0.39 | 1 | — |
| failure_429_closed | connection-guard-core | pass | 12 / 12 | 0.13 | 0.17 | 0.17 | 1 | — |
| failure_malformed_open | connection-guard-core | pass | 12 / 12 | 0.12 | 1.18 | 2.16 | 1 | — |
| failure_malformed_closed | connection-guard-core | pass | 12 / 12 | 0.11 | 0.13 | 0.14 | 1 | — |
| failure_missing_open | connection-guard-core | pass | 12 / 12 | 0.11 | 0.12 | 0.13 | 1 | — |
| failure_missing_closed | connection-guard-core | pass | 12 / 12 | 0.11 | 0.13 | 0.14 | 1 | — |
| positive_cache_control | connection-guard-core | unsupported | 0 / 12 | — | — | — | — | — |
| negative_cache_control | connection-guard-core | unsupported | 0 / 12 | — | — | — | — | — |

## Qualification problems


## Failed product contracts


## Evidence

- connection-guard-core: artifact `172d6e4dc7b925efc8498cab46f28807dba47f20750fbfbdb3c56a64002bb503`, dataset `9f78f0c1d1f84ac90ba04ceab10791bc8dc4b4404c30f44d844f88a9ec3eea26`, adapter `120647d94e7c72eb66f2cafb2dca3b0f5a0075cd50071abd9f346d409babcfad`, environment `9b03834e874cfd5f61ce41f02ec51ee469a5b862c3bb062ee61bc830d55690f6`.
