package world.vaelis.guildmaster;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import static world.vaelis.guildmaster.Store.*;

final class Guilds {
    record Person(String id, String name, String initialRole) {
        Person(String id, String name) { this(id,name,"member"); }
    }
    record Member(String player, String name, String guild, String role) {}
    record Home(String world, String uuid, double x, double y, double z, float yaw, float pitch) {}
    record Guild(String id, String name, String description, long created, Home home, boolean homePublic,
                 boolean recruiting, String announcement, int level, int experience, int capacityBonus) {}
    record Entry(String event, String detail, long time) {}
    record Request(String guild, String player, String name, long expires) {}
    record Relation(String guild, String name, String status, String sender, long created, int kills, int deaths) {}
    record Reservation(String id, Person actor, String guild, Settings.Currency currency) {}

    static final class Rejected extends RuntimeException {
        final String key;
        final Map<String,?> values;
        Rejected(String key) { this(key, Map.of()); }
        Rejected(String key, Map<String,?> values) { super(key); this.key = key; this.values = values; }
    }

    private final Connection c;
    private final Settings settings;
    private final long now;

    Guilds(Connection c, Settings settings) { this(c,settings,System.currentTimeMillis()); }
    Guilds(Connection c, Settings settings, long now) { this.c = c; this.settings = settings; this.now = now; }

    static void require(boolean condition, String key) { if (!condition) throw new Rejected(key); }
    static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    static Guild readGuild(ResultSet row) throws SQLException {
        String world = row.getString("home_world");
        Home home = world == null ? null : new Home(world,row.getString("home_uuid"),row.getDouble("home_x"),
                row.getDouble("home_y"),row.getDouble("home_z"),row.getFloat("home_yaw"),row.getFloat("home_pitch"));
        return new Guild(row.getString("id"),row.getString("name"),row.getString("description"),row.getLong("created"),
                home,row.getBoolean("home_public"),row.getBoolean("recruiting"),row.getString("announcement"),
                row.getInt("level"),row.getInt("experience"),row.getInt("capacity_bonus"));
    }

    static Member readMember(ResultSet row) throws SQLException {
        return new Member(row.getString("player"),row.getString("name"),row.getString("guild"),row.getString("role"));
    }

    List<Guild> active() throws SQLException {
        return list(c,"SELECT * FROM guild WHERE archived=0 ORDER BY name_key",Guilds::readGuild);
    }

    List<Member> allMembers() throws SQLException {
        return list(c,"SELECT * FROM member ORDER BY name COLLATE NOCASE,player",Guilds::readMember);
    }

    Guild find(String name) throws SQLException {
        Guild guild = one(c,"SELECT * FROM guild WHERE name_key=? AND archived=0",Guilds::readGuild,key(name));
        require(guild!=null,"guild.not-found");
        return guild;
    }

    Guild byId(String id) throws SQLException {
        Guild guild = one(c,"SELECT * FROM guild WHERE id=? AND archived=0",Guilds::readGuild,id);
        require(guild!=null,"guild.not-found");
        return guild;
    }

    Member member(String player) throws SQLException {
        return one(c,"SELECT * FROM member WHERE player=?",Guilds::readMember,player);
    }

    Member acting(Person actor, String permission) throws SQLException {
        Member actorMember = member(actor.id);
        require(actorMember!=null,"guild.not-member");
        require(settings.allows(actorMember.role,permission),"error.rank");
        return actorMember;
    }

    Member leader(String guild) throws SQLException {
        return one(c,"SELECT * FROM member WHERE guild=? AND role='leader'",Guilds::readMember,guild);
    }

    List<Member> members(String guild) throws SQLException {
        return list(c,"SELECT * FROM member WHERE guild=? ORDER BY name COLLATE NOCASE,player",Guilds::readMember,guild)
                .stream().sorted(Comparator.comparingInt((Member m)->settings.role(m.role).priority()).reversed()).toList();
    }

    Member target(String guild, String name) throws SQLException {
        Member result = one(c,"SELECT * FROM member WHERE guild=? AND (player=? OR name=? COLLATE NOCASE) ORDER BY joined DESC",
                Guilds::readMember,guild,name,name);
        require(result!=null,"guild.target");
        return result;
    }

