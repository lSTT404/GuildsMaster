package world.vaelis.guildmaster;

import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static world.vaelis.guildmaster.Store.*;

final class LegacyMigration {
    record Report(Map<String,Integer> copied, Path legacyBackup, Path destinationBackup, boolean applied) {
        int total() { return copied.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    static Path snapshot(Path source, Path directory) throws Exception {
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("VaelisKRL SQLite file does not exist");
        Files.createDirectories(directory);
        Path copy = directory.resolve("vaeliskrl-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".db");
        try (Connection read = DriverManager.getConnection("jdbc:sqlite:" + source.toUri() + "?mode=ro")) {
            execute(read,"PRAGMA busy_timeout=5000");
            execute(read,"VACUUM INTO ?",copy.toAbsolutePath().toString());
        } catch (Exception error) {
            Files.deleteIfExists(copy);
            throw error;
        }
        return copy;
    }

    static Report run(Store store, Connection c, Path source, boolean apply, boolean centreAsHome) throws Exception {
        if (Files.isSameFile(store.database(),source)) throw new IllegalArgumentException("Source and destination must differ");
        Path copy = snapshot(source,store.backupPath("unused.db").getParent());
        try {
            execute(c,"ATTACH DATABASE ? AS legacy",copy.toUri()+"?mode=ro");
            try {
                validate(c);
                Map<String,Integer> counts = transfer(c,centreAsHome,false);
                Path destinationBackup = null;
                if (apply && counts.values().stream().anyMatch(n->n>0)) {
                    destinationBackup = store.backup(c,"pre-migration",0);
                    counts = transfer(c,centreAsHome,true);
                }
                return new Report(counts,apply?copy:null,destinationBackup,apply);
            } finally {
                execute(c,"DETACH DATABASE legacy");
            }
        } finally {
            if (!apply) Files.deleteIfExists(copy);
        }
    }

    private static void validate(Connection c) throws SQLException {
        String integrity = one(c,"PRAGMA legacy.integrity_check",row->row.getString(1));
        if (!"ok".equals(integrity)) throw new SQLException("VaelisKRL integrity check failed: "+integrity);
        Set<String> tables = Set.copyOf(list(c,"SELECT name FROM legacy.sqlite_master WHERE type='table'",row->row.getString(1)));
        if (!tables.containsAll(Set.of("schema_version","guilds","guild_members","guild_invites",
                "guild_applications","guild_alliances","guild_history","player_settings")))
            throw new SQLException("Unrecognized VaelisKRL schema: missing tables");
        var versions = list(c,"SELECT version FROM legacy.schema_version",row->row.getInt(1));
        if (versions.size()!=1 || !List.of(1,2).contains(versions.getFirst()))
            throw new SQLException("Unsupported VaelisKRL schema version "+versions);
        var fk = one(c,"PRAGMA legacy.foreign_key_check",row->row.getString(1));
        if (fk!=null) throw new SQLException("Invalid VaelisKRL foreign key in "+fk);
        if (one(c,"""
                SELECT count(*) FROM legacy.guilds g LEFT JOIN legacy.guild_members m
                ON m.guild_id=g.id AND m.role='LEADER' AND m.player_uuid=g.leader_uuid
                WHERE g.archived=0 AND m.player_uuid IS NULL
                """,row->row.getInt(1))>0)
            throw new SQLException("An active VaelisKRL guild lacks its recorded leader");
    }

    private static Map<String,Integer> transfer(Connection c, boolean asHome, boolean commit) throws Exception {
        c.setAutoCommit(false);
        try {
            Map<String,Integer> counts = new LinkedHashMap<>();
            migrate(c,counts,"guild","guilds g","g.id","""
                    INSERT INTO guild(id,name,name_key,description,created,archived,home_world,home_uuid,home_x,home_y,home_z,home_yaw,home_pitch)
                    SELECT g.id,g.name,g.name_key,g.description,g.created_at,g.archived,
                    CASE WHEN ? THEN g.world_name END,CASE WHEN ? THEN g.world_uuid END,
                    CASE WHEN ? THEN g.centre_x END,CASE WHEN ? THEN g.centre_y END,CASE WHEN ? THEN g.centre_z END,0,0
                    """,asHome,asHome,asHome,asHome,asHome);
            String cooldownField = list(c,"PRAGMA legacy.table_info(guilds)",row->row.getString("name"))
                    .contains("centre_cooldown_until") ? "g.centre_cooldown_until" : "0";
            migrate(c,counts,"centre","guilds g","g.id","INSERT INTO legacy_centre SELECT g.id,g.world_uuid,g.world_name,g.centre_x,g.centre_y,g.centre_z,"+cooldownField);
            migrate(c,counts,"member","guild_members m","m.guild_id||':'||m.player_uuid",
                    "INSERT INTO member SELECT m.player_uuid,m.guild_id,m.last_name,lower(m.role),m.joined_at");
            migrate(c,counts,"invite","guild_invites i","i.guild_id||':'||i.player_uuid||':'||i.created_at",
                    "INSERT INTO request SELECT 'invite',i.guild_id,i.player_uuid,i.player_uuid,i.invited_by,i.created_at,i.expires_at");
            migrate(c,counts,"application","guild_applications a","a.guild_id||':'||a.player_uuid||':'||a.created_at",
                    "INSERT INTO request SELECT 'application',a.guild_id,a.player_uuid,a.player_name,a.player_uuid,a.created_at,a.expires_at");
            migrate(c,counts,"alliance","guild_alliances a","a.guild_a||':'||a.guild_b||':'||a.created_at",
                    "INSERT INTO relation SELECT 'alliance',a.guild_a,a.guild_b,lower(a.status),a.proposed_by,a.created_at");
            migrate(c,counts,"history","guild_history h","h.guild_id||':'||h.event_id",
                    "INSERT INTO history(guild,actor,event,detail,at) SELECT h.guild_id,h.actor_uuid,h.event_type,h.details,h.created_at");
            migrate(c,counts,"glow","player_settings p","p.player_uuid",
                    "INSERT INTO glow SELECT p.player_uuid,p.light_enabled,0");
            String playerCooldown = list(c,"PRAGMA legacy.table_info(player_settings)",row->row.getString("name"))
                    .contains("centre_cooldown_until") ? "p.centre_cooldown_until" : "0";
            migrate(c,counts,"legacy-settings","player_settings p","p.player_uuid","INSERT INTO legacy_settings SELECT p.player_uuid,"+playerCooldown);
            if (commit) c.commit(); else c.rollback();
            return Collections.unmodifiableMap(counts);
        } catch (SQLException error) {
            c.rollback();
            throw new SQLException("VaelisKRL import rolled back. Resolve conflicting or invalid records: "+error.getMessage(),error);
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static void migrate(Connection c, Map<String,Integer> report, String type, String from,
                                String key, String select, Object... args) throws SQLException {
        String filter = " FROM legacy."+from+" WHERE NOT EXISTS (SELECT 1 FROM legacy_import WHERE entity=? AND source_key="+key+")";
        Object[] params = Arrays.copyOf(args,args.length+1);
        params[args.length] = type;
        int count = execute(c,select+filter,params);
        execute(c,"INSERT INTO legacy_import SELECT ?,"+key+filter,type,type);
        report.put(type,count);
    }
}
