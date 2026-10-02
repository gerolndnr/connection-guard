# A legitimate player is flagged: what to check

A positive provider result is not proof that a player is malicious. Identify the rule, the data and the actual client IP before changing policy.

## Identify the decision

1. Record plugin, platform and Java versions; check successful initialization and registered providers.
2. Determine whether the action came from a VPN/proxy flag or a country rule. Review `behavior.vpn`, `behavior.geo` and `required-positive-flags`.
3. Privately run `/cg info <IP>` for an authorized test address and compare with the expected client IP. Proxy and Geyser/Floodgate forwarding require their own verification. Do not publish IPs or player identities.
4. Review the provider classification and cached data. After correcting the cause, `/cg clear <IP>` removes VPN and geo entries for that address. Fresh requests may return the same classification.
5. [Evaluate with notifications](CONFIGURATION.md) in an authorized test environment. Confirm any unwanted commands/webhooks are also disabled.

A higher vote threshold requires enough enabled providers and can miss genuine VPN/proxy connections. No threshold promises zero false positives. Ask the provider to review an incorrect classification when appropriate.

## Permission exemptions

The permissions are `connectionguard.exemption.vpn` and `connectionguard.exemption.geo`, with separate configuration switches:

```yaml
behavior:
  vpn:
    use-permission-exemption: true
  geo:
    use-permission-exemption: true
```

This is a partial config; edit the existing fields. A permission alone does not enable the feature. All three adapters use LuckPerms for the pre-login exemption lookup; notification permissions are checked separately through each platform. Verify permissions on the **same server/proxy where Connection Guard runs**, with the actual UUID and applicable context. A VPN exemption does not exempt a country rule.

A user reported an exemption problem in 0.4.9. The 0.4.10 HTTP/cache fixtures do not establish every LuckPerms, offline-mode or Floodgate scenario, and do not prove that report fixed. If correct settings still fail, submit a reproducible case. Config exemption lists are a separate path to evaluate, subject to identity/IP checks.

## Limits and provider errors

[Check provider allowance](PROVIDERS.md) and sanitized startup/error output. Repeated full cache clears can worsen request limits. Incomplete provider responses retry on subsequent connections; failures can still consume quota. Restart after provider/cache changes.

## Report a reproducible issue

Use [GitHub issues](https://github.com/gerolndnr/connection-guard/issues) or [Discord](https://discord.gg/GekQVPqsfS). Include versions, proxy/backend arrangement, relevant sanitized settings, expected/actual behavior and steps. For exceptions include the config switch, platform holding permissions and node tested. Remove keys, webhook URLs, player IPs/UUIDs and unrelated private logs.