    private int count(String sql, Object... args) throws SQLException { return one(c,sql,row->row.getInt(1),args); }

    static void validateName(Settings settings, String name) {
        int length = name.codePointCount(0,name.length());
        require(length>=settings.integer("config","guild.name-min-length") &&
                length<=settings.integer("config","guild.name-max-length") &&
                name.codePoints().noneMatch(cp -> Character.isISOControl(cp) || Character.isWhitespace(cp)) &&
                settings.namePattern.matcher(name).matches() && settings.get("config").getStringList("guild.banned-words")
                .stream().map(Guilds::key).filter(word -> !word.isBlank()).noneMatch(key(name)::contains),"guild.invalid-name");
    }

    private void availableName(String name, String except, String paymentId) throws SQLException {
        require(count("SELECT count(*) FROM guild WHERE name_key=? AND id<>?",key(name),except)==0,"guild.name-taken");
        require(count("SELECT count(*) FROM payment WHERE name_key=? AND id<>? AND state IN ('reserved','charging','charged','review')",
                key(name),paymentId)==0,"payment.busy");
    }

    private void noGuild(String person, String paymentId) throws SQLException {
        require(member(person)==null,"guild.already-member");
        require(count("SELECT count(*) FROM payment WHERE player=? AND id<>? AND state IN ('reserved','charging','charged','review')",
                person,paymentId)==0,"payment.busy");
    }

    Reservation reserve(Person actor, String name, Settings.Currency currency) throws SQLException {
        validateName(settings,name);
        noGuild(actor.id,"");
        availableName(name,"","");
        String id = UUID.randomUUID().toString();
        execute(c,"INSERT INTO payment(id,player,name_key,currency,amount,state,at) VALUES(?,?,?,?,?,'reserved',?)",
                id,actor.id,key(name),currency.id(),currency.amount().toPlainString(),now);
        return new Reservation(id,actor,name,currency);
    }

    void paymentState(String id, String expected, String state, String detail) throws SQLException {
        require(execute(c,"UPDATE payment SET state=?,detail=? WHERE id=? AND state=?",state,detail,id,expected)==1,"payment.busy");
    }

    void finish(Reservation order) throws SQLException {
        noGuild(order.actor.id,order.id);
        availableName(order.guild,"",order.id);
        String id = UUID.randomUUID().toString();
        execute(c,"INSERT INTO guild(id,name,name_key,created) VALUES(?,?,?,?)",id,order.guild,key(order.guild),now);
        execute(c,"INSERT INTO member VALUES(?,?,?,'leader',?)",order.actor.id,id,order.actor.name,now);
        execute(c,"DELETE FROM request WHERE player=?",order.actor.id);
        history(id,order.actor.id,"created",order.guild);
        paymentState(order.id,"charged","done","");
    }

    void rename(Person actor, String name) throws SQLException {
        Member m = acting(actor,"rename");
        validateName(settings,name);
        availableName(name,m.guild,"");
        cooldown(m.guild,"rename");
        Guild guild = byId(m.guild);
        execute(c,"UPDATE guild SET name=?,name_key=? WHERE id=?",name,key(name),m.guild);
        history(m.guild,actor.id,"renamed",guild.name+" → "+name);
    }

    void description(Person actor, String description) throws SQLException {
        Member m = acting(actor,"description");
        validateText(description);
        cooldown(m.guild,"description");
        execute(c,"UPDATE guild SET description=? WHERE id=?",description,m.guild);
        history(m.guild,actor.id,"description",description);
    }

    private void validateText(String text) {
        require(text.length()<=settings.integer("config","guild.description-max-length") &&
                text.codePoints().noneMatch(Character::isISOControl),"guild.invalid-text");
    }

