package world.vaelis.guildmaster;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static world.vaelis.guildmaster.Guilds.require;

final class Menus implements Listener {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final Map<String,String> FEATURES=Map.ofEntries(
            Map.entry("applications","applications"),Map.entry("invitations","invitations"),Map.entry("alliance","alliances"),
            Map.entry("alliance-info","alliances"),Map.entry("war","guild_wars"),Map.entry("war-info","guild_wars"),
            Map.entry("history","guild_history"),Map.entry("top","guild_top_gui"),Map.entry("statistics","guild_statistics"));
    private final GuildMaster plugin;
    private final Map<UUID,Long> input=new ConcurrentHashMap<>();
    private record Button(String left, String right, Map<String,?> values, String sound) {}

    private static final class Screen implements InventoryHolder {
        final String id;
        final Settings config;
        final Map<String,?> values;
        final List<Map<String,?>> rows;
        final int page;
        final Map<Integer,Button> buttons=new HashMap<>();
        Inventory inventory;

        Screen(String id, Settings config, Map<String,?> values, List<Map<String,?>> rows, int page) {
            this.id=id;this.config=config;this.values=values;this.rows=rows;this.page=page;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    Menus(GuildMaster plugin) { this.plugin=plugin; }

    private void check(String menu) {
        plugin.feature("guilds");
        String feature=FEATURES.get(menu);
        if (feature!=null) plugin.feature(feature);
        if (menu.equals("top")) {
            plugin.feature("guild_ranking");
            plugin.feature("guild_statistics");
        }
    }

    void closeAll() {
        input.clear();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Screen) p.closeInventory();
    }

    void show(Player player, String id, Map<String,?> values, List<Map<String,?>> rows, int page) {
        if (!player.isOnline() || !player.hasPermission("guildmaster.use")) return;
        try { check(id); } catch (Guilds.Rejected error) { plugin.explain(player,error);return; }
        Settings config=plugin.settings;
        String path="menus."+id;
        List<Integer> slots=config.get("menus").getIntegerList(path+".row.slots");
        int pages=slots.isEmpty()?1:Math.max(1,(rows.size()+slots.size()-1)/slots.size());
        int current=Math.clamp(page,1,pages);
        Map<String,Object> vars=new HashMap<>(values);
        vars.put("page",current);
        vars.put("pages",pages);
        Screen screen=new Screen(id,config,values,rows,current);
        int size=config.integer("menus",path+".size");
        String title=config.format(config.str("menus",path+".title"),vars);
        screen.inventory=Bukkit.createInventory(screen,size,title);
        var items=config.get("menus").getConfigurationSection(path+".items");
        if (items!=null) for (String key : items.getKeys(false)) {
            String p=path+".items."+key;
            String action=config.str("menus",p+".action");
            if ((action.equals("@next") && current==pages) || (action.equals("@previous") && current==1)
                    || !visible(player,config,p)) continue;
            item(screen,p,config.integer("menus",p+".slot"),vars);
        }
        for (int i=0;i<slots.size();i++) {
            int index=(current-1)*slots.size()+i;
            if (index>=rows.size()) break;
            Map<String,Object> row=new HashMap<>(vars);
            row.putAll(rows.get(index));
            item(screen,path+".row",slots.get(i),row);
        }
        player.openInventory(screen.inventory);
    }

    private boolean visible(Player player, Settings config, String path) {
        String feature=config.str("menus",path+".feature");
        if (!feature.isEmpty() && !config.enabled(feature)) return false;
        String required=config.str("menus",path+".permission");
        String state=config.str("menus",path+".state");
        Guilds.Member member=plugin.view.members().get(GuildMaster.id(player));
        if (state.equals("member") && member==null || state.equals("guest") && member!=null) return false;
        return required.isEmpty() || member!=null && config.allows(member.role(),required);
    }

