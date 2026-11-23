# PlaceholderAPI

Install PlaceholderAPI and leave `placeholderapi: true` in `integrations.yml`. GuildMaster registers its expansions during startup.

| Placeholder | Result |
|---|---|
| `%guild_name%` | Name of the player's guild |
| `%guild_leader%` | Leader's last known player name |
| `%guild_members%` | Number of current members |
| `%guild_online%` | Number of current members online |
| `%guild_description%` | Guild description |
| `%guild_role%` | Configured rank prefix and name |
| `%guild_alliances%` | Number of confirmed alliances |
| `%guild_home%` | Guild home world |
| `%guild_level%` | Guild level |
| `%guild_kd%` | Average eligible member K/D |
| `%guild_activity%` | Average eligible member hours in the activity period |
| `%guild_score%` | Configured score format |
| `%guild_position%` | Current ranking position for a qualifying guild |

Use `%top1_guilds%` through `%top10_guilds%` by default. Increase `ranking.yml: places` for more places. For each place, the suffixes are `guilds`, `guilds_score`, `guilds_members`, `guilds_kd` and `guilds_activity`.

Use `placeholders.aliases` to map a requested field to a canonical field. For example, `{ title: name }` lets `%guild_title%` resolve to the same value as `%guild_name%`. Configure `unavailable` for empty places or guilds and `disabled` for switches. The default for both is `-`.

The expansion also resolves VaelisKRL's `%vaeliskrl_guild%`, `%vaeliskrl_guild_leader%`, `%vaeliskrl_guild_members%`, `%vaeliskrl_guild_online%` and `%vaeliskrl_guild_alliances%` aliases when the identifier is free.