    void disband(Person actor, String guild, boolean admin) throws SQLException {
        if (!admin) {
            Member m = acting(actor,"disband");
            require(m.guild.equals(guild) && m.role.equals("leader"),"error.rank");
        }
        Guild target = byId(guild);
        for (Relation ally : relations(guild,"alliance")) {
            if (ally.status.equals("active")) history(ally.guild,actor.id,"alliance-remove",target.name);
        }
        execute(c,"DELETE FROM relation WHERE first=? OR second=?",guild,guild);
        execute(c,"DELETE FROM glow WHERE player IN (SELECT player FROM member WHERE guild=?)",guild);
        execute(c,"DELETE FROM member WHERE guild=?",guild);
        execute(c,"DELETE FROM request WHERE guild=?",guild);
        execute(c,"DELETE FROM territory WHERE guild=?",guild);
        execute(c,"UPDATE guild SET archived=1,name_key='archived:'||id WHERE id=?",guild);
        history(guild,actor.id,"disbanded",target.name);
    }

    void leave(Person actor, String expectedGuild) throws SQLException {
        Member m = acting(actor,"leave");
        require(m.guild.equals(expectedGuild),"guild.changed");
        require(!m.role.equals("leader"),"guild.leader-leave");
        removeMember(actor,m,"left");
    }

    private void removeMember(Person actor, Member m, String event) throws SQLException {
        execute(c,"DELETE FROM member WHERE player=?",m.player);
        execute(c,"DELETE FROM glow WHERE player=?",m.player);
        history(m.guild,actor.id,event,m.name);
    }

    void kick(Person actor, String player) throws SQLException {
        Member m = acting(actor,"kick"), target = target(m.guild,player);
        outranks(m,target);
        removeMember(actor,target,"kicked");
    }

    private void outranks(Member actor, Member target) {
        require(!actor.player.equals(target.player) && !target.role.equals("leader") &&
                (actor.role.equals("leader") || settings.role(actor.role).priority()>settings.role(target.role).priority()),"error.rank");
    }

    void transfer(Person actor, String expectedGuild, String player) throws SQLException {
        Member m = acting(actor,"transfer");
        require(m.guild.equals(expectedGuild) && m.role.equals("leader"),"error.rank");
        Member target = target(m.guild,player);
        require(!target.player.equals(actor.id),"guild.target");
        execute(c,"UPDATE member SET role='member' WHERE player=?",actor.id);
        execute(c,"UPDATE member SET role='leader' WHERE player=?",target.player);
        history(m.guild,actor.id,"transfer",target.name);
    }

    void role(Person actor, String player, String role) throws SQLException {
        Member m = acting(actor,"role"), target = target(m.guild,player);
        outranks(m,target);
        require(settings.roles.containsKey(role) && !role.equals("leader"),"guild.unknown-role");
        require(m.role.equals("leader") || settings.role(m.role).priority()>settings.role(role).priority(),"error.rank");
        execute(c,"UPDATE member SET role=? WHERE player=?",role,target.player);
        history(m.guild,actor.id,"rank",target.name+" · "+settings.role(role).name());
    }

    Guild invite(Person actor, Person target) throws SQLException {
        Member m = acting(actor,"invite");
        noGuild(target.id,"");
        Guild guild = byId(m.guild);
        capacity(guild);
        cooldown(m.guild,"invite");
        addRequest("invite",guild,target,actor.id,settings.integer("config","guild.invite-expiry-minutes"),
                settings.integer("config","guild.invite-limit"));
        return guild;
    }

    Guild apply(Person actor, String name) throws SQLException {
        noGuild(actor.id,"");
        Guild guild = find(name);
        require(guild.recruiting,"request.closed");
        capacity(guild);
        cooldown(actor.id,"apply");
        require(count("SELECT count(*) FROM request WHERE kind='application' AND player=? AND expires>?",actor.id,now)
                <settings.integer("config","guild.player-application-limit"),"request.limit");
        addRequest("application",guild,actor,actor.id,settings.integer("config","guild.application-expiry-minutes"),
                settings.integer("config","guild.application-limit"));
        return guild;
    }

    private void addRequest(String kind, Guild guild, Person subject, String actor, int minutes, int limit) throws SQLException {
        execute(c,"DELETE FROM request WHERE kind=? AND guild=? AND expires<=?",kind,guild.id,now);
        require(count("SELECT count(*) FROM request WHERE kind=? AND guild=? AND player=?",kind,guild.id,subject.id)==0,"request.duplicate");
        require(count("SELECT count(*) FROM request WHERE kind=? AND guild=?",kind,guild.id)<limit,"request.limit");
        execute(c,"INSERT INTO request VALUES(?,?,?,?,?,?,?)",kind,guild.id,subject.id,subject.name,actor,now,now+minutes*60_000L);
    }

