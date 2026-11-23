# Commands and permissions

The default shorthand commands are `/g`, `/guilds` and `/ga`. Tab completion suggests relevant subcommands and online targets. Player commands require `guildmaster.use`, granted by default. Management actions also check the guild action listed below. See `permissions.yml` for rank definitions.

| Command after `/guild` | Purpose | Guild action |
|---|---|---|
| empty, `help`, `list` | Main menu, help and guild directory | Public |
| `create [NAME] [CURRENCY]` | Enter a name and select payment, or create directly | Guest |
| `info [GUILD]` | Show guild information | `info` for own guild. Directory cards are public |
| `members` | View the roster | `members` |
| `description TEXT`, `rename NAME` | Edit the description or name | `description`, `rename` |
| `leave` | Open a confirmation menu | `leave` |
| `delete`, `disband` | Open the archive confirmation | Owner |
| `transfer PLAYER` | Confirm transferring ownership | Owner |
| `confirm TOKEN` | Run a pending personal confirmation | Permissions checked again |
| `invite PLAYER`, `invitations` | Invite a visible online player or see incoming invitations | `invite` to send |
| `accept GUILD`, `deny GUILD` | Answer an invitation | Invited player |
| `kick PLAYER` | Remove a lower-ranked member | `kick` |
| `apply GUILD`, `applications` | Send an application or view incoming applications | `applications` for staff view |
| `application accept\|deny PLAYER` | Decide an application | `applications` |
| `role PLAYER RANK` | Assign a lower-priority rank. Use `member` to revoke a custom rank | `role` |
| `alliance [list\|info GUILD]`, `war [list\|info GUILD]` | See active relationships and requests | `info` |
| `alliance\|war send\|accept\|deny\|remove GUILD` | Manage bilateral relationships | `alliance`, `war` |
| `sethome`, `home`, `delhome` | Set, teleport to, or clear the guild home | `sethome`, `home`, `delhome` |
| `chat TEXT` | Send private guild chat. The default chat prefix is `* ` | `chat` |
| `history [PAGE]` | Browse recorded events | `history-view-own` |
| `light on\|off` | Control personal entity glow | `light` to enable |
| `settings [home\|recruiting]` | View settings or toggle an option | `settings` |
| `top`, `statistics` | View rankings and current guild statistics | `statistics` for own statistics |
| `announce [TEXT]` | Save and publish an announcement. Omit text to clear it | `announce` |
| `upgrade`, `level` | Increase capacity or show the current level | `upgrade`, `info` |
| `territory claim\|release\|list` | Claim, release or list chunks | `territory` for changes |
| `set centre` | Set a legacy-style guild centre | `territory` |

Each admin command requires `guildmaster.admin.<action>` or `guildmaster.admin`. These permissions default to operators.

| Command after `/guildadmin` | Purpose |
|---|---|
| `help` | List administrative commands available to staff |
| `reload` | Validate and apply reloadable YAML settings |
| `info [GUILD]` | Instance overview or guild details |
| `delete GUILD [TOKEN]` | Archive a guild with confirmation |
| `migrate check` | Validate the configured source and simulate the import |
| `migrate apply [TOKEN]` | Confirm and import the source snapshot |
| `debug` | Show server name, schema version and unresolved payment count |
| `backup` | Create a full SQLite backup |
| `restore FILE [TOKEN]` | Confirm staging a backup for the next restart |
| `payment` | View payments that require review |
| `payment resolve ID verified` | Release a payment reservation after checking the provider and handling the balance |

`guildmaster.territory.bypass` lets operators or authorized staff bypass GuildMaster's chunk protections.
