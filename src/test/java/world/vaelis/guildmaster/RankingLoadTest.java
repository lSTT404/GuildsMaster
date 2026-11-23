package world.vaelis.guildmaster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static world.vaelis.guildmaster.Store.*;

final class RankingLoadTest {
    private static final int GUILDS=250, ROSTER=15, PLAYER_COUNT=GUILDS*ROSTER;
    private static final int KILLS=50_000, ACTIVITY_ROWS=100_000;
    private static final long NOW=1_900_000_000_000L;
    @TempDir Path dir;

    @Test void calculateAndPublishRankingFromRepresentativeData() throws Exception {
        Settings settings=GuildsTest.settings(Map.of());
        try (Store store=new Store(dir.resolve("load.db"),dir.resolve("backups"));
             Connection db=connect(store.database())) {
            store.initialize(db);
            transaction(db,connection->{seed(connection);return null;});
            long start=System.nanoTime();
            var guilds=new Guilds(db,settings).active();
            var ranking=Rankings.calculate(db,settings,guilds,NOW);
            double elapsed=(System.nanoTime()-start)/1_000_000.0;
            assertEquals(GUILDS,ranking.scores().size());
            assertEquals(GUILDS,ranking.positions().size());
            assertEquals(10,ranking.top().size());
            assertEquals(ROSTER,ranking.top().getFirst().members());
            System.out.printf(java.util.Locale.ROOT,"Ranking load %,d members / %,d kills / %,d activity rows: %.1f ms%n",
                    PLAYER_COUNT,KILLS,ACTIVITY_ROWS,elapsed);
        }
    }

    private void seed(Connection c) throws Exception {
        try (var guild=c.prepareStatement("INSERT INTO guild(id,name,name_key,created) VALUES(?,?,?,?)");
             var member=c.prepareStatement("INSERT INTO member VALUES(?,?,?,?,?)");
             var pvp=c.prepareStatement("INSERT INTO pvp(killer,victim,at) VALUES(?,?,?)");
             var activity=c.prepareStatement("INSERT INTO activity_span VALUES(?,?,?)")) {
            for (int index=0;index<GUILDS;index++) {
                String id=id(index);
                guild.setString(1,id);
                guild.setString(2,"Guild"+index);
                guild.setString(3,"guild"+index);
                guild.setLong(4,NOW-86_400_000L);
                guild.addBatch();
            }
            guild.executeBatch();
            for (int index=0;index<PLAYER_COUNT;index++) {
                member.setString(1,id(index+GUILDS));
                member.setString(2,id(index/ROSTER));
                member.setString(3,"Player"+index);
                member.setString(4,index%ROSTER==0?"leader":"member");
                member.setLong(5,NOW-86_400_000L);
                member.addBatch();
            }
            member.executeBatch();
            for (int index=0;index<KILLS;index++) {
                pvp.setString(1,id(index%PLAYER_COUNT+GUILDS));
                pvp.setString(2,id((index+ROSTER)%PLAYER_COUNT+GUILDS));
                pvp.setLong(3,NOW-index*5_000L);
                pvp.addBatch();
            }
            pvp.executeBatch();
            for (int index=0;index<ACTIVITY_ROWS;index++) {
                long start=NOW/1000-(index/PLAYER_COUNT+1)*1800;
                activity.setString(1,id(index%PLAYER_COUNT+GUILDS));
                activity.setLong(2,start);
                activity.setLong(3,start+600);
                activity.addBatch();
            }
            activity.executeBatch();
        }
    }

    private static String id(int index) { return new UUID(0,index).toString(); }
}