    List<Request> invitations(Person person) throws SQLException {
        return list(c,"SELECT r.guild,r.player,g.name,r.expires FROM request r JOIN guild g ON g.id=r.guild " +
                "WHERE r.kind='invite' AND r.player=? AND r.expires>? ORDER BY r.created",
                Guilds::request,person.id,now);
    }

    List<Request> applications(Person person) throws SQLException {
        Member m = acting(person,"applications");
        return list(c,"SELECT guild,player,name,expires FROM request WHERE kind='application' AND guild=? AND expires>? ORDER BY created",
                Guilds::request,m.guild,now);
    }

    private static Request request(ResultSet row) throws SQLException {
        return new Request(row.getString(1),row.getString(2),row.getString(3),row.getLong(4));
    }

    Guild answerInvite(Person actor, String name, boolean accept) throws SQLException {
        Guild guild = find(name);
        require(execute(c,"DELETE FROM request WHERE kind='invite' AND guild=? AND player=? AND expires>?",
                guild.id,actor.id,now)==1,"request.missing");
        if (accept) join(guild,actor);
        return guild;
    }

    Request answerApplication(Person actor, String player, boolean accept, String initialRole) throws SQLException {
        Member m = acting(actor,"applications");
        List<Request> matching = list(c,"SELECT guild,player,name,expires FROM request WHERE kind='application' AND guild=? " +
                "AND (player=? OR name=? COLLATE NOCASE) AND expires>? LIMIT 2",Guilds::request,m.guild,player,player,now);
        require(matching.size()==1,"request.missing");
        Request r = matching.getFirst();
        execute(c,"DELETE FROM request WHERE kind='application' AND guild=? AND player=?",m.guild,r.player);
        if (accept) join(byId(m.guild),new Person(r.player,r.name,initialRole));
        return r;
    }

    private void join(Guild guild, Person player) throws SQLException {
        noGuild(player.id,"");
        capacity(guild);
        execute(c,"INSERT INTO member VALUES(?,?,?,?,?)",player.id,guild.id,player.name,
                settings.roles.containsKey(player.initialRole) && !player.initialRole.equals("leader") ? player.initialRole : "member",now);
        execute(c,"DELETE FROM request WHERE player=?",player.id);
        history(guild.id,player.id,"joined",player.name);
    }

    private void capacity(Guild guild) throws SQLException {
        int bonus = settings.enabled("guild_upgrades") ? guild.capacityBonus : 0;
        require(count("SELECT count(*) FROM member WHERE guild=?",guild.id)
                <settings.integer("config","guild.max-members")+bonus,"guild.full");
    }

    List<Relation> relations(String guild, String kind) throws SQLException {
        return list(c,"SELECT g.id,g.name,r.status,r.sender,r.created, " +
                "(SELECT count(*) FROM war_kill w WHERE r.kind='war' AND w.attacker=? AND w.defender=g.id AND w.at>=r.created), " +
                "(SELECT count(*) FROM war_kill w WHERE r.kind='war' AND w.defender=? AND w.attacker=g.id AND w.at>=r.created) FROM relation r JOIN guild g " +
                "ON g.id=CASE WHEN r.first=? THEN r.second ELSE r.first END " +
                "WHERE r.kind=? AND (r.first=? OR r.second=?) AND g.archived=0 ORDER BY g.name_key",
                row -> new Relation(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getLong(5),row.getInt(6),row.getInt(7)),
                guild,guild,guild,kind,guild,guild);
    }

    record RelationChange(Guild source, Guild other, String action) {}

