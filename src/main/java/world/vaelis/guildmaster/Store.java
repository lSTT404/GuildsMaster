package world.vaelis.guildmaster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

final class Store implements AutoCloseable {
    static final int VERSION = 4;
    interface Work<T> { T run(Connection connection) throws Exception; }
    interface Row<T> { T read(ResultSet row) throws SQLException; }

    private final Path database;
    private final Path backups;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "GuildMaster-SQLite");
        thread.setDaemon(true);
        return thread;
    });

    Store(Path database, Path backups) {
        this.database = database;
        this.backups = backups;
    }

    <T> CompletableFuture<T> submit(Work<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = connect(database)) {
                return work.run(connection);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, worker);
    }

    static Connection connect(Path path) throws Exception {
        Class.forName("org.sqlite.JDBC", true, Store.class.getClassLoader());
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys=ON");
            pragma.execute("PRAGMA busy_timeout=5000");
            pragma.execute("PRAGMA synchronous=FULL");
            return connection;
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    void initialize(Connection connection) throws Exception {
        Files.createDirectories(backups);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
        }
        int version = one(connection, "PRAGMA user_version", row -> row.getInt(1));
        if (version > VERSION) throw new SQLException("GuildMaster database schema is newer than this plugin");
        if (version == 0) transaction(connection, c -> {
            for (String ddl : SCHEMA) execute(c, ddl);
            execute(c, "PRAGMA user_version=1");
            return null;
        });
        if (version < 2) transaction(connection, c -> {
            execute(c, "CREATE INDEX player_activity_period ON activity(day)");
            execute(c, "CREATE INDEX pvp_period ON pvp(at)");
            execute(c, "PRAGMA user_version=2");
            return null;
        });
        if (version < 3) transaction(connection, c -> {
            execute(c,"CREATE TABLE war_kill(id INTEGER PRIMARY KEY, attacker TEXT NOT NULL REFERENCES guild(id), defender TEXT NOT NULL REFERENCES guild(id), at INTEGER NOT NULL)");
            execute(c,"CREATE INDEX war_score ON war_kill(attacker,defender,at)");
            execute(c,"PRAGMA user_version=3");
            return null;
        });
        if (version < 4) transaction(connection, c -> {
            execute(c,"ALTER TABLE pvp ADD COLUMN tracked INTEGER NOT NULL DEFAULT 1");
            execute(c,"CREATE TABLE activity_span(player TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, PRIMARY KEY(player,start), CHECK(start<end))");
            execute(c,"CREATE INDEX span_player_end ON activity_span(player,end)");
            execute(c,"CREATE INDEX span_cutoff ON activity_span(end)");
            execute(c,"PRAGMA user_version=4");
            return null;
        });
    }

    Path backup(Connection c, String label, int retain) throws Exception {
        Files.createDirectories(backups);
        Path output = backups.resolve(label + "-" + Instant.now().toEpochMilli() + "-" + java.util.UUID.randomUUID() + ".db");
        execute(c, "VACUUM INTO ?", output.toAbsolutePath().toString());
        if (retain > 0) {
            List<Path> matching;
            try (var entries = Files.list(backups)) {
                matching = entries.filter(path -> path.getFileName().toString().startsWith(label + "-")
                        && path.toString().endsWith(".db")).sorted(java.util.Comparator.reverseOrder()).toList();
            }
            for (Path old : matching.stream().skip(retain).toList()) Files.deleteIfExists(old);
        }
        return output;
    }

    Path backupPath(String file) {
        if (!file.matches("[A-Za-z0-9._-]+[.]db")) throw new IllegalArgumentException("Invalid backup filename");
        return backups.resolve(file);
    }

    Path database() { return database; }

    static <T> T transaction(Connection c, Work<T> work) throws Exception {
        c.setAutoCommit(false);
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (Exception error) {
            try { c.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            throw error;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(c, sql, args)) {
            if (statement.execute()) return -1;
            return statement.getUpdateCount();
        }
    }

    static <T> T one(Connection c, String sql, Row<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(c, sql, args); ResultSet rows = statement.executeQuery()) {
            return rows.next() ? mapper.read(rows) : null;
        }
    }

    static <T> List<T> list(Connection c, String sql, Row<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement statement = prepare(c, sql, args); ResultSet rows = statement.executeQuery()) {
            List<T> results = new ArrayList<>();
            while (rows.next()) results.add(mapper.read(rows));
            return results;
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement statement = c.prepareStatement(sql);
        try {
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }

    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException error) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final List<String> SCHEMA = List.of(
        """
        CREATE TABLE guild (
          id TEXT PRIMARY KEY, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE,
          description TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL,
          archived INTEGER NOT NULL DEFAULT 0, home_world TEXT, home_uuid TEXT,
          home_x REAL, home_y REAL, home_z REAL, home_yaw REAL, home_pitch REAL,
          home_public INTEGER NOT NULL DEFAULT 1, recruiting INTEGER NOT NULL DEFAULT 1,
          announcement TEXT NOT NULL DEFAULT '', level INTEGER NOT NULL DEFAULT 1,
          experience INTEGER NOT NULL DEFAULT 0, capacity_bonus INTEGER NOT NULL DEFAULT 0
        )""",
        """
        CREATE TABLE member (
          player TEXT PRIMARY KEY, guild TEXT NOT NULL REFERENCES guild(id),
          name TEXT NOT NULL, role TEXT NOT NULL, joined INTEGER NOT NULL)
        """,
        "CREATE UNIQUE INDEX guild_leader ON member(guild) WHERE role='leader'",
        "CREATE INDEX member_guild ON member(guild)",
        """
        CREATE TABLE request (
          kind TEXT NOT NULL, guild TEXT NOT NULL REFERENCES guild(id), player TEXT NOT NULL,
          name TEXT NOT NULL, actor TEXT, created INTEGER NOT NULL, expires INTEGER NOT NULL,
          PRIMARY KEY(kind,guild,player))
        """,
        "CREATE INDEX request_player ON request(player,kind,expires)",
        """
        CREATE TABLE relation (
          kind TEXT NOT NULL, first TEXT NOT NULL REFERENCES guild(id), second TEXT NOT NULL REFERENCES guild(id),
          status TEXT NOT NULL, sender TEXT NOT NULL, created INTEGER NOT NULL, CHECK(first < second),
          PRIMARY KEY(kind,first,second))
        """,
        "CREATE INDEX relation_second ON relation(kind,second,status)",
        """
        CREATE TABLE history (
          id INTEGER PRIMARY KEY, guild TEXT NOT NULL REFERENCES guild(id), actor TEXT,
          event TEXT NOT NULL, detail TEXT NOT NULL, at INTEGER NOT NULL)
        """,
        "CREATE INDEX history_guild ON history(guild,id DESC)",
        """
        CREATE TABLE payment (
          id TEXT PRIMARY KEY, player TEXT NOT NULL, name_key TEXT NOT NULL, currency TEXT NOT NULL,
          amount TEXT NOT NULL, state TEXT NOT NULL, at INTEGER NOT NULL, detail TEXT NOT NULL DEFAULT '')
        """,
        "CREATE UNIQUE INDEX payment_player ON payment(player) WHERE state IN ('reserved','charging','charged','review')",
        "CREATE UNIQUE INDEX payment_name ON payment(name_key) WHERE state IN ('reserved','charging','charged','review')",
        """
        CREATE TABLE territory (
          world TEXT NOT NULL, x INTEGER NOT NULL, z INTEGER NOT NULL, guild TEXT NOT NULL REFERENCES guild(id),
          PRIMARY KEY(world,x,z))
        """,
        "CREATE INDEX territory_guild ON territory(guild)",
        "CREATE TABLE cooldown (scope TEXT NOT NULL, action TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(scope,action))",
        "CREATE TABLE activity (player TEXT NOT NULL, day INTEGER NOT NULL, seconds INTEGER NOT NULL, PRIMARY KEY(player,day))",
        "CREATE TABLE pvp (id INTEGER PRIMARY KEY, killer TEXT NOT NULL, victim TEXT NOT NULL, at INTEGER NOT NULL)",
        "CREATE INDEX pvp_pair ON pvp(killer,victim,at DESC)",
        "CREATE TABLE glow (player TEXT PRIMARY KEY, enabled INTEGER NOT NULL, until INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE legacy_import (entity TEXT NOT NULL, source_key TEXT NOT NULL, PRIMARY KEY(entity,source_key))",
        """
        CREATE TABLE legacy_centre (
          guild TEXT PRIMARY KEY REFERENCES guild(id), world_id TEXT NOT NULL, world_name TEXT NOT NULL,
          x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, cooldown INTEGER NOT NULL)
        """,
        "CREATE TABLE legacy_settings (player TEXT PRIMARY KEY, centre_cooldown INTEGER NOT NULL)"
    );
}
