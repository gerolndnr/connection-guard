# Evaluate rules before blocking players

**0.6.0 new installations start in ENFORCE and can block connections immediately.** Explicit VPN/proxy/Tor evidence and Blackbox aggregate listings can trigger the configured actions; Blackbox also covers hosting/cloud addresses. ProxyCheck/zowi hosting-only evidence stays review, and the default geo service is Disabled. ip-check.net requires explicit opt-in. Existing modes, enabled providers, keys, timeouts and service order are never overwritten. To evaluate decisions before blocking, explicitly select OBSERVE as shown below. A country match is an access-policy decision, not proof of an attack.

## Observe before enforcing

Edit these existing fields in the generated `config.yml`. This is a **partial example**, not a replacement for the complete config:

```yaml
behavior:
  vpn:
    kick-player: false
    notify-staff: true
    execute-command:
      enabled: false
    send-webhook:
      enabled: false
  geo:
    kick-player: false
    notify-staff: true
    execute-command:
      enabled: false
    send-webhook:
      enabled: false
    type: 'BLACKLIST'
    list: []
```

Preserve other generated settings and required values. Disabling kicks alone does not disable a command/webhook you previously enabled; this example disables those actions too. Staff need `connectionguard.notify.vpn` and `connectionguard.notify.geo`. With an empty country blocklist no geo-match notification is expected. Provider lookups still happen and consume quota.

Use `/cg reload` for supported settings/messages. Provider drafts are validated during reload. Restart after changing cache connection settings. Check successful initialization, then evaluate an ordinary connection and a controlled flagged case in an authorized test environment. `/cg info <IP>` inspects returned data; it is not an accuracy benchmark.

## Choose your policy

After reviewing decisions, set `operation.mode: ENFORCE` and `behavior.vpn.kick-player: true` if your policy rejects flagged VPN/proxy connections. Choose the corresponding geo field and country list for country rules. `BLACKLIST` matches listed countries; `WHITELIST` matches countries outside the list. An empty whitelist is restrictive; do not reuse the empty blacklist example as a whitelist.

Keep `required-positive-flags` between 1 and the number of enabled VPN providers. Raising it reduces which sets of votes cause a flag, including possible true detections; it does not guarantee fewer false positives. [Missing responses do not count as positive votes](PROVIDER_FAILURES.md).

Exceptions are configured separately under `behavior.vpn.exemptions` and `behavior.geo.exemptions`. Permission exceptions also require the respective `use-permission-exemption` setting. [Check the integration](TROUBLESHOOTING.md).

## Providers and messages

[Review limits, recipients and terms](PROVIDERS.md). The 0.6.0 template selects `provider.geo.service: Disabled`, so no geo lookup sends an IP. On existing installations, disabling geo kicks alone does not stop an enabled geo lookup. To enable geo deliberately, one supported choice is `provider.geo.service: 'ProxyCheck'`, using `provider.vpn.proxycheck.api-key` as appropriate for that service. Restart after the change; this does not promise unlimited free requests.

The generated `translation/en.yml` and `message-language` control messages. Kick messages should name the relevant server rule and explain how a legitimate player can get help. Keep secrets out of player-facing messages.
