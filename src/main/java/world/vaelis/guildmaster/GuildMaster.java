package world.vaelis.guildmaster;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

import static world.vaelis.guildmaster.Store.*;

public final class GuildMaster extends JavaPlugin {
    volatile Settings settings;
    volatile View view = View.EMPTY;
    volatile Set<String> online = Set.of();
    private View workerView = View.EMPTY;
    volatile boolean ready;
    private volatile boolean stopping;
    Store store;
    Economy economy;
    Menus menus;
    Gameplay gameplay;
    GuildCommands commands;
    private BukkitTask rankingTask, backupTask;
    private Runnable clearPlaceholders = () -> {};

    @Override public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath().resolve("guilds"));
            Files.createDirectories(getDataFolder().toPath().resolve("logs"));
            settings = new Settings(this);
            store = new Store(localPath(settings,"database"),localPath(settings,"backups"));
            Files.createDirectories(store.database().getParent());
            economy = new Economy(this);
            commands = new GuildCommands(this);
            menus = new Menus(this);
            gameplay = new Gameplay(this);
            commands.register();
            getServer().getPluginManager().registerEvents(menus,this);
            getServer().getPluginManager().registerEvents(gameplay,this);
            online = Bukkit.getOnlinePlayers().stream().map(GuildMaster::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
            query(null,c -> {
                Restore.applyPending(store,c);
                store.initialize(c);
                execute(c,"UPDATE payment SET state='cancelled',detail='restart before charge' WHERE state='reserved'");
                execute(c,"UPDATE payment SET state='review',detail='restart during external operation' WHERE state IN ('charging','charged')");
                capture(c,true);
                return null;
            }, ignored -> {
                ready = true;
                bridge();
                reschedule();
                gameplay.start();
                getLogger().info("GuildMaster ready");
            }, error -> {
                getLogger().log(Level.SEVERE,"Cannot initialize GuildMaster storage",error);
                getServer().getPluginManager().disablePlugin(this);
            });
        } catch (Exception error) {
            getLogger().log(Level.SEVERE,"Cannot start GuildMaster: "+error.getMessage(),error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    Path localPath(Settings config, String key) {
        Path base = getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = base.resolve(config.str("storage",key)).normalize();
        if (target.equals(base) || !target.startsWith(base)) throw Settings.bad("storage",key,"must stay within the plugin directory");
        return target;
    }

    Path legacyPath() {
        Path root = Path.of("").toAbsolutePath().normalize();
        Path path = root.resolve(settings.str("storage","legacy-source")).normalize();
        if (path.equals(root) || !path.startsWith(root)) throw Settings.bad("storage","legacy-source","must be inside the server directory");
        return path;
    }

    boolean reload(CommandSender sender) {
        try {
            Settings candidate = new Settings(this);
            localPath(candidate,"database");
            localPath(candidate,"backups");
            for (String path : List.of("database","backups"))
                if (!candidate.str("storage",path).equals(settings.str("storage",path)))
                    throw Settings.bad("storage",path,"restart required for storage path changes");
            for (String path : List.of("placeholders.guild-id","placeholders.top-prefix"))
                if (!candidate.str("integrations",path).equals(settings.str("integrations",path)))
                    throw Settings.bad("integrations",path,"restart required for identifier changes");
            for (String path : List.of("commands.guild","commands.admin"))
                for (String suffix : List.of(".name",".aliases"))
                    if (!Objects.equals(candidate.get("config").get(path+suffix),settings.get("config").get(path+suffix)))
                        throw Settings.bad("config",path+suffix,"restart required for root command changes");
            commands.validateAliases(candidate);
            boolean bridgeChanged = settings.bool("integrations","placeholderapi")!=candidate.bool("integrations","placeholderapi")
                    || settings.integer("ranking","places")!=candidate.integer("ranking","places");
            settings = candidate;
            menus.closeAll();
            commands.clearConfirmations();
            gameplay.reconfigure();
            if (bridgeChanged) bridge();
            reschedule();
            recalculate();
            send(sender,"admin.reloaded");
            return true;
        } catch (Exception error) {
            send(sender,"admin.invalid",Map.of("error",error.getMessage()));
            getLogger().log(Level.WARNING,"GuildMaster configuration rejected",error);
            return false;
        }
    }

    private void bridge() {
        clearPlaceholders.run();
        clearPlaceholders=()->{};
        if (settings.bool("integrations","placeholderapi") && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { clearPlaceholders = GuildPlaceholders.registerFor(this); }
            catch (LinkageError | RuntimeException error) { getLogger().log(Level.WARNING,"PlaceholderAPI integration unavailable",error); }
        }
    }

    private void reschedule() {
        if (rankingTask!=null) rankingTask.cancel();
        if (backupTask!=null) backupTask.cancel();
        long ranking = settings.integer("ranking","recalculate-seconds")*20L;
        rankingTask = Bukkit.getScheduler().runTaskTimer(this,this::recalculate,ranking,ranking);
        long backup = settings.integer("storage","backup-minutes")*1200L;
        if (backup>0) backupTask = Bukkit.getScheduler().runTaskTimer(this,() ->
                query(null,c->store.backup(c,"automatic",settings.integer("storage","backup-keep")),path->{},null),backup,backup);
    }

    private void recalculate() {
        query(null,c->{
            if (settings.enabled("guilds")) transaction(c,db->{new Guilds(db,settings).prune(); return null;});
            capture(c,true);
            return null;
        },null,null);
    }

    void capture(Connection c, boolean ranking) throws Exception {
        workerView = View.read(c,settings,workerView,ranking);
        view = workerView;
    }

    <T> void change(CommandSender sender, Work<T> work, Consumer<T> callback) {
        query(sender,c -> {
            T value = transaction(c,work);
            try {
                capture(c,false);
            } catch (Exception error) {
                getLogger().log(Level.SEVERE,"Mutation committed; cannot refresh the guild read view",error);
            }
            return value;
        },callback,null);
    }

    <T> void query(CommandSender sender, Work<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        if (stopping) return;
        CompletableFuture<T> future = store.submit(work);
        future.whenComplete((value,error)->{
            if (done==null && error==null) return;
            main(()->{
                if (error!=null) {
                    Throwable cause=error.getCause()==null?error:error.getCause();
                    if (failed==null) explain(sender,cause); else failed.accept(cause);
                } else if (done!=null) done.accept(value);
            });
        });
    }

    void main(Runnable task) {
        if (stopping || !isEnabled()) return;
        try { Bukkit.getScheduler().runTask(this,task); }
        catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Shutdown raced the completion. */ }
    }

    @Override public void onDisable() {
        stopping=true;
        ready=false;
        if (gameplay!=null) gameplay.stop();
        if (menus!=null) menus.closeAll();
        clearPlaceholders.run();
        Bukkit.getServicesManager().unregisterAll(this);
        if (store!=null) store.close();
    }

    void explain(CommandSender sender, Throwable cause) {
        if (cause instanceof Guilds.Rejected error) {
            send(sender,error.key,error.values);
        } else {
            send(sender,"error.storage");
            getLogger().log(Level.WARNING,"GuildMaster operation failed",cause);
        }
    }

    void feature(String key) {
        if (!settings.enabled(key)) throw new Guilds.Rejected("error.disabled",Map.of("feature",settings.str("messages","feature."+key)));
    }

    void send(CommandSender sender, String key) { send(sender,key,Map.of()); }
    void send(CommandSender sender, String key, Map<String,?> values) {
        if (sender==null || sender instanceof Player p && !p.isOnline()) return;
        String text = settings.message(key,values,true);
        if (!text.isEmpty()) sender.sendMessage(text);
    }

    void guildMessage(String guild, String key, Map<String,?> values) {
        for (Guilds.Member member : view.roster(guild)) send(Bukkit.getPlayer(UUID.fromString(member.player())),key,values);
    }

    static String id(Player player) { return player.getUniqueId().toString(); }
    Guilds.Person person(Player player) { return new Guilds.Person(id(player),player.getName(),initialRole(player)); }

    String initialRole(Player player) {
        if (settings.enabled("guild_permissions") && settings.bool("integrations","luckperms") &&
                Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            var manager = Bukkit.getServicesManager();
            for (Class<?> type : manager.getKnownServices()) {
                if (!type.getName().equals("net.luckperms.api.LuckPerms")) continue;
                try {
                    Object api = manager.load(type);
                    if (api==null) continue;
                    Object adapter = type.getMethod("getPlayerAdapter",Class.class).invoke(api,Player.class);
                    ClassLoader loader = type.getClassLoader();
                    Class<?> adapterType = Class.forName("net.luckperms.api.platform.PlayerAdapter",false,loader);
                    Object user = adapterType.getMethod("getUser",Object.class).invoke(adapter,player);
                    Class<?> userType = Class.forName("net.luckperms.api.model.user.User",false,loader);
                    String group = (String)userType.getMethod("getPrimaryGroup").invoke(user);
                    String role=settings.str("permissions","luckperms-groups."+group);
                    if (settings.roles.containsKey(role) && !role.equals("leader")) return role;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    getLogger().log(Level.FINE,"LuckPerms rank lookup failed for "+id(player),error);
                }
            }
        }
        return "member";
    }

    void migration(CommandSender sender, boolean apply) {
        Path source = legacyPath();
        boolean home = settings.bool("storage","legacy-centre-as-home");
        query(sender,c->{
            var report = LegacyMigration.run(store,c,source,apply,home);
            if (apply) capture(c,true);
            String content = "Mode: "+(apply?"apply":"check")+"\nRows: "+report.copied()+"\n"+
                    "Source snapshot: "+fileName(report.legacyBackup())+"\nDestination backup: "+fileName(report.destinationBackup())+"\n";
            String name="migration-"+Instant.now().toEpochMilli()+"-"+UUID.randomUUID()+".txt";
            Files.writeString(getDataFolder().toPath().resolve("logs").resolve(name),content);
            return Map.of("rows",report.total(),"report",name);
        },result->send(sender,apply?"admin.migrated":"admin.migration-check",result),error->{
            send(sender,"admin.invalid",Map.of("error",Objects.toString(error.getMessage(),"")));
            getLogger().log(Level.WARNING,"VaelisKRL migration failed",error);
        });
    }

    private static String fileName(Path path) { return path==null?"-":path.getFileName().toString(); }
}
