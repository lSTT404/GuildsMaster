package world.vaelis.guildmaster;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;

import static world.vaelis.guildmaster.Store.*;

final class Gameplay implements Listener {
    private final GuildMaster plugin;
    private final Map<String,Long> activity = new HashMap<>();
    private final Map<String,Teleport> teleports = new HashMap<>();
    private final Map<String,Glow> glow = new HashMap<>();
    private final Set<String> glowBusy = new HashSet<>();
    private final Map<String,Team> teams = new HashMap<>();
    private long sampled;
    private final Map<String,Long> joined = new HashMap<>();

    private record Teleport(Guilds.Home home, BukkitTask task) {}
    private record Glow(long until, boolean wasGlowing, Team team) {}

    Gameplay(GuildMaster plugin) { this.plugin = plugin; }

    void start() {
        sampled=System.currentTimeMillis();
        Bukkit.getOnlinePlayers().forEach(this::connected);
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
    }

    void reconfigure() {
        for (String id : List.copyOf(teleports.keySet())) cancel(id,false);
        for (String id : List.copyOf(glow.keySet())) clearGlow(id);
        if (plugin.settings.enabled("guild_glow")) Bukkit.getOnlinePlayers().forEach(this::restoreGlow);
    }

    void stop() {
        for (String id : List.copyOf(teleports.keySet())) cancel(id,false);
        for (String id : List.copyOf(glow.keySet())) clearGlow(id);
        for (Team team : teams.values()) {
            try { team.unregister(); } catch (IllegalStateException ignored) { }
        }
        teams.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (plugin.ready) connected(event.getPlayer());
    }

    private void connected(Player player) {
        joined.put(GuildMaster.id(player),System.currentTimeMillis());
        updateOnline(GuildMaster.id(player),true);
        restoreGlow(player);
        if (plugin.settings.enabled("guild_announcements")) {
            var member = plugin.view.members().get(GuildMaster.id(player));
            var guild = member==null?null:plugin.view.guilds().get(member.guild());
            if (guild!=null && !guild.announcement().isBlank())
                plugin.send(player,"guild.announcement",Map.of("guild",guild.name(),"text",guild.announcement()));
        }
        String id=GuildMaster.id(player), name=player.getName();
        var cached = plugin.view.members().get(id);
        if (cached!=null && !cached.name().equals(name))
            plugin.change(null,c->execute(c,"UPDATE member SET name=? WHERE player=?",name,id),null);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        String id=GuildMaster.id(event.getPlayer());
        cancel(id,false);
        clearGlow(id);
        activity.remove(id);
        joined.remove(id);
        updateOnline(id,false);
    }

    private void updateOnline(String id, boolean joined) {
        Set<String> online = new HashSet<>(plugin.online);
        if (joined) online.add(id); else online.remove(id);
        plugin.online=Set.copyOf(online);
    }

