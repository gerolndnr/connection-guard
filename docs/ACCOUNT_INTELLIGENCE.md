# Account Intelligence

Account Intelligence is the third module next to VPN and Geo detection. VPN and Geo detection judge the connection. Account Intelligence looks at the account: after a player has joined, it checks the account against public ban lists of other Minecraft servers and tells your staff when it finds a ban in force.

**It only informs.** It never denies, kicks or delays a login, and it runs no command. The check starts after the login was admitted, on two workers of its own, so a slow or unreachable ban-list service costs your players nothing.

A ban on another server is that server's claim, not proof. Lists also mark the victims of hacked accounts ("compromised account"), and a famous name can collect bans from impersonators. Read the record, then decide yourself.

## Turn it on

The first provider is [Mizan](https://mizan.ac), an index of public Minecraft ban lists run by KORDU LTD (UK). It is off by default.

```yaml
behavior:
  account:
    notify-staff: true
    notify-categories: [cheating, exploit, evasion]
provider:
  account:
    mizan:
      enabled: true
      api-key: ''
      minute-budget: 100
  cache:
    expiration:
      account: 60
```

Then run `/cg reload`. A file without these keys keeps the module off.

- **Without a key** Mizan answers its public view, about 120 uncached checks a minute per server address.
- **With your own key** from mizan.ac (`api-key: 'mzn_...'`) the limit is 300 a minute per organisation; set `minute-budget` up to 300. The key stays on your server and is never logged.

## What is sent, and to whom

For each premium player who joins, Mizan receives the account's UUID. It receives no IP address and no name. Connection Guard keeps the answers in memory only, for `provider.cache.expiration.account` minutes (default 60), never in SQLite, Redis or the Cloud.

Name Mizan as a recipient in your server's privacy information. Mizan's own terms apply: <https://mizan.ac/terms>. They allow using the records to moderate your own server; they do not allow copying Mizan's index wholesale.

## Who is checked

Only premium Java accounts: a version-4 UUID from an online-mode login. An offline-mode ("cracked") UUID is derived from the name alone, so everyone who picks that name shares it; such accounts are skipped. Bedrock players through Floodgate are skipped as well.

- **Velocity and BungeeCord:** the proxy's own online-mode login decides.
- **Paper/Spigot behind a proxy:** the backend sees the forwarded UUID. Enable Account Intelligence on the proxy only, not on every backend as well, or staff get the same warning twice.

## Which bans warn

`behavior.account.notify-categories` selects the ban categories that warn. The default `[cheating, exploit, evasion]` covers cheats, exploits and ban evasion, and leaves out chat offences and uncategorised bans. Mizan's other keys include `hate`, `threat`, `scam`, `spam`, `advert`, `ptox` and `minge`. `'*'` warns on any ban in force, also one without a category.

Only bans in force count, so an ended or expired ban never warns.

## What staff see

Players with `connectionguard.notify.account` get one line, for example:

> Connection Guard | Steve_1 has a ban in force on public ban lists: cheating (Example SMP). Not proof: /cg account Steve_1

The same line goes to the console even with `notify-staff: false`. One account warns at most once every 10 minutes, so reconnects don't flood the chat.

## Commands

- `/cg account <player|UUID>` shows the record: bans in force with list, category, date, expiry and the list's own reason, plus a link to the full record. Names work for players who joined since the last restart, and a UUID works for anyone. Permission: `connectionguard.command.account`.
- `/cg account status` shows the provider, budget, cache and counters: checks, warnings, accounts that could not be checked, checks skipped because the queue was full, and failures. `/cg providers` shows the same line.

## Limits

- Checks wait in a queue of 512 when the minute budget is used up. A join wave larger than that leaves the rest unchecked until they join again, and `/cg account status` counts them as skipped.
- When Mizan answers HTTP 429, checks pause for the `Retry-After` it names.
- Mizan indexes about 75 public lists, mostly smaller servers; large networks rarely publish their bans. No answer does not mean a clean history.
