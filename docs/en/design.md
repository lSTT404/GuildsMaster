# Design and legacy analysis

GuildMaster uses the Spigot 1.21.1 API as its common server interface and Java 21 bytecode. Bukkit-facing listeners and components run on the server thread. The SQLite worker serializes writes, and PlaceholderAPI and territory checks read immutable snapshots. Maven bundles SQLite and MiniMessage into the single plugin JAR. Optional plugin adapters are discovered through Bukkit services or loaded only when their plugin is active.

## VaelisKRL 0.2.3

The old implementation stored active and archived guilds, member UUIDs and ranks, active and pending alliances, applications, invitations, history, centre coordinates and cooldowns, and per-player light preferences. The old database schema calls those coordinates a centre and has no home orientation. The migration makes home mapping opt-in and preserves the centre fields independently.

The old command surface included guild creation, listing, member and request management, alliances, private chat, personal light, the centre command and history. GuildMaster retains those command workflows and adds configurable homes, ranks, currencies, ratings, administration and advanced mechanics.

The vendor-specific OpenEco bridge, direct diamond payment and squaremap marker adapter were part of VaelisKRL. GuildMaster's paid creation uses Vault or verified commands. Its Bukkit glow is entity-visible and uses the viewer's scoreboard team color when available. The old ProtocolLib viewer-specific glow behavior and the squaremap adapter are outside this release.

## Consistency

An indexed payment journal reserves both the player and the normalized guild name. A successful debit moves the reservation to a charged state. The guild, owner and final receipt commit together. A failed guild write rolls back before compensation. An interrupted external command with no confirmed result stays in review so it cannot be retried blindly.

Member UUIDs are unique across guilds and each guild has at most one leader. Rank authorization, cooldowns, limits and the current feature state are checked at mutation time. Alliances and wars use a canonical two-guild key. Archived guilds retain their IDs and relevant history.

SQL migrations use SQLite `user_version`. Legacy rows have import identities independent of later player and guild changes. Backups use SQLite's snapshot mechanism and staged restore verifies the schema and integrity before replacing the database contents.
