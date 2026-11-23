package world.vaelis.guildmaster;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static world.vaelis.guildmaster.Store.*;

final class GuildsTest {
    @TempDir Path directory;
    private Store store;
    private Connection c;
    private Settings settings;

    private final Guilds.Person ada=person("Ada");
    private final Guilds.Person sam=person("Sam");
    private final Guilds.Person eva=person("Eva");
    private final Guilds.Person mike=person("Mike");

    @BeforeEach void open() throws Exception {
        store=new Store(directory.resolve("guildmaster.db"),directory.resolve("backups"));
        c=connect(store.database());
        store.initialize(c);
        settings=settings(Map.of());
    }

    @AfterEach void close() throws Exception {
        c.close();
        store.close();
    }

    static Settings settings(Map<String,Map<String,Object>> edits) {
        Map<String,YamlConfiguration> configs=new LinkedHashMap<>();
        for (String name : Settings.FILES) {
            var reader=new InputStreamReader(Objects.requireNonNull(GuildsTest.class.getResourceAsStream("/"+name+".yml")),
                    StandardCharsets.UTF_8);
            var config=YamlConfiguration.loadConfiguration(reader);
            edits.getOrDefault(name,Map.of()).forEach(config::set);
            configs.put(name,config);
        }
        return new Settings(configs);
    }

    private static Guilds.Person person(String name) { return new Guilds.Person(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString(),name); }
    private Guilds guilds() { return new Guilds(c,settings); }
    private <T> T write(Store.Work<T> task) throws Exception { return transaction(c,task); }

    private Guilds.Reservation reserve(Guilds.Person actor, String name, String currency) throws Exception {
        return write(db->new Guilds(db,settings).reserve(actor,name,settings.currencies.get(currency)));
    }

    private Guilds.Guild create(Guilds.Person actor, String name) throws Exception {
        Guilds.Reservation order=reserve(actor,name,"free");
        finalizePayment(order);
        return guilds().find(name);
    }

    private void finalizePayment(Guilds.Reservation order) throws Exception {
        guilds().paymentState(order.id(),"reserved","charging","");
        guilds().paymentState(order.id(),"charging","charged","");
        write(db->{new Guilds(db,settings).finish(order);return null;});
    }

    @Test void chargesAreReservedAndCreationUsesOneReceipt() throws Exception {
        var order=reserve(ada,"Aurora","free");
        assertThrows(Guilds.Rejected.class,()->reserve(ada,"Other","free"));
        assertThrows(Guilds.Rejected.class,()->reserve(sam,"aUrOrA","free"));
        finalizePayment(order);
        assertEquals("leader",guilds().member(ada.id()).role());
        assertThrows(Guilds.Rejected.class,()->finalizePayment(order));
        assertEquals("done",one(c,"SELECT state FROM payment WHERE id=?",r->r.getString(1),order.id()));
    }

    @Test void failedAndRefundedPaymentsDoNotCreateGuildsAndAllowRetry() throws Exception {
        var failed=reserve(ada,"Aurora","free");
        guilds().paymentState(failed.id(),"reserved","charging","");
        guilds().paymentState(failed.id(),"charging","failed","provider declined");
        assertThrows(Guilds.Rejected.class,()->guilds().find("Aurora"));
        var charged=reserve(ada,"Aurora","free");
        guilds().paymentState(charged.id(),"reserved","charging","");
        guilds().paymentState(charged.id(),"charging","charged","");
        execute(c,"CREATE TRIGGER fail_guild BEFORE INSERT ON guild BEGIN SELECT RAISE(ABORT,'transaction failure'); END");
        assertThrows(java.sql.SQLException.class,()->write(db->{new Guilds(db,settings).finish(charged);return null;}));
        assertNull(guilds().member(ada.id()));
        guilds().paymentState(charged.id(),"charged","refunded","provider refunded");
        execute(c,"DROP TRIGGER fail_guild");
        create(ada,"Aurora");
    }

    @Test void selectsThreeConfiguredCurrencyPrices() throws Exception {
        settings=settings(Map.of("currencies",Map.of("currencies.money.enabled",true,"currencies.tokens.enabled",true)));
        assertEquals(List.of("free","money","tokens"),List.copyOf(settings.currencies.keySet()));
        for (var entry : Map.of("free",ada,"money",sam,"tokens",eva).entrySet()) {
            var label=entry.getValue().name()+"Guild";
            Guilds.Reservation order=reserve(entry.getValue(),label,entry.getKey());
            assertEquals(settings.currencies.get(entry.getKey()).amount(),order.currency().amount());
            finalizePayment(order);
        }
        assertEquals(3,guilds().active().size());
        assertEquals(0,new BigDecimal(Store.<String>one(c,"SELECT amount FROM payment WHERE currency='money'",r->r.getString(1))).compareTo(new BigDecimal("10000")));
    }

