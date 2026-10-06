# Security decision webhooks

Rich notifications are available since 0.5.0 and remain available in 0.6.0. Webhooks are disabled by default. Global `operation.mode: OBSERVE` suppresses both TEXT and EMBED output, including manual rules and provider failures. Rich notifications do not require a selected addon decision observer.

Rich human labels follow `message-language` (`en`, `de`, `es` or your own file); typed decision/source codes stay unchanged. See [languages](LANGUAGES.md).

## Enable a private notification channel

Create a webhook for the channel you control and store its full HTTPS URL in your server's private configuration. The URL authorizes sending to that channel; keep it out of shared settings and screenshots. Merge the following fields into your existing configuration, preserving your chosen connection policy:

```yaml
behavior:
  vpn:
    send-webhook:
      enabled: true
      url: 'https://discord.com/api/webhooks/WEBHOOK_ID/WEBHOOK_TOKEN'
      format: EMBED
      include-ip: false
      events: [FLAG, DENY, ERROR]
      cooldown-ms: 1000
  geo:
    send-webhook:
      enabled: false
      url: ''
      format: EMBED
      include-ip: false
      events: [FLAG, DENY, ERROR]
      cooldown-ms: 1000
```

Use your actual webhook URL and `/cg reload`. A complete invalid notification draft is rejected before activation; existing settings remain active. Enabled URLs must be HTTPS, at most 2048 characters, without user credentials, fragments, whitespace at the edges or control characters. Other HTTPS endpoints compatible with Discord's JSON contract remain supported; no authentication header is invented. Requests validate TLS and do not follow redirects. Deliberately choose ENFORCE after reviewing your connection policy if the installation is still in OBSERVE.

| Event | When a selected scope can notify |
| --- | --- |
| `FLAG` | A VPN/geo flag, including a flagged connection the guard allows, or a selected manual DENY rule |
| `DENY` | Actual guard denial for the relevant VPN/geo scope; includes selected manual rules, strict UNKNOWN and global external/overload denials |
| `ERROR` | A guard processing error, including a processing error accompanying DENY |
| `UNKNOWN` | Explicit opt-in for incomplete source/check, lost current identity proof or external-admission unavailability, including an allowed connection |

Select one to four unique events. Normal successful checks are quiet. `cooldown-ms` is 0..60000 (default 1000), shared by identical endpoint strings. A suppressed event is dropped, with no later replay. Choosing zero disables this operator cooldown; queue and server rate limits still apply.

## Read a decision

The embed distinguishes actual ALLOW/DENY/ERROR, reason, platform/phase and captured operation mode. It includes the VPN minimum captured when the decision started, check and identity-authority categories, guard duration, actual voting counts/flags, selected or unresolved rules, external admission status, and bounded source facts. Sources retain UNKNOWN/failure reason, voting status, cache provenance, duration, classifications, exact decimal risk, confidence, ASN, country and source expiry where supplied. Missing data stays UNKNOWN, risk alone does not become VPN proof, and values from different providers are not normalized into one score. Successful geo source facts use NEGATIVE in the shared source model; that is not a VPN verdict. Source cache age is not invented from a duration or expiry.

A guard ALLOW result describes this login phase. It does not confirm independent account authentication, every other plugin's decision or a completed backend join. A flag with ALLOW is presented as allowed. Pre-authentication manual denials can have NOT_CHECKED sources. Rule IDs are reported; rule targets and private reasons are omitted. Selected/unresolved trace entries are deduplicated per rule ID and evaluated scope.

Rich messages omit player names, UUIDs, raw provider text, ISP/operator strings, request endpoints and exception details. Addresses are omitted unless `include-ip: true` is explicitly chosen for that rich recipient. `allowed_mentions.parse` is empty for both formats. Messages show at most eight sources and eight relevant rule entries, with shown/total counts. Text fits Discord's field and combined-embed limits; omission is visible. These message/mention limits follow [Discord's message contract](https://docs.discord.com/developers/resources/message).

When both VPN and geo select an EMBED for the identical URL string, one combined message is generated: both scopes appear, address inclusion requires both opt-ins, and the longer cooldown applies. Different URLs retain independent privacy settings. Mixing TEXT and EMBED retains their separate delivery paths; use EMBED for both scopes to combine them.

## Existing text configuration

Missing `format` preserves TEXT and your existing `messages.vpn-webhook` / `messages.geo-webhook` templates. Existing text placeholders still behave as configured and may include names or addresses. `include-ip` and rich event selection do not rewrite those templates; choose EMBED for the typed privacy defaults, or remove unwanted placeholders in TEXT. Text is bounded to 2000 UTF-16 units, strips malformed/control/bidi text and suppresses mentions. Missing cooldown now defaults to 1000 ms. Rich DENY notifications can report strict failures/manual denials which never trigger the legacy positive-result text path.

## Delivery, reload and diagnosis

Notification rendering queues work separately from admission. One daemon worker and sixteen waiting jobs bound dispatch; an overloaded queue drops new notifications. HTTP calls are limited to 2.5 seconds. Failures do not change guard decisions, detector/cache facts or separately selected addon observer delivery. `/cg stats` reports `webhooks sent`, `failed`, `skipped`, `queued`, `active` and `invalid`; none exposes the endpoint or response content. `sent` counts HTTP acceptance, not a confirmed saved Discord message: [Discord's execute-webhook contract](https://docs.discord.com/developers/resources/webhook) allows a `wait=false` request to return without proving message persistence.

HTTP 429 pauses the endpoint, using fractional `Retry-After` or bounded JSON `retry_after`, and honors global flags. A depleted successful bucket can pause using `X-RateLimit-Reset-After`. The local pause is bounded to 1 second..1 hour, with a conservative one-minute fallback. The current failed notification is dropped; there is no automatic retry after a timeout, connection error or unclear POST outcome. These rate-limit signals follow [Discord's rate-limit contract](https://docs.discord.com/developers/topics/rate-limits). Inspect the destination before deliberately generating another notification after an uncertain outcome.

Each successful reload selects a new immutable notification draft. Queued old-draft messages are retired, even when the URL/options are unchanged. Already active requests may have been accepted and cannot be unsent. Disabling the plugin closes/cancels its sender and completes waiting jobs. A re-enabled sender is created only after the old worker has physically stopped; there is no overlapping replacement pool. Generic failure warnings are limited to one per 30 seconds. Counters describe the active sender's lifetime; renderer failures also have a separate diagnostic count.

If messages are missing, check OBSERVE, enable/format/event selection, privacy/template choices, the endpoint and `/cg stats`. A skipped count can mean cooldown, stale draft, queue capacity or shutdown; failed can mean server limit, unsuccessful HTTP or ambiguous delivery. Keep the URL private when requesting help. No automatic test post is sent during initialization, reload or diagnosis.

## Qualification

[The portable owned HTTPS/Velocity fixture](../ci/fixtures/webhooks/README.md) records the exact artifact/source hashes and its bounded synthetic scope. Core fixtures exercise payload bounds/privacy, legacy compatibility, explicit routing, transport limits/errors, same-URL privacy, shared decision capture, observer isolation, stale-draft retirement and physical sender re-enable. Such fixtures establish controlled behavior, not real Discord persistence, detection accuracy, every server version or production adoption.
