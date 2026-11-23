# VaelisKRL migration

The importer supports SQLite schema versions 1 and 2 from the reviewed VaelisKRL 0.2.3 source. It preserves active and archived guilds, leader/member roles, pending requests, pending and active alliances, event history, centre coordinates and cooldowns, and the stored glow preference. It keeps VaelisKRL's centres separately. Set `legacy-centre-as-home: true` before importing if those locations should also become guild homes.

## Dry run and import

1. Stop VaelisKRL or export a consistent SQLite snapshot that includes committed WAL pages. Keep VaelisKRL and the original database available for rollback. Move the old plugin JAR out of the test server's `plugins/` directory to avoid competing guild commands.
2. Place the source snapshot at `plugins/VaelisKRL/guilds.db` or set `storage.yml: legacy-source` to the relative path.
3. Start GuildMaster. Run `/guildadmin migrate check`. The importer opens the source read-only, validates its schema and data, attempts the import in a transaction, rolls it back, and writes counts in `plugins/GuildMaster/logs/`.
4. Run `/guildadmin migrate apply`, then the confirmation command printed by the plugin. The importer stores a source snapshot and a pre-import destination backup when it has rows to apply.
5. Review the report and validate guild access, membership, alliances and homes in Minecraft. Repeat `migrate check`; an unchanged source should report zero new rows.

A row that conflicts with existing GuildMaster data aborts the import. The safest starting point is an empty GuildMaster database. Use the evidence from the checked copy to resolve conflicts before applying. The importer marks rows already copied, even if they are later modified in GuildMaster. Later edits to previously copied VaelisKRL records are outside this one-time migration workflow.

Imported history and requests are governed by the new history retention and request expiry configuration. Configure those values before importing older records if you need longer retention. VaelisKRL's centre has no yaw or pitch. Opt-in home conversion initializes both to zero. The legacy OpenEco, diamond and squaremap providers are outside the data import. Configure Vault or COMMAND payment and any map integrations for the new server.

## Backups and restore

Run `/guildadmin backup` to create a consistent SQLite backup. For replay, run `/guildadmin restore BACKUP.db`, then repeat the command with its token. During the next startup GuildMaster verifies the staged copy, backs up the current destination database, and restores the staged copy before enabling guild actions.

SQLite snapshots restore guild data and the payment ledger. Reconcile provider balances separately when restoring to an earlier point in time.
