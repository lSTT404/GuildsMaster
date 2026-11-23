package world.vaelis.guildmaster;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static world.vaelis.guildmaster.Store.*;

final class Rankings {
    record MemberStats(long kills, long deaths, long seconds) {}
    record Score(String id, String name, long created, int members, double kd, double hours, double points) {}
    record Table(List<Score> top, Map<String,Score> scores, Map<String,Integer> positions) {
        static final Table EMPTY = new Table(List.of(),Map.of(),Map.of());
    }

    static double points(double kd, double hours, double kdThreshold, double activityThreshold,
                         double kdWeight, double activityWeight, double maximum) {
        return maximum * ((kdWeight*Math.clamp(kd/kdThreshold,0,1)+activityWeight*Math.clamp(hours/activityThreshold,0,1))
                /(kdWeight+activityWeight));
    }

    record Activity(String player, long start, long end) {}

    static void activity(Connection c, List<Activity> intervals) throws SQLException {
        for (Activity item : intervals) {
            if (item.start>=item.end) continue;
            if (execute(c,"UPDATE activity_span SET end=? WHERE player=? AND end=?",item.end,item.player,item.start)==0)
                execute(c,"INSERT INTO activity_span VALUES(?,?,?) ON CONFLICT(player,start) DO UPDATE SET end=max(end,excluded.end)",
                        item.player,item.start,item.end);
        }
    }

    static boolean kill(Connection c, Settings settings, String killer, String victim, long now) throws SQLException {
        if (killer.equals(victim) || one(c,"SELECT 1 FROM pvp WHERE killer=? AND victim=? AND at>? LIMIT 1",
                row->row.getInt(1),killer,victim,now-settings.integer("ranking","repeat-kill-seconds")*1000L)!=null) return false;
        execute(c,"INSERT INTO pvp(killer,victim,at,tracked) VALUES(?,?,?,?)",killer,victim,now,settings.enabled("guild_statistics"));
        if (settings.enabled("guild_levels")) execute(c,"""
                UPDATE guild SET
                  experience=experience+?,
                  level=min(?, 1+(experience+?)/?)
                WHERE id=(SELECT guild FROM member WHERE player=?)
                """,settings.integer("mechanics","levels.xp-per-kill"),settings.integer("mechanics","levels.maximum"),
                settings.integer("mechanics","levels.xp-per-kill"),settings.integer("mechanics","levels.xp-per-level"),killer);
        return true;
    }

    private record Counting(String guild, long kills, long deaths, long seconds) {}

    static Table calculate(Connection c, Settings settings, List<Guilds.Guild> guilds, long now) throws SQLException {
        if (!settings.enabled("guild_statistics")) return Table.EMPTY;
        int kdDays = settings.integer("ranking","kd-days");
        long kdSince = kdDays==0 ? 0 : now-kdDays*86400000L;
        long period = now/1000-settings.integer("ranking","activity-days")*86400L;
        var all = list(c,"""
                WITH kills AS (SELECT killer,count(*) n FROM pvp WHERE tracked=1 AND at>=? GROUP BY killer),
                     deaths AS (SELECT victim,count(*) n FROM pvp WHERE tracked=1 AND at>=? GROUP BY victim),
                     intervals AS (
                        SELECT player,min(end,?)-max(start,?) seconds FROM activity_span WHERE end>? AND start<?
                        UNION ALL SELECT player,seconds FROM activity WHERE day>=?
                     ),
                     uptime AS (SELECT player,sum(seconds) n FROM intervals GROUP BY player)
                SELECT m.guild,coalesce(k.n,0),coalesce(d.n,0),coalesce(u.n,0) FROM member m
                LEFT JOIN kills k ON m.player=k.killer
                LEFT JOIN deaths d ON m.player=d.victim
                LEFT JOIN uptime u ON m.player=u.player
                """,row->new Counting(row.getString(1),row.getLong(2),row.getLong(3),row.getLong(4)),kdSince,kdSince,now/1000,period,period,now/1000,Math.floorDiv(period,86400));
        Map<String,List<Counting>> groups = new HashMap<>();
        for (Counting sample : all) groups.computeIfAbsent(sample.guild, ignored->new ArrayList<>()).add(sample);

        List<Score> ordered = new ArrayList<>();
        Map<String,Score> scores = new HashMap<>();
        for (Guilds.Guild guild : guilds) {
            List<Counting> group = groups.getOrDefault(guild.id(),List.of());
            double kd = 0, hours = 0;
            int samples = 0;
            for (Counting sample : group) {
                if (sample.kills+sample.deaths<settings.integer("ranking","minimum-player-kills-deaths") ||
                        sample.seconds<settings.integer("ranking","minimum-player-seconds")) continue;
                kd += Math.min(settings.number("ranking","max-player-kd"),
                        (double)sample.kills/Math.max(settings.integer("ranking","minimum-deaths"),sample.deaths));
                hours += Math.min(settings.number("ranking","max-player-hours"),sample.seconds/3600.0);
                samples++;
            }
            kd /= Math.max(1,samples);
            hours /= Math.max(1,samples);
            Score score = new Score(guild.id(),guild.name(),guild.created(),group.size(),kd,hours,
                    points(kd,hours,settings.number("ranking","kd-threshold"),settings.number("ranking","activity-threshold"),
                            settings.number("ranking","kd-weight"),settings.number("ranking","activity-weight"),
                            settings.number("ranking","score-max")));
            scores.put(guild.id(),score);
            if (settings.enabled("guild_ranking") && group.size()>=settings.integer("ranking","minimum-members") &&
                    samples>=settings.integer("ranking","minimum-samples")) ordered.add(score);
        }
        Comparator<Score> sorter = Comparator.comparingDouble(Score::points).reversed();
        for (String tie : settings.get("ranking").getStringList("tie-breaks")) {
            sorter = sorter.thenComparing(switch (tie) {
                case "kd" -> Comparator.comparingDouble(Score::kd).reversed();
                case "activity" -> Comparator.comparingDouble(Score::hours).reversed();
                case "members" -> Comparator.comparingInt(Score::members).reversed();
                case "oldest" -> Comparator.comparingLong(Score::created);
                default -> Comparator.comparing(s->Guilds.key(s.name));
            });
        }
        ordered.sort(sorter.thenComparing(Score::id));
        Map<String,Integer> positions = new HashMap<>();
        for (int index=0; index<ordered.size(); index++) positions.put(ordered.get(index).id,index+1);
        return new Table(ordered.stream().limit(settings.integer("ranking","places")).toList(),Map.copyOf(scores),Map.copyOf(positions));
    }
}