    private void mark(Player player) { activity.put(GuildMaster.id(player),System.currentTimeMillis()); }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void onMove(PlayerMoveEvent event) {
        Location to=event.getTo(), from=event.getFrom();
        if (to==null || to.equals(from)) return;
        mark(event.getPlayer());
        if (plugin.settings.bool("mechanics","home.cancel-on-move") &&
                (!from.getWorld().equals(to.getWorld()) || from.distanceSquared(to)>0.0001)) cancel(GuildMaster.id(event.getPlayer()),true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void onExternalTeleport(PlayerTeleportEvent event) {
        if (plugin.settings.bool("mechanics","home.cancel-on-move") && event.getTo()!=null && !event.getFrom().equals(event.getTo()))
            cancel(GuildMaster.id(event.getPlayer()),true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage()>0 &&
                plugin.settings.bool("mechanics","home.cancel-on-damage")) cancel(GuildMaster.id(player),true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGHEST) public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.ready) return;
        String prefix=plugin.settings.str("config","chat.prefix");
        String text=event.getMessage();
        if (plugin.menus.consumeName(event.getPlayer(),text)) { event.setCancelled(true); return; }
        boolean guild = !prefix.isEmpty() && text.startsWith(prefix);
        if (guild) event.setCancelled(true);
        plugin.main(()->{
            if (!event.getPlayer().isOnline()) return;
            mark(event.getPlayer());
            if (guild) {
                try { chat(event.getPlayer(),text.substring(prefix.length()).strip()); }
                catch (Guilds.Rejected rejected) { plugin.explain(event.getPlayer(),rejected); }
            }
        });
    }

    void chat(Player player, String text) {
        plugin.feature("guild_chat");
        Guilds.require(player.hasPermission("guildmaster.use"),"error.permission");
        Guilds.require(!text.isBlank() && text.length()<=plugin.settings.integer("config","chat.max-length")
                && text.codePoints().noneMatch(Character::isISOControl),"guild.invalid-text");
        var member = plugin.view.members().get(GuildMaster.id(player));
        Guilds.require(member!=null,"guild.not-member");
        Guilds.require(plugin.settings.allows(member.role(),"chat"),"error.rank");
        var guild = plugin.view.guilds().get(member.guild());
        plugin.guildMessage(guild.id(),"guild.chat",Map.of("guild",guild.name(),"player",player.getName(),"text",text));
    }

    @EventHandler(priority=EventPriority.MONITOR) public void onDeath(PlayerDeathEvent event) {
        Player victim=event.getEntity();
        if (!plugin.ready || !(event.getDamageSource().getCausingEntity() instanceof Player killer)
                || killer.getUniqueId().equals(victim.getUniqueId())) return;
        if (!tracksKills(plugin.settings)) return;
        String attacker=GuildMaster.id(killer), defender=GuildMaster.id(victim);
        long now=System.currentTimeMillis();
        plugin.query(null,c->transaction(c,db->{
            Settings settings=plugin.settings;
            if (!tracksKills(settings)) return false;
            boolean counted=Rankings.kill(db,settings,attacker,defender,now);
            if (counted && settings.enabled("guild_wars") && settings.bool("mechanics","wars.count-kills")) {
                execute(db,"""
                    INSERT INTO war_kill(attacker,defender,at)
                    SELECT a.guild,b.guild,? FROM member a JOIN member b
                    JOIN relation r ON r.first=min(a.guild,b.guild) AND r.second=max(a.guild,b.guild)
                    WHERE a.player=? AND b.player=? AND r.kind='war' AND r.status='active'
                    """,now,attacker,defender);
            }
            return counted;
        }),null,null);
    }

    private boolean tracksKills(Settings settings) {
        return settings.enabled("guild_statistics") || settings.enabled("guild_levels")
                || settings.enabled("guild_wars") && settings.bool("mechanics","wars.count-kills");
    }

    private void tick() {
        long now=System.currentTimeMillis();
        for (var entry : List.copyOf(glow.entrySet())) {
            var member=plugin.view.members().get(entry.getKey());
            if (!glowAllowed(member) || entry.getValue().until!=0 && entry.getValue().until<=now) clearGlow(entry.getKey());
        }
        if (now-sampled>=15_000) {
            if (plugin.settings.enabled("guild_statistics")) {
                int timeout=plugin.settings.integer("ranking","afk-seconds");
                List<Rankings.Activity> intervals=new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (externalAfk(player)) continue;
                    String id=GuildMaster.id(player);
                    if (!joined.containsKey(id)) continue;
                    long start=Math.max(Math.max(sampled,now-20_000),joined.get(id));
                    long end=timeout==0?now:Math.min(now,activity.getOrDefault(id,start)+timeout*1000L);
                    if (end/1000>start/1000) intervals.add(new Rankings.Activity(id,start/1000,end/1000));
                }
                if (!intervals.isEmpty()) plugin.query(null,c->transaction(c,db->{
                    if (plugin.settings.enabled("guild_statistics")) Rankings.activity(db,intervals);
                    return null;
                }),null,null);
            }
            sampled=now;
        }
    }

    private boolean externalAfk(Player player) {
        if (plugin.settings.bool("integrations","essentials-afk") && Bukkit.getPluginManager().isPluginEnabled("Essentials")) {
            try {
                Object essentials=Bukkit.getPluginManager().getPlugin("Essentials");
                Object user=essentials.getClass().getMethod("getUser",UUID.class).invoke(essentials,player.getUniqueId());
                return user!=null && (boolean)user.getClass().getMethod("isAfk").invoke(user);
            } catch (ReflectiveOperationException | RuntimeException ignored) { /* Local AFK remains in use. */ }
        }
        return false;
    }

    Guilds.Home position(Player player) {
        Location l=player.getLocation();
        List<String> worlds=plugin.settings.get("mechanics").getStringList("home.allowed-worlds");
        Guilds.require(worlds.isEmpty() || worlds.contains(l.getWorld().getName()),"home.world-disallowed");
        Guilds.require(valid(l),"home.unsafe");
        return new Guilds.Home(l.getWorld().getName(),l.getWorld().getUID().toString(),l.getX(),l.getY(),l.getZ(),l.getYaw(),l.getPitch());
    }

