# Commands and permissions

Default root commands are `/guild` (aliases `/g`, `/guilds`) and `/guildadmin` (alias `/ga`). Tab completion suggests relevant subcommands and online targets.

Player commands require `guildmaster.use` (default: `true`). Guild management actions are additionally checked against the internal rank in `permissions.yml`. These rank actions are **not Bukkit permissions**; they are the action allowlist assigned to a guild role.

## Player commands

All commands below are entered after `/guild`.

| Command | Purpose | Internal rank action |
|---|---|---|
| `/guild`, `help`, `list` | Main menu, help, and guild directory | Public |
| `create [NAME] [CURRENCY]` | Create a guild; without arguments opens the creation flow | Guest |
| `info [GUILD]` | Show own or specified guild information | `info` for own guild; directory cards are public |
| `members` | View the roster | `members` |
| `description TEXT` | Edit the description | `description` |
| `rename NAME` | Rename the guild | `rename` |
| `leave` | Leave through a confirmation menu | `leave` |
| `delete`, `disband` | Archive the guild after confirmation | Leader only |
| `transfer PLAYER` | Transfer leadership after confirmation | Leader only |
| `confirm TOKEN` | Confirm a pending personal action | Permissions are checked again |
| `invite PLAYER` | Invite a visible online player | `invite` |
| `invitations` | View incoming invitations | Invited player |
| `accept GUILD`, `deny GUILD` | Accept or decline an invitation | Invited player |
| `kick PLAYER` | Remove a lower-ranked member | `kick` |
| `apply GUILD` | Apply to a guild | Player without guild membership |
| `applications` | View incoming applications | `applications` |
| `application accept\|deny PLAYER` | Accept or decline an application | `applications` |
| `role PLAYER RANK` | Assign a lower-priority rank; `member` revokes a custom rank | `role` |
| `alliance [list\|info GUILD]` | View alliances and requests | `info` |
| `war [list\|info GUILD]` | View wars and requests | `info` |
| `alliance\|war send\|accept\|deny\|remove GUILD` | Manage bilateral relationships | `alliance` or `war` |
| `sethome` | Set the guild home | `sethome` |
| `home` | Teleport to the guild home | `home` |
| `delhome` | Delete the guild home | `delhome` |
| `chat TEXT` | Send a private guild chat message | `chat` |
| `history [PAGE]` | Browse event history | `history-view-own` |
| `light on\|off` | Toggle personal entity glow | `light` |
| `settings [home\|recruiting]` | View or change guild settings | `settings` |
| `top` | Open rankings | `info` / ranking mechanic must be enabled |
| `statistics` | View own guild statistics | `statistics` |
| `announce [TEXT]` | Set an announcement; omit text to clear it | `announce` |
| `upgrade` | Upgrade guild capacity | `upgrade` |
| `level` | View guild level | `info` |
| `territory claim\|release\|list` | Claim, release, or list chunks | `territory` for changes |
| `set centre` | Set a legacy-style guild centre | `territory` |

## Administrative commands

`/guildadmin` requires `guildmaster.admin` or the corresponding `guildmaster.admin.<action>`. These Bukkit permissions default to operators.

| Command | Permission | Purpose |
|---|---|---|
| `/guildadmin help` | `guildmaster.admin` or access to admin actions | Help |
| `/guildadmin reload` | `guildmaster.admin.reload` | Validate and apply reloadable YAML settings |
| `/guildadmin info [GUILD]` | `guildmaster.admin.info` | Plugin overview or guild details |
| `/guildadmin delete GUILD [TOKEN]` | `guildmaster.admin.delete` | Archive a guild with confirmation |
| `/guildadmin migrate check` | `guildmaster.admin.migrate` | Validate source and simulate import |
| `/guildadmin migrate apply [TOKEN]` | `guildmaster.admin.migrate` | Confirm the import |
| `/guildadmin debug` | `guildmaster.admin.debug` | Server name, schema version, unresolved payment count |
| `/guildadmin backup` | `guildmaster.admin.backup` | Create a SQLite backup |
| `/guildadmin restore FILE [TOKEN]` | `guildmaster.admin.restore` | Stage a restore for the next startup |
| `/guildadmin payment` | `guildmaster.admin.payment` | List payments requiring review |
| `/guildadmin payment resolve ID verified` | `guildmaster.admin.payment` | Release a reservation after checking provider and balance |

`guildmaster.territory.bypass` allows staff to bypass GuildMaster chunk protections.

## Bukkit permissions

| Permission | Default | Description |
|---|---|---|
| `guildmaster.use` | `true` | Access player commands and menus |
| `guildmaster.admin` | `op` | Full administrative access |
| `guildmaster.admin.reload` | `op` | Reload settings |
| `guildmaster.admin.info` | `op` | Administrative information |
| `guildmaster.admin.delete` | `op` | Admin guild archive |
| `guildmaster.admin.migrate` | `op` | Validate and import legacy database |
| `guildmaster.admin.debug` | `op` | Diagnostics |
| `guildmaster.admin.backup` | `op` | Create backup |
| `guildmaster.admin.restore` | `op` | Stage database restore |
| `guildmaster.admin.payment` | `op` | Review and resolve payments |
| `guildmaster.territory.bypass` | `op` | Bypass territory protection |

## Internal guild-role actions

`permissions.yml` configures permissions for members inside a guild. These are not Bukkit permission nodes.

- `leader`: the single protected owner, priority `1000`, all actions (`*`).
- `member`: baseline actions `info`, `members`, `chat`, `home`, `light`, `statistics`, `history-view-own`, and `leave`.

Custom roles define `display-name`, `priority` (1–999), `color`, `prefix`, and an `actions` list. The leader is protected and always retains management access. Assign a role with `/guild role PLAYER RANK`.

Actions used by the role system: `info`, `members`, `description`, `rename`, `leave`, `disband`, `invite`, `kick`, `applications`, `role`, `alliance`, `war`, `sethome`, `home`, `delhome`, `chat`, `history-view-own`, `light`, `settings`, `statistics`, `announce`, `upgrade`, and `territory`.

## Examples

```text
/guild
/guild create Vaelis
/guild invite PlayerName
/guild role PlayerName builder
/guild sethome
/guild home
/guild territory claim
/guildadmin backup
```

See [configuration.md](../configuration.md#permissionsyml) for role configuration.