    @Test void invitationsApplicationsCustomRanksAndTransfer() throws Exception {
        settings=settings(Map.of("permissions",Map.of(
                "roles.builder.priority",10,"roles.builder.display-name","Builder",
                "roles.builder.color","yellow","roles.builder.prefix","Build · ","roles.builder.actions",List.of("sethome"))));
        var guild=create(ada,"Aurora");
        write(db->guilds().invite(ada,sam));
        write(db->guilds().answerInvite(sam,"Aurora",true));
        assertEquals("member",guilds().member(sam.id()).role());
        assertThrows(Guilds.Rejected.class,()->write(db->{guilds().setHome(sam,new Guilds.Home("world",null,1,60,1,0,0));return null;}));
        write(db->{guilds().role(ada,"Sam","builder");return null;});
        var home=new Guilds.Home("world",null,1,60,1,0,0);
        write(db->{guilds().setHome(sam,home);return null;});
        assertEquals(home,guilds().home(ada));
        write(db->guilds().apply(eva,"Aurora"));
        assertEquals(1,guilds().applications(ada).size());
        write(db->guilds().answerApplication(ada,eva.id(),true,"member"));
        assertNotNull(guilds().member(eva.id()));
        write(db->{guilds().transfer(ada,guild.id(),sam.id());return null;});
        assertEquals("leader",guilds().member(sam.id()).role());
        assertEquals("member",guilds().member(ada.id()).role());
        assertThrows(Guilds.Rejected.class,()->write(db->{guilds().leave(sam,guild.id());return null;}));
    }

    @Test void alliancesRequireBothGuildsAndReportBothHistories() throws Exception {
        settings=settings(Map.of("config",Map.of("cooldowns.alliance",0)));
        var aurora=create(ada,"Aurora");
        var copper=create(sam,"Copper");
        assertThrows(Guilds.Rejected.class,()->write(db->guilds().relationship(ada,"alliance","propose","Aurora")));
        write(db->guilds().relationship(ada,"alliance","propose","Copper"));
        assertThrows(Guilds.Rejected.class,()->write(db->guilds().relationship(ada,"alliance","accept","Copper")));
        write(db->guilds().relationship(sam,"alliance","accept","Aurora"));
        assertEquals("active",guilds().relations(aurora.id(),"alliance").getFirst().status());
        assertEquals("active",guilds().relations(copper.id(),"alliance").getFirst().status());
        assertTrue(guilds().history(ada).stream().anyMatch(e->e.event().equals("alliance-accept")));
        assertTrue(guilds().history(sam).stream().anyMatch(e->e.event().equals("alliance-accept")));
        write(db->guilds().relationship(sam,"alliance","remove","Aurora"));
        assertTrue(guilds().relations(aurora.id(),"alliance").isEmpty());
    }

    @Test void rankingHandlesCapsRollingPeriodsAndRepeatedKills() throws Exception {
        create(ada,"Aurora");
        create(sam,"Copper");
        long now=1_900_000_000_000L;
        assertFalse(Rankings.kill(c,settings,ada.id(),ada.id(),now));
        assertTrue(Rankings.kill(c,settings,ada.id(),mike.id(),now));
        assertFalse(Rankings.kill(c,settings,ada.id(),mike.id(),now+5));
        long end=now/1000, period=end-7*86400;
        Rankings.activity(c,List.of(new Rankings.Activity(ada.id(),period-1000,period+1000),
                new Rankings.Activity(ada.id(),period+1000,period+3600)));
        assertEquals(1,Store.<Integer>one(c,"SELECT COUNT(*) FROM activity_span",r->r.getInt(1)));
        var table=Rankings.calculate(c,settings,guilds().active(),now);
        var winner=table.top().getFirst();
        assertEquals("Aurora",winner.name());
        assertEquals(1.0,winner.kd());
        assertEquals(1.0,winner.hours());
        assertEquals(120+400.0/28,winner.points(),1e-8);
        assertEquals(1000,Rankings.points(50,600,5,28,.6,.4,1000),1e-8);
    }

    @Test void featuresToggleWithoutRemovingExistingGuildData() throws Exception {
        var guild=create(ada,"Aurora");
        assertEquals(Set.of("leader","member"),settings.roles.keySet());
        for (String feature : Settings.FEATURES) {
            var config=settings(Map.of("mechanics",Map.of("mechanics."+feature,false)));
            assertFalse(config.enabled(feature),feature);
            for (String other : Settings.FEATURES)
                assertEquals(!feature.equals("guilds") && !other.equals(feature),config.enabled(other),other);
            assertNotNull(new Guilds(c,config).byId(guild.id()));
        }
    }

    @Test void backupCanRestoreOnRestart() throws Exception {
        create(ada,"Aurora");
        var file=store.backup(c,"manual",2);
        create(sam,"Copper");
        Restore.stage(store,file.getFileName().toString());
        c.close();
        c=connect(store.database());
        Restore.applyPending(store,c);
        assertEquals(List.of("Aurora"),guilds().active().stream().map(Guilds.Guild::name).toList());
        assertFalse(java.nio.file.Files.exists(store.backupPath("restore-pending.db")));
    }

    @Test void formatKeepsPlayerContentLiteral() {
        String text=settings.format("<red>{name}",Map.of("name","<bold>text"));
        assertTrue(text.contains("<bold>text"));
        assertFalse(settings.message("glow.enabled",Map.of(),false).isEmpty());
        assertFalse(settings.message("glow.disabled",Map.of(),false).isEmpty());
        assertFalse(settings.format("{name}",Map.of("name","\u00A7ktext")).contains("\u00A7"));
    }
}