    void home(Player player, Guilds.Home home) {
        String id=GuildMaster.id(player);
        Guilds.require(!teleports.containsKey(id),"home.pending");
        int delay=plugin.settings.integer("mechanics","home.delay-seconds");

        BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,()->{
            Teleport scheduled=teleports.remove(id);
            if (scheduled==null || !player.isOnline() || !plugin.settings.enabled("guild_home")) return;
            var member=plugin.view.members().get(id);
            var guild=member==null?null:plugin.view.guilds().get(member.guild());
            if (guild==null || !Objects.equals(guild.home(),scheduled.home) ||
                    !plugin.settings.allows(member.role(),"home") || !guild.homePublic() && !member.role().equals("leader")) {
                plugin.send(player,"home.cancelled");
                return;
            }
            World world=null;
            try { if (home.uuid()!=null) world=Bukkit.getWorld(UUID.fromString(home.uuid())); }
            catch (IllegalArgumentException ignored) { }
            if (world==null && home.uuid()==null) world=Bukkit.getWorld(home.world());
            if (world==null) { plugin.send(player,"home.world-missing"); return; }
            Location target=new Location(world,home.x(),home.y(),home.z(),home.yaw(),home.pitch());
            if (!valid(target) || !player.teleport(target,PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                plugin.send(player,"home.unsafe"); return;
            }
            plugin.send(player,"home.arrived");
        },delay*20L);
        teleports.put(id,new Teleport(home,task));
        if (delay>0) plugin.send(player,"home.warmup",Map.of("seconds",delay));
    }

    private boolean valid(Location l) {
        World w=l.getWorld();
        if (!Double.isFinite(l.getX()) || !Double.isFinite(l.getY()) || !Double.isFinite(l.getZ()) ||
                !Float.isFinite(l.getYaw()) || !Float.isFinite(l.getPitch()) ||
                l.getY()<w.getMinHeight()+1 || l.getY()>=w.getMaxHeight()-1 || !w.getWorldBorder().isInside(l)) return false;
        if (!plugin.settings.bool("mechanics","home.require-safe-location")) return true;
        Block feet=l.getBlock(), head=feet.getRelative(0,1,0), floor=feet.getRelative(0,-1,0);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid() && floor.getType().isSolid()
                && !hazard(feet.getType()) && !hazard(head.getType()) && !hazard(floor.getType());
    }

