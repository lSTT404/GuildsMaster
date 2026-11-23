package world.vaelis.guildmaster;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.io.File;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Pattern;

final class Settings {
    static final List<String> FILES = List.of("config","messages","menus","permissions","currencies",
            "mechanics","ranking","integrations","storage");
    static final List<String> FEATURES = List.of("guilds","alliances","guild_chat","guild_home","guild_history",
            "applications","invitations","guild_glow","guild_upgrades","guild_ranking","guild_wars","guild_territories",
            "guild_announcements","guild_statistics","guild_permissions","guild_levels","guild_top_gui");
    static final MiniMessage MINI = MiniMessage.miniMessage();
    static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().hexColors()
            .useUnusualXRepeatedCharacterHexFormat().build();
    static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,40}");
    static final Pattern TOKEN = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern MONEY_TOKEN = Pattern.compile("\\{([^{}]+)}");

    record Role(String id, String name, String color, String prefix, int priority, Set<String> actions) {
        boolean allows(String action) { return actions.contains("*") || actions.contains(action); }
    }

    record Currency(String id, String name, BigDecimal amount, String type, String withdraw, String refund, String balance) {}

    final Map<String,YamlConfiguration> files;
    final Map<String,Role> roles = new LinkedHashMap<>();
    final Map<String,Currency> currencies = new LinkedHashMap<>();
    final Pattern namePattern;

    Settings(JavaPlugin plugin) throws Exception {
        this(load(plugin));
    }

    private static Map<String,YamlConfiguration> load(JavaPlugin plugin) throws Exception {
        Map<String,YamlConfiguration> loaded = new LinkedHashMap<>();
        for (String name : FILES) {
            String file = name + ".yml";
            File disk = new File(plugin.getDataFolder(), file);
            if (!disk.exists()) plugin.saveResource(file, false);
            YamlConfiguration conf = new YamlConfiguration(), defaults = new YamlConfiguration();
            try (var reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource(file)), StandardCharsets.UTF_8)) {
                defaults.load(reader);
                conf.load(disk);
            } catch (Exception error) {
                throw new IllegalArgumentException(file + ": " + error.getMessage(), error);
            }
            for (String path : defaults.getKeys(true)) {
                Object expected = defaults.get(path), actual = conf.get(path);
                boolean correct = actual == null || (expected instanceof Number ? actual instanceof Number
                        : expected instanceof List<?> ? actual instanceof List<?>
                        : expected instanceof ConfigurationSection ? actual instanceof ConfigurationSection
                        : expected.getClass().isInstance(actual));
                if (!correct) throw bad(name, path, "incorrect value type");
            }
            conf.setDefaults(defaults);
            loaded.put(name, conf);
        }
        return loaded;
    }

    Settings(Map<String,YamlConfiguration> loaded) {
        files=Map.copyOf(loaded);
        try { namePattern = Pattern.compile(str("config", "guild.name-pattern")); }
        catch (Exception error) { throw bad("config","guild.name-pattern",error.getMessage()); }
        validate();
    }

    YamlConfiguration get(String file) { return files.get(file); }
    String str(String file, String path) { return get(file).getString(path, ""); }
    int integer(String file, String path) { return get(file).getInt(path); }
    double number(String file, String path) { return get(file).getDouble(path); }
    boolean bool(String file, String path) { return get(file).getBoolean(path); }
    boolean enabled(String name) {
        if (!bool("mechanics","mechanics.guilds")) return false;
        return bool("mechanics","mechanics." + name) && (!name.equals("guild_ranking") || bool("ranking","enabled"));
    }
    Role role(String id) { return roles.getOrDefault(id, roles.get("member")); }
    boolean allows(String id, String action) {
        return id.equals("leader") || (enabled("guild_permissions") ? role(id) : role("member")).allows(action);
    }

    String format(String template, Map<String,?> values) {
        // Replace brace aliases in the template before MiniMessage parsing; all supplied values are literal.
        String input = template;
        var tags = new ArrayList<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver>();
        for (var item : values.entrySet()) {
            input = input.replace("{" + item.getKey() + "}", "<" + item.getKey() + ">");
            tags.add(Placeholder.unparsed(item.getKey(), Objects.toString(item.getValue(), "").replace("\u00A7","")));
            if (item.getKey().equals("rank_color")) {
                String name=Objects.toString(item.getValue(),"");
                var color=name.startsWith("#")?net.kyori.adventure.text.format.TextColor.fromHexString(name):
                        net.kyori.adventure.text.format.NamedTextColor.NAMES.value(name);
                if (color!=null) tags.add(Placeholder.styling("rank_style",color));
                else tags.add(Placeholder.styling("rank_style",net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
        }
        return LEGACY.serialize(MINI.deserialize(input, tags.toArray(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new)));
    }

    String message(String key, Map<String,?> values, boolean prefix) {
        String template = str("messages",key);
        if (template.isEmpty()) return "";
        return format(prefix ? str("messages","prefix") + " " + template : template, values);
    }

    private void validate() {
        range("config","guild.name-min-length",1,64);
        range("config","guild.name-max-length",integer("config","guild.name-min-length"),64);
        range("config","guild.max-members",1,500);
        range("config","guild.description-max-length",1,1024);
        for (String key : List.of("invite-expiry-minutes","application-expiry-minutes","invite-limit","application-limit",
                "player-application-limit","history-limit","history-days","alliance-limit")) range("config","guild."+key,1,525600);
        range("config","safety.confirm-seconds",3,300);
        range("config","safety.commands-per-second",1,100);
        range("config","chat.max-length",1,1024);
        for (String key : get("config").getConfigurationSection("cooldowns").getKeys(true)) range("config","cooldowns."+key,0,604800);
        range("mechanics","home.delay-seconds",0,120);
        range("mechanics","glow.duration-seconds",0,86400);
        range("mechanics","upgrades.capacity-step",1,500);
        range("mechanics","upgrades.max-capacity-bonus",0,500);
        range("mechanics","upgrades.minimum-level",1,10000);
        range("mechanics","levels.xp-per-kill",0,10000);
        range("mechanics","levels.xp-per-level",1,1000000);
        range("mechanics","levels.maximum",1,10000);
        range("mechanics","territories.limit",0,10000);
        range("mechanics","wars.limit",0,10000);
        try { if (!ChatColor.valueOf(str("mechanics","glow.color").toUpperCase(Locale.ROOT)).isColor()) throw new IllegalArgumentException(); }
        catch (Exception error) { throw bad("mechanics","glow.color","expected a Bukkit color"); }
        for (String key : List.of("activity-days","minimum-members","minimum-deaths","minimum-samples"))
            range("ranking",key,1,3650);
        for (String key : List.of("kd-days","minimum-player-kills-deaths","minimum-player-seconds","repeat-kill-seconds","afk-seconds"))
            range("ranking",key,0,31536000);
        range("ranking","places",10,200);
        range("ranking","recalculate-seconds",10,86400);
        for (String key : List.of("kd-threshold","activity-threshold","max-player-kd","max-player-hours","score-max"))
            range("ranking",key,0.00001,1000000);
        range("ranking","kd-weight",0,1000000);
        range("ranking","activity-weight",0,1000000);
        if (number("ranking","kd-weight")+number("ranking","activity-weight") <= 0)
            throw bad("ranking","kd-weight/activity-weight","at least one weight must be positive");
        try { new DecimalFormat(str("ranking","score-format")); }
        catch (IllegalArgumentException error) { throw bad("ranking","score-format",error.getMessage()); }
        for (String key : get("ranking").getStringList("tie-breaks"))
            if (!List.of("kd","activity","members","name","oldest").contains(key)) throw bad("ranking","tie-breaks",key);
        range("storage","backup-minutes",0,525600);
        range("storage","backup-keep",1,1000);

        ConfigurationSection roleSection = get("permissions").getConfigurationSection("roles");
        Set<String> roleKeys = new LinkedHashSet<>(List.of("leader","member"));
        roleKeys.addAll(roleSection.getKeys(false));
        for (String id : roleKeys) {
            String path = "roles." + id;
            if (!ID.matcher(id).matches() || !get("permissions").isConfigurationSection(path))
                throw bad("permissions",path,"invalid role");
            int priority = integer("permissions",path+".priority");
            String name = str("permissions",path+".display-name"), color = str("permissions",path+".color");
            if (name.isBlank() || (!id.equals("leader") && !id.equals("member") && (priority <= 0 || priority >= 1000)))
                throw bad("permissions",path,"custom role priority must be 1–999 and all roles need a display name");
            if (!color.isEmpty() && !color.matches("[a-z_]+|#[A-Fa-f0-9]{6}"))
                throw bad("permissions",path+".color","invalid color");
            roles.put(id, new Role(id, name, color, str("permissions",path+".prefix"), priority,
                    Set.copyOf(get("permissions").getStringList(path+".actions"))));
        }
        for (String role : get("mechanics").getStringList("glow.roles"))
            if (!roles.containsKey(role)) throw bad("mechanics","glow.roles","unknown role " + role);
        ConfigurationSection currencySection = get("currencies").getConfigurationSection("currencies");
        for (String id : currencySection.getKeys(false)) {
            String path = "currencies." + id;
            if (!ID.matcher(id).matches() || !get("currencies").isConfigurationSection(path)) throw bad("currencies",path,"invalid currency");
            if (!bool("currencies",path+".enabled")) continue;
            String label = str("currencies",path+".display-name"), type = str("currencies",path+".type");
            if (label.isBlank() || !List.of("VAULT","COMMAND").contains(type)) throw bad("currencies",path,"missing name or invalid type");
            range("currencies",path+".creation-cost",0,1_000_000_000);
            BigDecimal amount = BigDecimal.valueOf(number("currencies",path+".creation-cost")).stripTrailingZeros();
            String withdraw = str("currencies",path+".withdraw-command"), refund = str("currencies",path+".refund-command");
            if (type.equals("COMMAND")) {
                if (amount.signum() > 0 && withdraw.isBlank()) throw bad("currencies",path+".withdraw-command","required for paid COMMAND currency");
                validateCommand(path+".withdraw-command",withdraw);
                validateCommand(path+".refund-command",refund);
            }
            String balance = str("currencies",path+".balance-placeholder");
            if (!balance.isEmpty() && !balance.matches("%[A-Za-z0-9_-]+%"))
                throw bad("currencies",path+".balance-placeholder","expected one PlaceholderAPI identifier");
            if (type.equals("COMMAND") && amount.signum()>0 && balance.isEmpty() && !withdraw.contains("{transaction}"))
                throw bad("currencies",path+".withdraw-command","use {transaction} or set balance-placeholder");
            if (type.equals("COMMAND") && !refund.isEmpty() && balance.isEmpty() && !refund.contains("{transaction}"))
                throw bad("currencies",path+".refund-command","use {transaction} or set balance-placeholder");
            currencies.put(id,new Currency(id,label,amount,type,withdraw,refund,balance));
        }
        if (currencies.size() > 3) throw bad("currencies","currencies","enable at most three currencies");
        range("mechanics","glow.cost",0,1_000_000_000);
        if (number("mechanics","glow.cost") > 0) {
            Currency glow = currencies.get(str("mechanics","glow.currency"));
            if (glow == null || !glow.type().equals("VAULT")) throw bad("mechanics","glow.currency","paid glow requires an enabled Vault currency");
        }
        for (String path : List.of("commands.guild.name","commands.admin.name")) validateToken("config",path,str("config",path));
        for (String path : List.of("commands.guild.aliases","commands.admin.aliases"))
            for (String value : get("config").getStringList(path)) validateToken("config",path,value);
        for (String path : List.of("placeholders.guild-id","placeholders.top-prefix"))
            validateToken("integrations",path,str("integrations",path));
        for (String id : get("menus").getConfigurationSection("menus").getKeys(false)) {
            String p = "menus."+id;
            int size = integer("menus",p+".size");
            if (size < 9 || size > 54 || size%9 != 0) throw bad("menus",p+".size","must be 9–54 and divisible by 9");
            Set<Integer> occupied = new HashSet<>();
            ConfigurationSection items = get("menus").getConfigurationSection(p+".items");
            if (items != null) for (String item : items.getKeys(false)) {
                String key = p+".items."+item;
                int slot = integer("menus",key+".slot");
                if (slot<0 || slot>=size || !occupied.add(slot)) throw bad("menus",key+".slot","invalid or duplicate slot");
                material(key);
            }
            if (get("menus").isConfigurationSection(p+".row")) {
                material(p+".row");
                Set<Integer> rows = new HashSet<>();
                for (int slot : get("menus").getIntegerList(p+".row.slots"))
                    if (slot<0 || slot>=size || occupied.contains(slot) || !rows.add(slot))
                        throw bad("menus",p+".row.slots","invalid or overlapping row slot");
            }
        }
    }

    private void material(String key) {
        Material material = Material.matchMaterial(str("menus",key+".material"));
        if (material == null || org.bukkit.Bukkit.getServer()!=null && (material.isAir() || !material.isItem())) throw bad("menus",key+".material","invalid item material");
    }

    private static void validateToken(String file, String path, String token) {
        if (!TOKEN.matcher(token).matches()) throw bad(file,path,"invalid identifier: "+token);
    }

    private static void validateCommand(String key, String command) {
        if (command.isEmpty()) return;
        if (!command.matches("[a-zA-Z0-9_:-]+(?: [^\\p{Cntrl}]+)?"))
            throw bad("currencies",key,"use a single console command without a leading slash");
        var matcher = MONEY_TOKEN.matcher(command);
        while (matcher.find())
            if (!List.of("player","uuid","amount","guild","currency","transaction").contains(matcher.group(1)))
                throw bad("currencies",key,"unknown token: "+matcher.group());
    }

    private void range(String file, String path, double min, double max) {
        Object value = get(file).get(path);
        double number = number(file,path);
        if (!(value instanceof Number) || !Double.isFinite(number) || number < min || number > max
                || (get(file).getDefaults()!=null && get(file).getDefaults().get(path) instanceof Integer && number != Math.rint(number)))
            throw bad(file,path,"expected number in range " + min + " to " + max);
    }

    static IllegalArgumentException bad(String file, String key, String why) {
        return new IllegalArgumentException(file+".yml: "+key+" — "+why);
    }
}