    RelationChange relationship(Person actor, String kind, String action, String name) throws SQLException {
        Member m = acting(actor,kind);
        Guild source = byId(m.guild), other = find(name);
        require(!source.id.equals(other.id),"relation.self");
        String first = source.id.compareTo(other.id)<0 ? source.id : other.id;
        String second = first.equals(source.id) ? other.id : source.id;
        Relation item = one(c,"SELECT status,sender,created FROM relation WHERE kind=? AND first=? AND second=?",
                row->new Relation(other.id,other.name,row.getString(1),row.getString(2),row.getLong(3),0,0),kind,first,second);
        if (action.equals("propose")) {
            require(item==null,"request.duplicate");
            cooldown(m.guild,"alliance");
            relationCapacity(kind,source,other,true);
            execute(c,"INSERT INTO relation VALUES(?,?,?,'pending',?,?)",kind,first,second,m.guild,now);
        } else {
            require(item!=null,"request.missing");
            if (action.equals("accept") || action.equals("deny")) {
                require(item.status.equals("pending") && !item.sender.equals(m.guild),"relation.incoming");
            }
            if (action.equals("accept")) {
                relationCapacity(kind,source,other,false);
                execute(c,"UPDATE relation SET status='active' WHERE kind=? AND first=? AND second=?",kind,first,second);
            } else {
                execute(c,"DELETE FROM relation WHERE kind=? AND first=? AND second=?",kind,first,second);
            }
        }
        history(source.id,actor.id,kind+"-"+action,other.name);
        history(other.id,actor.id,kind+"-"+action,source.name);
        return new RelationChange(source,other,action);
    }

    private void relationCapacity(String kind, Guild a, Guild b, boolean includePending) throws SQLException {
        int cap = kind.equals("alliance") ? settings.integer("config","guild.alliance-limit") : settings.integer("mechanics","wars.limit");
        for (Guild guild : List.of(a,b)) {
            String status = includePending ? "%" : "active";
            require(count("SELECT count(*) FROM relation WHERE kind=? AND status LIKE ? AND (first=? OR second=?)",
                    kind,status,guild.id,guild.id)<cap,"relation.limit");
        }
    }

    void setHome(Person actor, Home home) throws SQLException {
        Member m = acting(actor,home==null ? "delhome" : "sethome");
        if (home!=null) cooldown(m.guild,"sethome");
        execute(c,"UPDATE guild SET home_world=?,home_uuid=?,home_x=?,home_y=?,home_z=?,home_yaw=?,home_pitch=? WHERE id=?",
                home==null?null:home.world,home==null?null:home.uuid,home==null?null:home.x,home==null?null:home.y,
                home==null?null:home.z,home==null?null:home.yaw,home==null?null:home.pitch,m.guild);
        history(m.guild,actor.id,home==null?"home-delete":"home-set",home==null?"":home.world);
    }

    Home home(Person actor) throws SQLException {
        Member m = acting(actor,"home");
        Guild guild = byId(m.guild);
        require(guild.homePublic || m.role.equals("leader"),"error.rank");
        require(guild.home!=null,"home.missing");
        return guild.home;
    }

    Guild toggle(Person actor, String flag) throws SQLException {
        Member m = acting(actor,"settings");
        switch (flag) {
            case "home" -> execute(c,"UPDATE guild SET home_public=1-home_public WHERE id=?",m.guild);
            case "recruiting" -> execute(c,"UPDATE guild SET recruiting=1-recruiting WHERE id=?",m.guild);
            default -> throw new Rejected("error.usage");
        }
        history(m.guild,actor.id,"setting-"+flag,"");
        return byId(m.guild);
    }

    Guild announce(Person actor, String text) throws SQLException {
        Member m = acting(actor,"announce");
        validateText(text);
        execute(c,"UPDATE guild SET announcement=? WHERE id=?",text,m.guild);
        history(m.guild,actor.id,"announcement",text);
        return byId(m.guild);
    }

    int upgrade(Person actor) throws SQLException {
        Member m = acting(actor,"upgrade");
        Guild guild = byId(m.guild);
        int cap = settings.integer("mechanics","upgrades.max-capacity-bonus");
        require(guild.capacityBonus<cap,"upgrade.max");
        require(!settings.enabled("guild_levels") || guild.level>=settings.integer("mechanics","upgrades.minimum-level"),"upgrade.level");
        int after = Math.min(cap,guild.capacityBonus+settings.integer("mechanics","upgrades.capacity-step"));
        execute(c,"UPDATE guild SET capacity_bonus=? WHERE id=?",after,guild.id);
        history(guild.id,actor.id,"upgrade",Integer.toString(after));
        return after;
    }

