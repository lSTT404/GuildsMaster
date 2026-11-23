# GuildMaster

![GuildMaster](../assets/guildmaster-logo.png)

**GuildMaster 1.0.0** is a standalone Bukkit guild plugin by **VaelisWorld**. It combines guild membership, configurable ranks, guild relationships, homes, chat, territory claims, rankings, and persistent SQLite storage.

> **Languages:** English / Russian  
> **Platforms:** Paper, Purpur, and Spigot  
> **Verified compatibility recorded in the test report:** Minecraft 1.21.1 and 1.21.11 on Java 21; selected Minecraft 26.x builds on Java 25. See the [test report](testing.md).

## Features

- Guild creation, invitations, applications, and member management.
- Leadership transfer and guild archiving with event history.
- Configurable internal ranks and action permissions.
- Guild alliances and wars.
- Guild homes, safe teleportation, and access settings.
- Private guild chat, announcements, and event history.
- Personal player glow.
- Chunk claims and territory protection.
- Guild levels, capacity upgrades, and PvP statistics.
- Guild rankings based on PvP and activity.
- Optional PlaceholderAPI, Vault, LuckPerms, and Essentials integrations.
- SQLite storage, automated backups, restore staging, and VaelisKRL import.

## Installation

1. Make sure the server runs a supported Java version. The tested Minecraft 1.21.x builds use Java 21.
2. Put `GuildMaster.jar` into the server's `plugins/` directory.
3. Start the server. GuildMaster creates `plugins/GuildMaster/`, its YAML configuration files, the SQLite database, backup directory, and migration logs.
4. Configure `config.yml`, `mechanics.yml`, `permissions.yml`, `currencies.yml`, `menus.yml`, and `messages.yml` as needed.
5. Use `/guildadmin reload` to reload supported YAML settings. Root command names and aliases, database paths, and PlaceholderAPI identifiers require a restart.

## Quick start

- `/guild` — open the main menu.
- `/guild help` — list player commands.
- `/guild create MyGuild` — start guild creation using the selected currency/payment option.
- `/guild list` — browse guilds.
- `/guildadmin help` — administrative help.

Full command and permission reference: [commands.md](commands.md).

## Documentation

- [Commands and permissions](commands.md)
- [Configuration](configuration.md)
- [PlaceholderAPI](placeholders.md)
- [Migration, backup, and restore](migration.md)
- [Testing and compatibility](testing.md)
- [Design and migration boundaries](../design.md)

## Build from source

JDK 21 or 25 and Maven are required:

```bash
mvn clean verify
```

The built plugin will be at `target/GuildMaster.jar`.

## Integrations

Vault, PlaceholderAPI, LuckPerms, and Essentials are optional. Configure them in `integrations.yml`. PlaceholderAPI itself must be installed to use the GuildMaster expansion.

## Data and safety

GuildMaster stores data in SQLite. The default database is `plugins/GuildMaster/data/guildmaster.db`; backups are stored in `plugins/GuildMaster/data/backups/`. Paid guild creation uses a payment ledger and requires confirmation from the configured payment provider. Do not delete the database or restore an older backup without a rollback plan.

## License

MIT. See [LICENSE](../../LICENSE).