    private void item(Screen screen, String path, int slot, Map<String,?> values) {
        Settings config=screen.config;
        Material type=Material.matchMaterial(config.str("menus",path+".material"));
        var stack=new ItemStack(Objects.requireNonNull(type));
        var meta=Objects.requireNonNull(stack.getItemMeta());
        meta.setDisplayName(config.format(config.str("menus",path+".name"),values));
        meta.setLore(config.get("menus").getStringList(path+".lore").stream().map(line->config.format(line,values)).toList());
        if (config.get("menus").isInt(path+".custom-model-data")) meta.setCustomModelData(config.integer("menus",path+".custom-model-data"));
        stack.setItemMeta(meta);
        screen.inventory.setItem(slot,stack);
        screen.buttons.put(slot,new Button(config.str("menus",path+".action"),config.str("menus",path+".right-action"),
                Map.copyOf(values),config.str("menus",path+".sound")));
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Screen) event.setCancelled(true);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Screen screen)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !event.isLeftClick() && !event.isRightClick()) return;
        Button button=screen.buttons.get(event.getRawSlot());
        if (button==null || event.getRawSlot()<0 || event.getRawSlot()>=screen.inventory.getSize()) return;
        String action=event.isRightClick() && !button.right.isEmpty()?button.right:button.left;
        if (action.isEmpty()) return;
        plugin.main(()->{
            if (!player.isOnline() || screen.config!=plugin.settings || player.getOpenInventory().getTopInventory()!=screen.inventory) return;
            try {
                check(screen.id);
                require(player.hasPermission("guildmaster.use"),"error.permission");
                sound(player,button.sound);
                switch (action) {
                    case "@next" -> show(player,screen.id,screen.values,screen.rows,screen.page+1);
                    case "@previous" -> show(player,screen.id,screen.values,screen.rows,screen.page-1);
                    case "@close" -> player.closeInventory();
                    case "@main" -> show(player,"main",Map.of(),List.of(),1);
                    case "@name" -> askName(player);
                    default -> {
                        player.closeInventory();
                        String[] args=action.strip().split(" +");
                        for (int index=0;index<args.length;index++) {
                            String token=args[index];
                            if (token.startsWith("{") && token.endsWith("}")) {
                                Object value=button.values.get(token.substring(1,token.length()-1));
                                require(value!=null,"error.usage");
                                args[index]=value.toString();
                            }
                        }
                        plugin.commands.dispatch(player,args,false,true);
                    }
                }
            } catch (Guilds.Rejected error) { plugin.explain(player,error); }
        });
    }

    private void sound(Player player, String name) {
        if (name.isEmpty()) return;
        NamespacedKey key=NamespacedKey.fromString(name);
        Sound sound=key==null?null:Registry.SOUNDS.get(key);
        if (sound!=null) player.playSound(player.getLocation(),sound,0.7f,1f);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { input.remove(event.getPlayer().getUniqueId()); }

    private void askName(Player p) {
        require(!plugin.view.members().containsKey(GuildMaster.id(p)),"guild.already-member");
        p.closeInventory();
        input.put(p.getUniqueId(),System.currentTimeMillis()+30_000);
        plugin.send(p,"guild.name-prompt",Map.of("cancel",plugin.settings.str("messages","value.cancel")));
    }

    boolean consumeName(Player p, String text) {
        Long expiry=input.remove(p.getUniqueId());
        if (expiry==null || expiry<System.currentTimeMillis()) return false;
        plugin.main(()->{
            if (!p.isOnline()) return;
            if (text.strip().equalsIgnoreCase(plugin.settings.str("messages","value.cancel"))) {
                plugin.send(p,"guild.name-cancelled");
                return;
            }
            plugin.commands.dispatch(p,new String[]{"create",text.strip()},false,true);
        });
        return true;
    }

    void create(Player p) {
        require(!plugin.view.members().containsKey(GuildMaster.id(p)),"guild.already-member");
        show(p,"create",Map.of(),List.of(),1);
    }

    void currency(Player p, String name) {
        require(!plugin.view.members().containsKey(GuildMaster.id(p)),"guild.already-member");
        Guilds.validateName(plugin.settings,name);
        List<Map<String,?>> rows=new ArrayList<>();
        for (Settings.Currency c : plugin.settings.currencies.values())
            rows.add(Map.of("guild",name,"currency",c.name(),"currency_id",c.id(),"amount",c.amount().toPlainString()));
        if (rows.isEmpty()) { plugin.send(p,"payment.currency");return; }
        show(p,"currency",Map.of("guild",name),rows,1);
    }

    void guildList(Player p) {
        List<Map<String,?>> rows=new ArrayList<>();
        for (var guild : plugin.view.guilds().values()) rows.add(details(guild,p));
        show(p,"list",Map.of(),rows,1);
    }

    void guildInfo(Player p, String name) {
        Guilds.Guild guild=name==null?own(p,"info"):plugin.view.guilds().values().stream()
                .filter(row->row.name().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new Guilds.Rejected("guild.not-found"));
        show(p,"info",details(guild,p),List.of(),1);
    }

    private Map<String,Object> details(Guilds.Guild guild, Player p) {
        var view=plugin.view;
        String id=guild.id();
        var leader=view.leader(id);
        var roster=view.roster(id);
        long online=roster.stream().map(m->Bukkit.getPlayer(UUID.fromString(m.player())))
                .filter(player->player!=null && player.isOnline() && p.canSee(player)).count();
        return Map.of("guild",guild.name(),"leader",leader==null?unknown():leader.name(),
                "members",roster.size(),"online",online,"description",guild.description().isBlank()?unknown():guild.description());
    }

    void members(Player p) {
        Guilds.Guild guild=own(p,"members");
        List<Map<String,?>> rows=new ArrayList<>();
        var settings=plugin.settings;
        var me=plugin.view.members().get(GuildMaster.id(p));
        boolean canTransfer=me.role().equals("leader");
        for (var member : plugin.view.roster(guild.id()).stream()
                .sorted(Comparator.comparingInt((Guilds.Member m)->settings.role(m.role()).priority()).reversed()).toList()) {
            var role=settings.role(settings.enabled("guild_permissions")?member.role():member.role().equals("leader")?"leader":"member");
            String status=plugin.settings.str("messages",plugin.online.contains(member.player())?"value.online":"value.offline");
            rows.add(Map.of("player",member.name(),"role",role.name(),"rank_prefix",role.prefix(),"rank_color",role.color(),
                    "status",status,"uuid",member.player(),"transfer_hint",canTransfer && !member.player().equals(me.player())
                            ?settings.str("messages","value.transfer-hint"):""));
        }
        show(p,"members",Map.of("guild",guild.name()),rows,1);
    }

    void requests(Player p, String id, List<Guilds.Request> requests) {
        List<Map<String,?>> rows=new ArrayList<>();
        for (var request : requests) rows.add(Map.of("player",request.name(),"guild",request.name(),"uuid",request.player(),
                "expiry",DATE.format(Instant.ofEpochMilli(request.expires()))));
        show(p,id,Map.of(),rows,1);
    }

    void relations(Player p, String kind, String action, List<Guilds.Relation> relations) {
        String id=kind+(action.equals("info")?"-info":"");
        List<Map<String,?>> rows=new ArrayList<>();
        Guilds.Guild own=own(p,"info");
        for (var item : relations) {
            String status=item.status().equals("pending")?(item.sender().equals(own.id())?"outgoing":"incoming"):"active";
            rows.add(Map.of("guild",item.name(),"status",plugin.settings.str("messages","value."+status),
                    "date",DATE.format(Instant.ofEpochMilli(item.created())),"kills",item.kills(),"deaths",item.deaths()));
        }
        show(p,id,Map.of(),rows,1);
    }

    void history(Player p, List<Guilds.Entry> entries, int page) {
        List<Map<String,?>> rows=new ArrayList<>();
        for (var entry : entries) {
            String event=plugin.settings.get("messages").getString("history-events."+entry.event(),entry.event());
            rows.add(Map.of("event",event,"detail",entry.detail(),"date",DATE.format(Instant.ofEpochMilli(entry.time()))));
        }
        show(p,"history",Map.of(),rows,page);
    }

    void settings(Player p) {
        Guilds.Guild guild=own(p,"settings");
        show(p,"settings",Map.of("guild",guild.name(),"home",state(guild.homePublic()),"recruiting",state(guild.recruiting())),List.of(),1);
    }

    private String state(boolean on) { return plugin.settings.str("messages",on?"value.enabled":"value.disabled"); }

    void top(Player p) {
        plugin.feature("guild_statistics");
        var scores=plugin.view.ranking().top();
        if (!plugin.settings.enabled("guild_top_gui")) {
            int index=1;
            for (var score : scores) if (plugin.view.guilds().containsKey(score.id()))
                plugin.send(p,"ranking.row",score(score,index++));
            if (scores.isEmpty()) plugin.send(p,"guild.empty");
            return;
        }
        List<Map<String,?>> rows=new ArrayList<>();
        int index=1;
        for (var score : scores) if (plugin.view.guilds().containsKey(score.id()))
            rows.add(score(score,index++));
        show(p,"top",Map.of(),rows,1);
    }

    void statistics(Player p) {
        var guild=own(p,"statistics");
        var score=plugin.view.ranking().scores().get(guild.id());
        show(p,"statistics",score==null?Map.of("guild",guild.name(),"kd",unknown(),"activity",unknown(),"score",unknown(),
                "rank",unknown(),"members",plugin.view.roster(guild.id()).size()):
                score(score,plugin.view.ranking().positions().getOrDefault(guild.id(),0)),List.of(),1);
    }

    private Map<String,?> score(Rankings.Score score, int index) {
        return Map.of("guild",!plugin.view.guilds().containsKey(score.id())?score.name():plugin.view.guilds().get(score.id()).name(),
                "score",plugin.settings.enabled("guild_ranking")?GuildPlaceholders.scoreFormat(plugin.settings,score.points()):unknown(),
                "kd",String.format(Locale.ROOT,"%.2f",score.kd()),"activity",String.format(Locale.ROOT,"%.2f",score.hours()),
                "rank",index==0?unknown():index,"members",score.members());
    }

    private Guilds.Guild own(Player p, String permission) {
        var member=plugin.view.members().get(GuildMaster.id(p));
        require(member!=null,"guild.not-member");
        require(plugin.settings.allows(member.role(),permission),"error.rank");
        return plugin.view.guilds().get(member.guild());
    }

    private String unknown() { return plugin.settings.str("messages","value.unknown"); }
}
