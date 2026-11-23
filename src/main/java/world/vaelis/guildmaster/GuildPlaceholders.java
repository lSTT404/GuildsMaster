package world.vaelis.guildmaster;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;

final class GuildPlaceholders {
    private static final Set<String> KNOWN_GUILD = Set.of("name","leader","members","online","kd","activity","score",
            "position","description","role","alliances","home","level");
    private static final Set<String> KNOWN_TOP = Set.of("guilds","guilds_score","guilds_members","guilds_kd","guilds_activity");

    static Runnable registerFor(GuildMaster plugin) {
        List<Expansion> expansions=new ArrayList<>();
        try {
            String guild=plugin.settings.str("integrations","placeholders.guild-id");
            String top=plugin.settings.str("integrations","placeholders.top-prefix");
            add(plugin,expansions,guild,0);
            for (int place=1;place<=plugin.settings.integer("ranking","places");place++) add(plugin,expansions,top+place,place);
            if (!guild.equals("vaeliskrl")) add(plugin,expansions,"vaeliskrl",-1);
        } catch (RuntimeException | LinkageError error) {
            expansions.forEach(Expansion::unregister);
            throw error;
        }
        return ()->expansions.forEach(Expansion::unregister);
    }

    private static void add(GuildMaster plugin, List<Expansion> registered, String id, int place) {
        Expansion expansion=new Expansion(plugin,id,place);
        if (expansion.register()) registered.add(expansion);
        else if (place<=10) plugin.getLogger().warning("PlaceholderAPI expansion already registered: "+id);
    }

    static java.math.BigDecimal balance(OfflinePlayer player, String placeholder) {
        String rendered=me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,placeholder).strip();
        return new java.math.BigDecimal(rendered);
    }

    static String scoreFormat(Settings config, double points) {
        return new DecimalFormat(config.str("ranking","score-format"),DecimalFormatSymbols.getInstance(Locale.ROOT)).format(points);
    }

    private static String decimal(double value) { return String.format(Locale.ROOT,"%.2f",value); }

    private static final class Expansion extends PlaceholderExpansion {
        private final GuildMaster plugin;
        private final String id;
        private final int place;

        Expansion(GuildMaster plugin, String id, int place) {
            this.plugin=plugin;this.id=id;this.place=place;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getAuthor() { return "VaelisWorld"; }
        @Override public String getVersion() { return plugin.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }

        @Override public String onRequest(OfflinePlayer player, String parameter) {
            Settings s=plugin.settings;
            String key=parameter.toLowerCase(Locale.ROOT);
            if (place==-1) key=key.equals("guild")?"name":key.startsWith("guild_")?key.substring(6):key;
            if (Settings.TOKEN.matcher(key).matches()) key=s.get("integrations").getString("placeholders.aliases."+key,key);
            return place>0?top(s,key):guild(s,player,key);
        }

        private String top(Settings s, String key) {
            if (!KNOWN_TOP.contains(key)) return null;
            if (!s.enabled("guild_ranking") || !s.enabled("guild_statistics")) return disabled(s);
            View view=plugin.view;
            List<Rankings.Score> ranks=view.ranking().top();
            if (ranks.size()<place || s.integer("ranking","places")<place) return missing(s);
            Rankings.Score score=ranks.get(place-1);
            Guilds.Guild g=view.guilds().get(score.id());
            if (g==null) return missing(s);
            return switch (key) {
                case "guilds" -> g.name();
                case "guilds_score" -> scoreFormat(s,score.points());
                case "guilds_members" -> Integer.toString(view.roster(g.id()).size());
                case "guilds_kd" -> decimal(score.kd());
                default -> decimal(score.hours());
            };
        }

        private String guild(Settings s, OfflinePlayer player, String key) {
            if (!KNOWN_GUILD.contains(key)) return null;
            if (!s.enabled("guilds") || switch (key) {
                case "home" -> !s.enabled("guild_home");
                case "alliances" -> !s.enabled("alliances");
                case "kd","activity" -> !s.enabled("guild_statistics");
                case "score","position" -> !s.enabled("guild_statistics") || !s.enabled("guild_ranking");
                case "role" -> !s.enabled("guild_permissions");
                case "level" -> !s.enabled("guild_levels");
                default -> false;
            }) return disabled(s);
            if (player==null) return missing(s);
            View view=plugin.view;
            Guilds.Member member=view.members().get(player.getUniqueId().toString());
            if (member==null) return missing(s);
            Guilds.Guild g=view.guilds().get(member.guild());
            if (g==null) return missing(s);
            Guilds.Member leader=view.leader(g.id());
            Rankings.Score score=view.ranking().scores().get(g.id());
            return switch (key) {
                case "name" -> g.name();
                case "leader" -> leader==null?missing(s):leader.name();
                case "members" -> Integer.toString(view.roster(g.id()).size());
                case "online" -> Long.toString(view.roster(g.id()).stream().filter(m->plugin.online.contains(m.player())).count());
                case "description" -> g.description().isBlank()?missing(s):g.description();
                case "role" -> {
                    var role=s.role(member.role());
                    yield role.prefix()+role.name();
                }
                case "alliances" -> view.alliances().getOrDefault(g.id(),0).toString();
                case "home" -> g.home()==null?missing(s):g.home().world();
                case "kd" -> score==null?missing(s):decimal(score.kd());
                case "activity" -> score==null?missing(s):decimal(score.hours());
                case "score" -> score==null?missing(s):scoreFormat(s,score.points());
                case "position" -> Objects.toString(view.ranking().positions().get(g.id()),missing(s));
                case "level" -> Integer.toString(g.level());
                default -> null;
            };
        }

        private String missing(Settings s) { return s.str("integrations","placeholders.unavailable"); }
        private String disabled(Settings s) { return s.str("integrations","placeholders.disabled"); }
    }
}