    record Claim(String world, int x, int z, String guild) {}
    List<Claim> claims() throws SQLException {
        return list(c,"SELECT world,x,z,guild FROM territory",row->new Claim(row.getString(1),row.getInt(2),row.getInt(3),row.getString(4)));
    }

    void claim(Person actor, Claim point, boolean add) throws SQLException {
        Member m = acting(actor,"territory");
        if (add) {
            require(count("SELECT count(*) FROM territory WHERE world=? AND x=? AND z=?",point.world,point.x,point.z)==0,"territory.exists");
            require(count("SELECT count(*) FROM territory WHERE guild=?",m.guild)<settings.integer("mechanics","territories.limit"),"territory.limit");
            execute(c,"INSERT INTO territory VALUES(?,?,?,?)",point.world,point.x,point.z,m.guild);
        } else {
            require(execute(c,"DELETE FROM territory WHERE world=? AND x=? AND z=? AND guild=?",point.world,point.x,point.z,m.guild)==1,
                    "territory.missing");
        }
        history(m.guild,actor.id,add?"territory-claim":"territory-release",point.world+" "+point.x+" "+point.z);
    }

    void glow(Person actor, boolean enable, long until) throws SQLException {
        if (enable) {
            Member m = acting(actor,"light");
            List<String> roles = settings.get("mechanics").getStringList("glow.roles");
            require(roles.isEmpty() || roles.contains(m.role),"error.rank");
        }
        execute(c,"INSERT INTO glow VALUES(?,?,?) ON CONFLICT(player) DO UPDATE SET enabled=excluded.enabled,until=excluded.until",
                actor.id,enable?1:0,until);
    }

    Long glowUntil(String player) throws SQLException {
        return one(c,"SELECT until FROM glow WHERE player=? AND enabled=1 AND (until=0 OR until>?)",row->row.getLong(1),player,now);
    }

    List<Entry> history(Person actor) throws SQLException {
        Member m = acting(actor,"history-view-own");
        return list(c,"SELECT event,detail,at FROM history WHERE guild=? AND at>=? ORDER BY at DESC,id DESC LIMIT ?",
                row->new Entry(row.getString(1),row.getString(2),row.getLong(3)),m.guild,historyCutoff(),
                settings.integer("config","guild.history-limit"));
    }

    private void history(String guild, String actor, String event, String detail) throws SQLException {
        if (settings.enabled("guild_history")) execute(c,"INSERT INTO history(guild,actor,event,detail,at) VALUES(?,?,?,?,?)",guild,actor,event,detail,now);
    }

    void prune() throws SQLException {
        if (settings.enabled("guild_history")) {
            execute(c,"DELETE FROM history WHERE at<?",historyCutoff());
            execute(c,"DELETE FROM history WHERE id IN (SELECT id FROM (SELECT id,row_number() OVER(PARTITION BY guild ORDER BY at DESC,id DESC) AS n FROM history) WHERE n>?)",
                    settings.integer("config","guild.history-limit"));
        }
        if (settings.enabled("invitations")) execute(c,"DELETE FROM request WHERE kind='invite' AND expires<=?",now);
        if (settings.enabled("applications")) execute(c,"DELETE FROM request WHERE kind='application' AND expires<=?",now);
    }

    private long historyCutoff() { return now-settings.integer("config","guild.history-days")*86_400_000L; }

    private void cooldown(String scope, String action) throws SQLException {
        Long last = one(c,"SELECT at FROM cooldown WHERE scope=? AND action=?",row->row.getLong(1),scope,action);
        long duration = settings.integer("config","cooldowns."+action)*1000L;
        if (last!=null && last+duration>now)
            throw new Rejected("error.cooldown",Map.of("seconds",(last+duration-now+999)/1000));
        execute(c,"INSERT INTO cooldown VALUES(?,?,?) ON CONFLICT(scope,action) DO UPDATE SET at=excluded.at",scope,action,now);
    }
}
