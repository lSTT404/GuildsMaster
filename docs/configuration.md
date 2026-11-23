# Configuration

GuildMaster supplies missing files on startup and reads bundled defaults for omitted keys. It preserves the YAML on disk. A rejected reload leaves the active configuration in place and reports the file and key. Restart after changing root command names and aliases, database paths, or PlaceholderAPI identifiers.

## config.yml

- `guild.*` controls name length and regex, restricted words, descriptions, membership cap, request durations and limits, alliances, and history retention. Names entered for a new guild are one argument.
- `cooldowns.*` stores action cooldowns in seconds. Requests, descriptions, renames, guild homes and centres have separate limits.
- `commands.guild` and `commands.admin` add names and aliases while preserving the original root commands. `commands.guild-actions` and `commands.admin-actions` map canonical actions to extra aliases. Example: `guild-actions: { rename: [name] }`.
- `safety.*` configures command rate limits and confirmation time. `chat.*` configures the guild chat prefix and the message-length limit.

## mechanics.yml

All individual switches start enabled. `guilds` is the master switch. Disabled features retain stored data, block associated player actions, and use the configured placeholder fallback when applicable. Disabling `guild_top_gui` routes `/guild top` to chat. Disabling `guild_statistics` makes standings unavailable. Disabling the optional ranking calculation leaves guild activity and K/D visible in statistics.

`home` configures warmup, allowed worlds, safe landing checks and movement or damage cancellation. `glow` configures color, rank allowlist, duration, automatic expiration and optional Vault price. `levels` governs XP per counted PvP kill, XP per level and the cap. `upgrades` controls capacity bonuses and minimum level. `territories` controls chunk limits, allowed worlds, and protections for block edits, explosions and interactions. `wars` sets the cap and controls recording kills during active wars.

## permissions.yml

Default roles are `leader` and `member`. The leader is the single owner and always retains management access. Custom IDs can use lower-case ASCII letters, digits, hyphens or underscores. Set a display name, color, prefix, priority from 1–999, and a whitelist of [guild actions](commands.md). Grant and revoke roles with `/guild role`. With LuckPerms installed, `luckperms-groups` can map primary groups to an initial custom role when a member joins.

## currencies.yml

Enable up to three currencies. Choose a display name, price and either `VAULT` or `COMMAND`. Vault uses the registered default economy service. Give separate denominations their own adapters or commands. Zero-cost entries work with no economy plugin.

Commands run on the server thread as the console. Substitutions are `{player}`, `{uuid}`, `{amount}`, `{guild}`, `{currency}` and `{transaction}`. For nonzero amounts, choose one way to verify the outcome:

- **Bukkit acknowledgement service.** Pass `{transaction}` to the command. A trusted server-side adapter loads `world.vaelis.guildmaster.api.PaymentAcknowledgements` from Bukkit's ServicesManager and calls `acknowledge(transaction, phase, outcome)` on the server thread before returning. Acknowledge only the result already confirmed by the underlying provider. The refund command uses phase `REFUND`. Debit uses `WITHDRAW`.
- **Numeric balance placeholder.** Set `balance-placeholder` to a player-scoped PlaceholderAPI balance that renders an unformatted decimal. The provider must apply changes synchronously and expose the updated balance immediately. A change equal to the configured amount confirms the debit or refund. Counters formatted with grouping separators or asynchronous providers require a different adapter.

The SQLite ledger reserves the player and guild name before dispatch, commits confirmed payments with guild creation, and attempts compensation when creation fails after a confirmed charge. Ambiguous outcomes enter review. Check the provider before releasing a review with `/guildadmin payment resolve ID verified`. Payment verification rules apply to configured withdrawal and refund commands.

## menus.yml and messages.yml

`messages.yml` uses MiniMessage. Empty message values suppress output. User-supplied text passed in braces is formatted as literal text. `menus.yml` defines inventory titles, sizes, slots, items, lore, sounds, permission or feature visibility, and optional CustomModelData. Row slots bind a page of current data. Buttons can use a canonical guild action with tokens as individual arguments, or `@main`, `@name`, `@next`, `@previous` or `@close`. Use `right-action` for a secondary click action.

## ranking.yml

The default score is `1000 × (0.60 × min(K/D ÷ 5, 1) + 0.40 × min(hours ÷ 28, 1))`. The plugin averages qualifying members, caps individual values, uses a configurable minimum death denominator, and ignores repeated killer–victim events inside the cooldown. Activity is measured in the rolling window. The PvP window can be lifetime or a configured number of days. The YAML controls membership and sample thresholds, weights, normalization, formatting, places, tie-breaks and recalculation interval. Local AFK detection is configurable and can be combined with Essentials.

## integrations.yml and storage.yml

Integrations can be enabled independently. `placeholders` controls identifiers, field aliases and values for unavailable or disabled results. `storage.yml` configures SQLite paths, retention and frequency of backups, and the migration source. See the [migration and restore procedure](migration.md).
