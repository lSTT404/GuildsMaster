package world.vaelis.guildmaster;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static world.vaelis.guildmaster.Store.list;

record View(Map<String,Guilds.Guild> guilds, Map<String,Guilds.Member> members,
            Map<String,List<Guilds.Member>> byGuild, Map<String,Integer> alliances,
            Map<Chunk,String> claims, Rankings.Table ranking) {
    record Chunk(String world, int x, int z) {}
    static final View EMPTY = new View(Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Rankings.Table.EMPTY);

    static View read(Connection c, Settings settings, View previous, boolean recalculate) throws SQLException {
        Guilds data = new Guilds(c,settings);
        List<Guilds.Guild> active = data.active();
        Map<String,Guilds.Guild> guilds = new LinkedHashMap<>();
        Map<String,Guilds.Member> members = new HashMap<>();
        Map<String,List<Guilds.Member>> grouped = new HashMap<>();
        Map<Chunk,String> claims = new HashMap<>();
        Map<String,Integer> alliances = new HashMap<>();
        for (Guilds.Guild guild : active) guilds.put(guild.id(),guild);
        for (Guilds.Member member : data.allMembers()) {
            if (!guilds.containsKey(member.guild())) continue;
            members.put(member.player(),member);
            grouped.computeIfAbsent(member.guild(),ignored->new ArrayList<>()).add(member);
        }
        for (String id : list(c,"SELECT first FROM relation WHERE kind='alliance' AND status='active' UNION ALL SELECT second FROM relation WHERE kind='alliance' AND status='active'",row->row.getString(1)))
            alliances.merge(id,1,Integer::sum);
        for (Guilds.Claim claim : data.claims()) claims.put(new Chunk(claim.world(),claim.x(),claim.z()),claim.guild());
        grouped.replaceAll((key,value)->List.copyOf(value));
        Rankings.Table ranking = recalculate ? Rankings.calculate(c,settings,active,System.currentTimeMillis()) : previous.ranking;
        return new View(Collections.unmodifiableMap(guilds),Map.copyOf(members),Map.copyOf(grouped),
                Map.copyOf(alliances),Map.copyOf(claims),ranking);
    }

    List<Guilds.Member> roster(String guild) { return byGuild.getOrDefault(guild,List.of()); }
    Guilds.Member leader(String guild) { return roster(guild).stream().filter(m->m.role().equals("leader")).findFirst().orElse(null); }
}