    private boolean hazard(Material material) {
        return switch (material) {
            case FIRE, SOUL_FIRE, CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE, POWDER_SNOW, MAGMA_BLOCK,
                    CAMPFIRE, SOUL_CAMPFIRE, LAVA, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    private void cancel(String id, boolean notify) {
        Teleport teleport=teleports.remove(id);
        if (teleport==null) return;
        teleport.task.cancel();
        if (notify) plugin.send(Bukkit.getPlayer(UUID.fromString(id)),"home.cancelled");
    }

    void setGlow(Player player, boolean enabled) {
        String id=GuildMaster.id(player);
        Guilds.require(glowBusy.add(id),"payment.busy");
        Guilds.Person actor=plugin.person(player);
        Settings config=plugin.settings;
        long seconds=config.bool("mechanics","glow.automatic-off")?config.integer("mechanics","glow.duration-seconds"):0;
        long until=seconds==0?0:System.currentTimeMillis()+seconds*1000L;
        BigDecimal cost=BigDecimal.valueOf(config.number("mechanics","glow.cost"));
        if (!enabled || cost.signum()==0) {
            glowWrite(player,actor,enabled,until,config,BigDecimal.ZERO);
            return;
        }
        plugin.query(player,c->{
            var data=new Guilds(c,plugin.settings);
            var member=data.acting(actor,"light");
            Guilds.require(glowAllowed(member),"error.rank");
            Guilds.require(data.glowUntil(id)==null,"glow.already");
            return true;
        },ignored->{
            if (config!=plugin.settings || !player.isOnline()) { glowBusy.remove(id); return; }
            var result=plugin.economy.vault(config,id,cost,false);
            if (result!=Economy.Result.SUCCESS) {
                glowBusy.remove(id);
                plugin.send(player,result==Economy.Result.UNKNOWN?"glow.review":"payment.failed");
                return;
            }
            glowWrite(player,actor,true,until,config,cost);
        },error->{glowBusy.remove(id);plugin.explain(player,error);});
    }

    private void glowWrite(Player player, Guilds.Person actor, boolean enable, long until, Settings config, BigDecimal paid) {
        plugin.query(player,c->transaction(c,db->{
            plugin.feature("guild_glow");
            new Guilds(db,plugin.settings).glow(actor,enable,until);
            return null;
        }),ignored->{
            glowBusy.remove(actor.id());
            if (enable && player.isOnline() && glowAllowed(plugin.view.members().get(actor.id()))) applyGlow(player,until);
            if (!enable) clearGlow(actor.id());
            plugin.send(player,enable?"glow.enabled":"glow.disabled");
        },error->{
            glowBusy.remove(actor.id());
            if (paid.signum()>0 && plugin.economy.vault(config,actor.id(),paid,true)!=Economy.Result.SUCCESS) {
                plugin.getLogger().warning("Glow refund requires review for "+actor.id());
                plugin.send(player,"glow.review");
            } else plugin.explain(player,error);
        });
    }

    private boolean glowAllowed(Guilds.Member member) {
        if (!plugin.settings.enabled("guild_glow") || member==null || !plugin.settings.allows(member.role(),"light")) return false;
        List<String> allow=plugin.settings.get("mechanics").getStringList("glow.roles");
        return allow.isEmpty() || allow.contains(member.role());
    }

    private void restoreGlow(Player player) {
        String id=GuildMaster.id(player);
        if (!glowAllowed(plugin.view.members().get(id))) return;
        plugin.query(null,c->new Guilds(c,plugin.settings).glowUntil(id),until->{
            if (until!=null && player.isOnline() && glowAllowed(plugin.view.members().get(id))) applyGlow(player,until);
        },null);
    }

    private void applyGlow(Player player, long until) {
        String id=GuildMaster.id(player);
        clearGlow(id);
        var scoreboard=Objects.requireNonNull(Bukkit.getScoreboardManager()).getMainScoreboard();
        String color=plugin.settings.str("mechanics","glow.color").toUpperCase(Locale.ROOT);
        Team team=null;
        if (scoreboard.getEntryTeam(player.getName())==null) {
            team=teams.computeIfAbsent(color,key->{
                String name="gm_"+key.toLowerCase(Locale.ROOT);
                if (scoreboard.getTeam(name)!=null) return null;
                Team created=scoreboard.registerNewTeam(name);
                created.setColor(ChatColor.valueOf(key));
                return created;
            });
            if (team!=null) team.addEntry(player.getName());
        }
        glow.put(id,new Glow(until,player.isGlowing(),team));
        player.setGlowing(true);
    }

    private void clearGlow(String id) {
        Glow state=glow.remove(id);
        if (state==null) return;
        Player player=Bukkit.getPlayer(UUID.fromString(id));
        if (player!=null) {
            if (state.team!=null) state.team.removeEntry(player.getName());
            player.setGlowing(state.wasGlowing);
        }
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH) public void onBreak(BlockBreakEvent event) {
        if (protectedFrom(event.getPlayer(),event.getBlock(),"protect-blocks")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH) public void onPlace(BlockPlaceEvent event) {
        if (protectedFrom(event.getPlayer(),event.getBlock(),"protect-blocks")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH) public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedFrom(event.getPlayer(),event.getBlock(),"protect-blocks")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH) public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedFrom(event.getPlayer(),event.getBlock(),"protect-blocks")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH) public void onInteract(PlayerInteractEvent event) {
        mark(event.getPlayer());
        if (event.getClickedBlock()!=null && protectedFrom(event.getPlayer(),event.getClickedBlock(),"protect-interact"))
            event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH) public void onExplosion(EntityExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    @EventHandler(ignoreCancelled=true,priority=EventPriority.HIGH) public void onExplosion(BlockExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    private void protectExplosion(List<Block> blocks) {
        if (plugin.settings.enabled("guild_territories") && plugin.settings.bool("mechanics","territories.protect-blocks"))
            blocks.removeIf(block->owner(block)!=null);
    }

    private String owner(Block block) {
        return plugin.view.claims().get(new View.Chunk(block.getWorld().getUID().toString(),block.getX()>>4,block.getZ()>>4));
    }

    private boolean protectedFrom(Player player, Block block, String rule) {
        if (!plugin.ready || !plugin.settings.enabled("guild_territories") ||
                !plugin.settings.bool("mechanics","territories."+rule) || player.hasPermission("guildmaster.territory.bypass")) return false;
        String owner=owner(block);
        var member=plugin.view.members().get(GuildMaster.id(player));
        if (owner==null || member!=null && owner.equals(member.guild())) return false;
        plugin.send(player,"territory.protected");
        return true;
    }
}
