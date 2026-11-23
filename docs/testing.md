# GuildMaster 1.0.0 verification report

**Date:** 23 November 2026  
**Artifact:** the shaded `GuildMaster.jar`, compiled to Java 21 bytecode. SHA-256 `60310dd4778d4bdccc78bcdc8c6b86ccb4ea0f4f06cceec26462a9160ee4f70a`.

Minecraft's 2026 Java releases use the `26.x` naming scheme. The compatibility table covers exact tested builds. Other patch levels need their own verification. [Mojang version numbering](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system), [Paper Java requirements](https://docs.papermc.io/paper/getting-started/)

## Server matrix

Boot tests covered enable, admin debug, successful reload, rejected invalid reload, SQLite backup, clean shutdown and restart. The server bound to loopback in a disposable directory. Boot tests ran without Vault and LuckPerms. PlaceholderAPI was also absent except on Paper 26.3.

| Platform | Minecraft | Server build | Java | Boot, reload, backup and restart | Gameplay |
|---|---|---|---|---|---|
| Paper | 1.21.1 | 133 / `3cb8529` | 21 | Pass | — |
| Paper | 1.21.11 | 132 / `c5eb079` | 21 | Pass | Pass |
| Paper | 26.1.2 | 74 / `e4e17fc` | 25 | Pass | — |
| Paper | 26.3 | 257 / `d81ca50` | 25 | Pass | Migration and top placeholder |
| Purpur | 1.21.1 | 2329 / `803bf62` | 21 | Pass | — |
| Purpur | 1.21.11 | 2568 / `f57bd86` | 21 | Pass | Pass |
| Purpur | 26.1.2 | 2592 / `405ad83` | 25 | Pass | — |
| Purpur | 26.3 | 2660 / `a040e50` | 25 | Pass | — |
| Spigot | 1.21.1 | 4344 / `a759b62-19bf846` | 21 | Pass | — |
| Spigot | 1.21.11 | 4598 / `56165ca-d74c5d8` | 21 | Pass | Pass |
| Spigot | 26.1.2 | 4632 / `566f972-690a402` | 25 | Pass | — |
| Spigot | 26.3 | 4664 / `bed90fc-e89124d` | 25 | Pass | — |

## Gameplay and persistence

The three 1.21.11 gameplay runs used eight local Minecraft protocol clients, Vault 1.7.3, PlaceholderAPI 2.12.3 and a test-only provider with known balances. Assertions covered one paid Vault currency and two paid COMMAND currencies, duplicate inventory clicks, an explicit command decline, an unacknowledged external charge entering review, and a verified refund after an injected SQLite insert failure.

The same runs exercised invitation and application menus, Member permission denial, a configured Builder role, a confirmed leadership transfer, ally and war proposals in both directions, territory claiming, capacity upgrades, PvP and war kill recording, home teleportation and movement cancellation, glow on/off, history, ranking and statistics menus, scoped chat, top placeholders, feature-switch command guards and persistence after restart.

The separate Paper 1.21.11 run with LuckPerms 5.5.81 verified primary-group rank assignment and the player-scoped guild name, role and member count placeholders.

## VaelisKRL migration

On Paper 26.3, the importer read a consistent copy of an actual VaelisKRL schema 2 database. The dry run predicted 23 rows and the confirmed apply imported 23. A repeated dry run returned zero new rows. After restart, two guilds and two members were active. The source database SHA-256 stayed unchanged during the test.

| Imported entity | Rows |
|---|---:|
| Guild records (including one archived) | 3 |
| Member records | 2 |
| Alliance records | 1 |
| History entries | 12 |
| Legacy centre records | 3 |
| Glow settings | 1 |
| Legacy player cooldowns | 1 |

The source had no pending invitations or applications. A synthetic migration fixture covered both tables, centre-to-home mapping, read-only source hashes, rollback and repeated import.

## Maven tests and storage load

`mvn test` passed 11 tests on JDK 21 and again on JDK 25. Tests cover the payment journal, transactions, ranks, relationships, configuration switches, literal message formatting, ratings, migration and backup restoration. A separate JDK 21 build and test run succeeded after extracting the public sources ZIP.

A synthetic SQLite ranking run with 250 guilds, 3,750 members, 50,000 PvP records and 100,000 activity intervals took 107 ms under JDK 21 and 123 ms under JDK 25 on the test host. This measures the same guild-row query and ranking calculation used by the production worker. The measurement excludes server tick load and network latency.

## Reproducing

- Build and run JVM tests with `mvn clean verify`.
- Run `python scripts/smoke.py --help` for a server-specific boot check. Supply an accepted Minecraft EULA file and use a dedicated test server directory. The optional `--legacy` argument reads the original in SQLite read-only mode.
- Run `python scripts/playtest.py --help` for the loopback gameplay run on a clean Minecraft 1.21.11 test server. It requires a JDK, Python 3.11+, Node.js with `minecraft-protocol` (verified with 1.68.0), a Spigot API JAR, and Vault. The test-only fixture is compiled into the test server directory.

## Remaining coverage

Full player scenarios on Minecraft 26.x, a multi-server production load run, the generic PlaceholderAPI balance verifier with a real third-party economy, and the Essentials AFK bridge were outside the recorded tests. The design notes cover the legacy adapters and glow behavior. [Design and legacy analysis](design.md)
