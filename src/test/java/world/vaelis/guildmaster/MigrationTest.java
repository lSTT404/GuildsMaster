package world.vaelis.guildmaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static world.vaelis.guildmaster.Store.*;

final class MigrationTest {
    @TempDir Path directory;

    @Test void dryRunAndRepeatableImportPreserveLegacyRecords() throws Exception {
        Path source=directory.resolve("legacy.db");
        String first=UUID.randomUUID().toString(), second=UUID.randomUUID().toString();
        if (first.compareTo(second)>0) { String swap=first; first=second;second=swap; }
        try (var old=connect(source)) {
            execute(old,"PRAGMA journal_mode=WAL");
            execute(old,"CREATE TABLE schema_version(version INTEGER)");
            execute(old,"INSERT INTO schema_version VALUES(2)");
            execute(old,"CREATE TABLE guilds(id TEXT PRIMARY KEY,name TEXT,name_key TEXT,leader_uuid TEXT,description TEXT,world_uuid TEXT,world_name TEXT,centre_x REAL,centre_y REAL,centre_z REAL,created_at INTEGER,centre_cooldown_until INTEGER,archived INTEGER)");
            execute(old,"CREATE TABLE guild_members(guild_id TEXT,player_uuid TEXT,last_name TEXT,role TEXT,joined_at INTEGER)");
            execute(old,"CREATE TABLE guild_invites(guild_id TEXT,player_uuid TEXT,invited_by TEXT,created_at INTEGER,expires_at INTEGER)");
            execute(old,"CREATE TABLE guild_applications(guild_id TEXT,player_uuid TEXT,player_name TEXT,created_at INTEGER,expires_at INTEGER)");
            execute(old,"CREATE TABLE guild_alliances(guild_a TEXT,guild_b TEXT,status TEXT,proposed_by TEXT,created_at INTEGER)");
            execute(old,"CREATE TABLE guild_history(event_id INTEGER,guild_id TEXT,actor_uuid TEXT,event_type TEXT,details TEXT,created_at INTEGER)");
            execute(old,"CREATE TABLE player_settings(player_uuid TEXT,light_enabled INTEGER,centre_cooldown_until INTEGER)");
            String ada=UUID.randomUUID().toString(), sam=UUID.randomUUID().toString(), other=UUID.randomUUID().toString();
            execute(old,"INSERT INTO guilds VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",first,"Aurora","aurora",ada,"Old description",other,"world",10,64,20,1,555,0);
            execute(old,"INSERT INTO guilds VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",second,"Silver","silver",sam,"",other,"world",11,64,20,1,0,0);
            execute(old,"INSERT INTO guild_members VALUES(?,?,?,?,?)",first,ada,"Ada","LEADER",1);
            execute(old,"INSERT INTO guild_members VALUES(?,?,?,?,?)",second,sam,"Sam","LEADER",1);
            execute(old,"INSERT INTO guild_invites VALUES(?,?,?,?,?)",first,other,ada,1,Long.MAX_VALUE);
            execute(old,"INSERT INTO guild_applications VALUES(?,?,?,?,?)",second,other,"Other",1,Long.MAX_VALUE);
            execute(old,"INSERT INTO guild_alliances VALUES(?,?,?,?,?)",first,second,"ACTIVE",first,1);
            execute(old,"INSERT INTO guild_history VALUES(?,?,?,?,?,?)",1,first,ada,"GUILD_CREATED","Aurora",1);
            execute(old,"INSERT INTO player_settings VALUES(?,?,?)",ada,1,123);
        }
        byte[] before=Files.readAllBytes(source);
        try (Store store=new Store(directory.resolve("guildmaster.db"),directory.resolve("backup"));
             var current=connect(store.database())) {
            store.initialize(current);
            assertEquals(12,LegacyMigration.run(store,current,source,false,true).total());
            assertEquals(0,Store.<Integer>one(current,"SELECT count(*) FROM guild",r->r.getInt(1)));
            var report=LegacyMigration.run(store,current,source,true,true);
            assertEquals(12,report.total());
            assertTrue(Files.isRegularFile(report.destinationBackup()));
            assertTrue(Files.isRegularFile(report.legacyBackup()));
            assertEquals(0,LegacyMigration.run(store,current,source,true,true).total());
            assertEquals(555L,Store.<Long>one(current,"SELECT cooldown FROM legacy_centre WHERE guild=?",r->r.getLong(1),first));
            assertEquals(64.0,one(current,"SELECT home_y FROM guild WHERE id=?",r->r.getDouble(1),first));
            assertEquals("active",one(current,"SELECT status FROM relation",r->r.getString(1)));
        }
        assertArrayEquals(before,Files.readAllBytes(source));
    }
}
