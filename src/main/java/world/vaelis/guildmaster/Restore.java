package world.vaelis.guildmaster;

import java.nio.file.*;
import java.sql.Connection;
import java.sql.SQLException;
import org.sqlite.SQLiteConnection;

import static world.vaelis.guildmaster.Store.*;

final class Restore {
    private static final String STAGED = "restore-pending.db";

    static void stage(Store store, String filename) throws Exception {
        if (filename.equals(STAGED)) throw new IllegalArgumentException("Select a backup created by GuildMaster");
        Path source = store.backupPath(filename);
        verify(source);
        Path staged = store.backupPath(STAGED);
        Path temp = Files.createTempFile(staged.getParent(),"restore-",".tmp");
        try {
            Files.copy(source,temp,StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp,staged,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void applyPending(Store store, Connection c) throws Exception {
        Path staged = store.backupPath(STAGED);
        if (!Files.isRegularFile(staged)) return;
        verify(staged);
        store.backup(c,"before-restore",0);
        int status = c.unwrap(SQLiteConnection.class).getDatabase().restore("main",staged.toAbsolutePath().toString(),null);
        if (status!=0) throw new SQLException("SQLite restore failed with status "+status);
        Files.delete(staged);
    }

    private static void verify(Path input) throws Exception {
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("Backup file does not exist");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:"+input.toUri()+"?mode=ro")) {
            int schema=one(c,"PRAGMA user_version",row->row.getInt(1));
            if (schema<1 || schema>Store.VERSION || !"ok".equals(one(c,"PRAGMA integrity_check",row->row.getString(1))) ||
                    one(c,"SELECT 1 FROM sqlite_master WHERE name='guild' AND type='table'",row->row.getInt(1))==null)
                throw new SQLException("Invalid GuildMaster backup");
        }
    }
}
