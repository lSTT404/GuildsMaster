package world.vaelis.guildmaster;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static world.vaelis.guildmaster.Store.*;
import static world.vaelis.guildmaster.Guilds.require;

final class GuildCommands implements TabExecutor {
    static final List<String> USER = List.of("help","create","delete","disband","info","list","description","rename","leave",
            "transfer","invite","invitations","accept","deny","kick","members","apply","applications","application","alliance",
            "sethome","home","delhome","chat","history","light","top","settings","role","statistics","announce","upgrade",
            "level","war","territory","set","confirm");
    static final List<String> ADMIN = List.of("help","reload","info","delete","migrate","debug","backup","restore","payment");
    static final Map<String,String> FEATURE = Map.ofEntries(
            Map.entry("apply","applications"),Map.entry("application","applications"),Map.entry("applications","applications"),
            Map.entry("invite","invitations"),Map.entry("invitations","invitations"),Map.entry("accept","invitations"),
            Map.entry("deny","invitations"),Map.entry("alliance","alliances"),Map.entry("sethome","guild_home"),
            Map.entry("home","guild_home"),Map.entry("delhome","guild_home"),Map.entry("chat","guild_chat"),
            Map.entry("history","guild_history"),Map.entry("light","guild_glow"),Map.entry("top","guild_ranking"),
            Map.entry("role","guild_permissions"),Map.entry("statistics","guild_statistics"),Map.entry("announce","guild_announcements"),
            Map.entry("upgrade","guild_upgrades"),Map.entry("level","guild_levels"),Map.entry("war","guild_wars"),
            Map.entry("territory","guild_territories"),Map.entry("set","guild_territories"));
    private final GuildMaster plugin;
    private final Map<CommandSender,Confirmation> confirmations = new WeakHashMap<>();
    private final Map<UUID,Limit> limits = new HashMap<>();
    private CommandMap commandMap;
    private record Confirmation(String key, String token, long expires, Runnable effect) {}
    private record Limit(long second, int count) {}
    private interface Operation<T> { T run(Guilds guilds) throws Exception; }

    GuildCommands(GuildMaster plugin) { this.plugin=plugin; }

    void register() throws ReflectiveOperationException {
        validateAliases(plugin.settings);
        for (String key : List.of("guild","guildadmin")) {
            PluginCommand command=Objects.requireNonNull(plugin.getCommand(key));
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        commandMap=(CommandMap)Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
        registerRoots(false);
        registerRoots(true);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            long now=System.currentTimeMillis();
            limits.values().removeIf(limit->limit.second<now/1000-30);
            confirmations.values().removeIf(confirmation->confirmation.expires<now);
        },600,600);
    }

    private void registerRoots(boolean admin) {
        Settings config=plugin.settings;
        String section="commands."+(admin?"admin":"guild");
        Set<String> names=new LinkedHashSet<>();
        names.add(config.str("config",section+".name"));
        names.addAll(config.get("config").getStringList(section+".aliases"));
        for (String name : names) {
            Command existing=commandMap.getCommand(name);
            if (existing instanceof PluginCommand ours && ours.getPlugin()==plugin) continue;
            if (existing!=null) {
                plugin.getLogger().warning("Configured command already in use: /"+name);
                continue;
            }
            commandMap.register("guildmaster",new Command(name) {
                @Override public boolean execute(CommandSender sender, String label, String[] args) {
                    dispatch(sender,args,admin,false);
                    return true;
                }
                @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                    return complete(sender,args,admin);
                }
            });
        }
    }

    void validateAliases(Settings config) {
        Set<String> roots=new HashSet<>();
        for (String type : List.of("guild","admin")) {
            var names=new ArrayList<>(config.get("config").getStringList("commands."+type+".aliases"));
            names.add(config.str("config","commands."+type+".name"));
            for (String name : names)
                if (!roots.add(name) || (type.equals("guild")?Set.of("guildadmin","ga"):Set.of("guild","g")).contains(name))
                    throw Settings.bad("config","commands."+type,"conflicting root command "+name);
        }
        aliases(config,false);
        aliases(config,true);
    }

    private Map<String,String> aliases(Settings config, boolean admin) {
        List<String> names=admin?ADMIN:USER;
        Map<String,String> out=new LinkedHashMap<>();
        names.forEach(name->out.put(name,name));
        String path="commands."+(admin?"admin":"guild")+"-actions";
        var section=config.get("config").getConfigurationSection(path);
        if (section!=null) for (String name : section.getKeys(false)) {
            if (!names.contains(name) || !section.isList(name))
                throw Settings.bad("config",path+"."+name,"expected action and alias list");
            for (String alias : section.getStringList(name))
                if (!Settings.TOKEN.matcher(alias).matches() || out.putIfAbsent(alias,name)!=null)
                    throw Settings.bad("config",path+"."+name,"invalid or conflicting alias "+alias);
        }
        return out;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        dispatch(sender,args,command.getName().equals("guildadmin"),false);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return complete(sender,args,command.getName().equals("guildadmin"));
    }

    void dispatch(CommandSender sender, String[] args, boolean admin, boolean canonical) {
        if (!plugin.ready) { plugin.send(sender,"error.starting");return; }
        try {
            String input=args.length==0?(admin?"help":""):args[0].toLowerCase(Locale.ROOT);
            String action=input.isEmpty()?"":canonical?input:aliases(plugin.settings,admin).get(input);
            require(action!=null,"error.usage");
            if (admin) {
                require(allowedAdmin(sender,action),"error.permission");
                admin(sender,action,args);
            } else {
                require(sender instanceof Player,"error.player");
                Player player=(Player)sender;
                require(player.hasPermission("guildmaster.use"),"error.permission");
                guard(action);
                throttle(player);
                player(player,action,args);
            }
        } catch (Guilds.Rejected error) {
            plugin.explain(sender,error);
        } catch (IllegalArgumentException error) {
            plugin.send(sender,"error.usage");
            plugin.getLogger().fine(error.toString());
        }
    }

    private void throttle(Player player) {
        UUID id=player.getUniqueId();
        long second=System.currentTimeMillis()/1000;
        Limit previous=limits.get(id);
        int next=previous!=null && previous.second==second ? previous.count+1 : 1;
        require(next<=plugin.settings.integer("config","safety.commands-per-second"),"error.rate");
        limits.put(id,new Limit(second,next));
    }

    private void player(Player p, String action, String[] args) {
        Guilds.Person who=plugin.person(p);
        switch (action) {
            case "" -> plugin.menus.show(p,"main",Map.of(),List.of(),1);
            case "help" -> plugin.send(p,"help.player");
            case "create" -> {
                if (args.length==1) plugin.menus.create(p);
                else if (args.length==2) plugin.menus.currency(p,args[1]);
                else {
                    require(args.length==3,"error.usage");
                    create(p,who,args[1],args[2]);
                }
            }
            case "delete","disband","leave","transfer" -> askPlayer(p,who,action,args);
            case "confirm" -> confirm(p,"player",arg(args,1));
            case "list" -> plugin.menus.guildList(p);
            case "info" -> plugin.menus.guildInfo(p,args.length>1?join(args,1):null);
            case "members" -> plugin.menus.members(p);
            case "description" -> update(p,action,"guild.description",g->{g.description(who,join(args,1));return null;});
            case "rename" -> update(p,action,"guild.renamed",g->{g.rename(who,arg(args,1));return null;});
            case "invite" -> {
                Player target=Bukkit.getPlayerExact(arg(args,1));
                require(target!=null && p.canSee(target),"guild.target-online");
                Guilds.Person invited=plugin.person(target);
                change(p,action,g->g.invite(who,invited),guild->{
                    plugin.send(p,"request.invited",Map.of("player",invited.name()));
                    plugin.send(target,"request.invitation",Map.of("guild",guild.name()));
                });
            }
            case "invitations" -> query(p,action,g->g.invitations(who),rows->plugin.menus.requests(p,"invitations",rows));
            case "accept","deny" -> change(p,action,g->g.answerInvite(who,join(args,1),action.equals("accept")),guild->{
                plugin.send(p,action.equals("accept")?"request.joined":"request.denied",Map.of("guild",guild.name()));
                if (action.equals("accept")) plugin.guildMessage(guild.id(),"request.new-member",Map.of("player",who.name()));
            });
            case "apply" -> change(p,action,g->g.apply(who,join(args,1)),guild->{
                plugin.send(p,"request.applied",Map.of("guild",guild.name()));
                for (var m : plugin.view.roster(guild.id()))
                    if (plugin.settings.allows(m.role(),"applications"))
                        plugin.send(Bukkit.getPlayer(UUID.fromString(m.player())),"request.application",Map.of("player",who.name()));
            });
            case "applications" -> query(p,action,g->g.applications(who),rows->plugin.menus.requests(p,"applications",rows));
            case "application" -> application(p,who,args);
            case "kick" -> update(p,action,"guild.kicked",g->{g.kick(who,arg(args,1));return null;});
            case "alliance","war" -> relation(p,who,action,args);
            case "sethome" -> {
                Guilds.Home position=plugin.gameplay.position(p);
                update(p,action,"home.set",g->{g.setHome(who,position);return null;});
            }
            case "delhome" -> update(p,action,"home.deleted",g->{g.setHome(who,null);return null;});
            case "home" -> query(p,action,g->g.home(who),home->{
                try { plugin.gameplay.home(p,home); } catch (Guilds.Rejected error) { plugin.explain(p,error); }
            });
            case "chat" -> plugin.gameplay.chat(p,join(args,1));
            case "history" -> {
                int page=args.length==1?1:Integer.parseInt(args[1]);
                require(page>0,"error.usage");
                query(p,action,g->g.history(who),rows->plugin.menus.history(p,rows,page));
            }
            case "light" -> {
                String mode=arg(args,1).toLowerCase(Locale.ROOT);
                require(List.of("on","off").contains(mode),"error.usage");
                plugin.gameplay.setGlow(p,mode.equals("on"));
            }
            case "top" -> plugin.menus.top(p);
            case "settings" -> {
                if (args.length==1) plugin.menus.settings(p);
                else {
                    String flag=arg(args,1).toLowerCase(Locale.ROOT);
                    update(p,action,"guild.settings-updated",g->{
                        if (flag.equals("home")) plugin.feature("guild_home");
                        if (flag.equals("recruiting")) plugin.feature("applications");
                        g.toggle(who,flag);return null;
                    });
                }
            }
            case "role" -> update(p,action,"guild.rank-updated",g->{g.role(who,arg(args,1),arg(args,2).toLowerCase(Locale.ROOT));return null;});
            case "statistics" -> plugin.menus.statistics(p);
            case "announce" -> change(p,action,g->g.announce(who,args.length==1?"":join(args,1)),guild->{
                plugin.send(p,"guild.announced");
                if (!guild.announcement().isBlank()) plugin.guildMessage(guild.id(),"guild.announcement",
                        Map.of("guild",guild.name(),"text",guild.announcement()));
            });
            case "upgrade" -> change(p,action,g->g.upgrade(who),bonus->plugin.send(p,"upgrade.done",Map.of("bonus",bonus)));
            case "level" -> query(p,action,g->g.byId(g.acting(who,"info").guild()),guild->
                    plugin.send(p,"level.current",Map.of("level",guild.level(),"xp",guild.experience())));
            case "territory" -> territory(p,who,args);
            case "set" -> centre(p,who,args);
            default -> throw new Guilds.Rejected("error.usage");
        }
    }

    private void create(Player p, Guilds.Person actor, String guild, String currencyId) {
        Settings selected=plugin.settings;
        Settings.Currency currency=selected.currencies.get(currencyId.toLowerCase(Locale.ROOT));
        require(currency!=null,"payment.currency");
        if (currency.type().equals("COMMAND") && currency.amount().signum()>0) {
            String label=currency.withdraw().split(" ",2)[0].toLowerCase(Locale.ROOT);
            require(commandMap.getCommand(label)!=null,"payment.unavailable");
        }
        plugin.query(p,c->transaction(c,db->{
            guard("create");
            return new Guilds(db,selected).reserve(actor,guild,currency);
        }),order->{
            if (!stillAuthorized(p,selected)) {
                paymentState(p,order,"reserved","cancelled","player or configuration changed",null);
                return;
            }
            paymentState(p,order,"reserved","charging","",()->charge(p,order,selected));
        },null);
    }

    private boolean stillAuthorized(Player p, Settings selected) {
        return p.isOnline() && plugin.settings==selected && p.hasPermission("guildmaster.use");
    }

    private void charge(Player p, Guilds.Reservation order, Settings selected) {
        if (!stillAuthorized(p,selected)) {
            paymentState(p,order,"charging","cancelled","player or configuration changed before dispatch",
                    ()->plugin.send(p,"guild.changed"));
            return;
        }
        Economy.Result result=plugin.economy.change(selected,order,false);
        if (result!=Economy.Result.SUCCESS) {
            paymentState(p,order,"charging",result==Economy.Result.UNKNOWN?"review":"failed",result.name(),
                    ()->plugin.send(p,result==Economy.Result.UNKNOWN?"payment.review":"payment.failed",Map.of("id",order.id())));
            return;
        }
        plugin.query(p,c->{
            new Guilds(c,selected).paymentState(order.id(),"charging","charged","");
            transaction(c,db->{new Guilds(db,selected).finish(order);return null;});
            try { plugin.capture(c,true); }
            catch (Exception error) { plugin.getLogger().log(java.util.logging.Level.WARNING,"Guild created; read view will refresh",error); }
            return null;
        },ignored->plugin.send(p,"guild.created",Map.of("guild",order.guild())),
                failure->compensate(p,order,selected,failure));
    }

    private void compensate(Player p, Guilds.Reservation order, Settings selected, Throwable failure) {
        plugin.getLogger().log(java.util.logging.Level.WARNING,"Could not complete payment "+order.id(),failure);
        plugin.query(p,c->one(c,"SELECT state FROM payment WHERE id=?",row->row.getString(1),order.id()),state->{
            if ("done".equals(state)) {
                plugin.send(p,"guild.created",Map.of("guild",order.guild()));
                return;
            }
            if (!Set.of("charging","charged").contains(Objects.toString(state,""))) {
                plugin.send(p,"payment.review",Map.of("id",order.id()));
                return;
            }
            Economy.Result refunded=plugin.economy.change(selected,order,true);
            paymentState(p,order,state,refunded==Economy.Result.SUCCESS?"refunded":"review","creation failed; refund "+refunded,
                    ()->plugin.send(p,refunded==Economy.Result.SUCCESS?"payment.refunded":"payment.review",Map.of("id",order.id())));
        },error->{
            plugin.getLogger().log(java.util.logging.Level.SEVERE,"Cannot verify payment "+order.id()+" for compensation",error);
            plugin.send(p,"payment.review",Map.of("id",order.id()));
        });
    }

    private void paymentState(Player p, Guilds.Reservation order, String expected, String state, String detail, Runnable next) {
        plugin.query(p,c->{new Guilds(c,plugin.settings).paymentState(order.id(),expected,state,detail);return null;},
                ignored->{if(next!=null)next.run();},null);
    }

    private void application(Player p, Guilds.Person actor, String[] args) {
        String decision=arg(args,1).toLowerCase(Locale.ROOT), target=arg(args,2);
        require(List.of("accept","deny").contains(decision),"error.usage");
        Player online=Bukkit.getPlayerExact(target);
        if (online==null) try { online=Bukkit.getPlayer(UUID.fromString(target)); } catch (IllegalArgumentException ignored) { }
        String role=online==null?"member":plugin.initialRole(online);
        change(p,"application",g->g.answerApplication(actor,target,decision.equals("accept"),role),request->{
            plugin.send(p,"request.handled",Map.of("player",request.name()));
            Guilds.Guild guild=plugin.view.guilds().get(request.guild());
            if (guild!=null) plugin.send(Bukkit.getPlayer(UUID.fromString(request.player())),decision.equals("accept")?"request.joined":"request.denied",
                    Map.of("guild",guild.name()));
        });
    }

    private void relation(Player p, Guilds.Person actor, String kind, String[] args) {
        String action=args.length==1?"list":args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list") || action.equals("info")) {
            String filter=action.equals("info")?join(args,2):null;
            query(p,kind,g->{
                var m=g.acting(actor,"info");
                List<Guilds.Relation> rows=g.relations(m.guild(),kind);
                return filter==null?rows:rows.stream().filter(r->r.name().equalsIgnoreCase(filter)).toList();
            },rows->plugin.menus.relations(p,kind,action,rows));
            return;
        }
        require(List.of("propose","send","accept","deny","remove").contains(action),"error.usage");
        change(p,kind,g->g.relationship(actor,kind,action.equals("send")?"propose":action,join(args,2)),result->{
            String message="relation."+result.action();
            plugin.guildMessage(result.source().id(),message,Map.of("guild",result.other().name(),"kind",plugin.settings.str("messages","relation."+kind)));
            plugin.guildMessage(result.other().id(),message,Map.of("guild",result.source().name(),"kind",plugin.settings.str("messages","relation."+kind)));
        });
    }

    private void territory(Player p, Guilds.Person actor, String[] args) {
        String action=arg(args,1).toLowerCase(Locale.ROOT);
        var loc=p.getLocation();
        if (action.equals("list")) {
            query(p,"territory",g->{
                var m=g.acting(actor,"info");
                return g.claims().stream().filter(claim->claim.guild().equals(m.guild())).toList();
            },claims->{
                if (claims.isEmpty()) plugin.send(p,"guild.empty");
                for (var claim : claims) {
                    var world=Bukkit.getWorld(UUID.fromString(claim.world()));
                    plugin.send(p,"territory.row",Map.of("world",world==null?claim.world():world.getName(),"x",claim.x(),"z",claim.z()));
                }
            });
            return;
        }
        require(List.of("claim","release").contains(action),"error.usage");
        var allowed=plugin.settings.get("mechanics").getStringList("territories.worlds");
        require(action.equals("release") || allowed.isEmpty() || allowed.contains(loc.getWorld().getName()),"territory.world");
        var point=new Guilds.Claim(loc.getWorld().getUID().toString(),loc.getBlockX()>>4,loc.getBlockZ()>>4,"");
        update(p,"territory","territory."+action,g->{g.claim(actor,point,action.equals("claim"));return null;});
    }

    private void centre(Player p, Guilds.Person actor, String[] args) {
        require(Set.of("centre","center").contains(arg(args,1).toLowerCase(Locale.ROOT)),"error.usage");
        var l=p.getLocation();
        String id=l.getWorld().getUID().toString(), name=l.getWorld().getName();
        double x=l.getX(),y=l.getY(),z=l.getZ();
        plugin.change(p,c->{
            guard("set");
            var member=new Guilds(c,plugin.settings).acting(actor,"territory");
            long now=System.currentTimeMillis();
            Long cooldown=one(c,"SELECT cooldown FROM legacy_centre WHERE guild=?",r->r.getLong(1),member.guild());
            if (cooldown!=null && cooldown>now) throw new Guilds.Rejected("error.cooldown",Map.of("seconds",(cooldown-now+999)/1000));
            execute(c,"INSERT INTO legacy_centre VALUES(?,?,?,?,?,?,?) ON CONFLICT(guild) DO UPDATE SET " +
                    "world_id=excluded.world_id,world_name=excluded.world_name,x=excluded.x,y=excluded.y,z=excluded.z,cooldown=excluded.cooldown",
                    member.guild(),id,name,x,y,z,now+plugin.settings.integer("config","cooldowns.centre")*1000L);
            if (plugin.settings.enabled("guild_history")) execute(c,"INSERT INTO history(guild,actor,event,detail,at) VALUES(?,?,?,?,?)",
                    member.guild(),actor.id(),"centre",name,now);
            return null;
        },ignored->plugin.send(p,"territory.centre"));
    }

    private void askPlayer(Player p, Guilds.Person actor, String action, String[] args) {
        String key=action.equals("delete")?"disband":action;
        String name=key.equals("transfer")?arg(args,1):"";
        query(p,key,g->{
            Guilds.Member m=g.acting(actor,key);
            if (key.equals("leave")) require(!m.role().equals("leader"),"guild.leader-leave");
            else require(m.role().equals("leader"),"error.rank");
            Guilds.Member next=key.equals("transfer")?g.target(m.guild(),name):null;
            require(next==null || !next.player().equals(actor.id()),"guild.target");
            return Map.entry(g.byId(m.guild()),next==null?"":next.player());
        },prepared->{
            var guild=prepared.getKey();
            String token=ask(p,"player",()->update(p,key,"guild."+key+"-done",g->{
                switch (key) {
                    case "leave" -> g.leave(actor,guild.id());
                    case "transfer" -> g.transfer(actor,guild.id(),prepared.getValue());
                    default -> g.disband(actor,guild.id(),false);
                }
                return null;
            }));
            plugin.menus.show(p,"confirm-"+key,Map.of("token",token,"guild",guild.name(),"player",name),List.of(),1);
        });
    }

    private String ask(CommandSender sender, String key, Runnable effect) {
        String token=UUID.randomUUID().toString().substring(0,8);
        confirmations.put(sender,new Confirmation(key,token,System.currentTimeMillis()+plugin.settings.integer("config","safety.confirm-seconds")*1000L,effect));
        return token;
    }

    void clearConfirmations() { confirmations.clear(); }

    private void confirm(CommandSender sender, String key, String token) {
        Confirmation confirmation=confirmations.get(sender);
        require(confirmation!=null && confirmation.expires>=System.currentTimeMillis() &&
                confirmation.key.equals(key) && confirmation.token.equals(token),"error.confirmation");
        confirmations.remove(sender);
        confirmation.effect.run();
    }

    private boolean allowedAdmin(CommandSender sender, String action) {
        return sender.hasPermission("guildmaster.admin") || sender.hasPermission("guildmaster.admin."+action) ||
                action.equals("help") && ADMIN.stream().anyMatch(name->sender.hasPermission("guildmaster.admin."+name));
    }

    private void admin(CommandSender sender, String action, String[] args) {
        switch (action) {
            case "help" -> plugin.send(sender,"help.admin");
            case "reload" -> plugin.reload(sender);
            case "info" -> {
                if (args.length==1) plugin.send(sender,"admin.summary",Map.of("guilds",plugin.view.guilds().size(),"members",plugin.view.members().size()));
                else {
                    String name=join(args,1);
                    plugin.query(sender,c->{
                        Guilds g=new Guilds(c,plugin.settings);
                        var guild=g.find(name);
                        return Map.of("guild",guild.name(),"members",g.members(guild.id()).size(),"description",guild.description());
                    },values->plugin.send(sender,"admin.guild",values),null);
                }
            }
            case "delete" -> {
                String name=arg(args,1);
                if (args.length==3) {confirm(sender,"delete:"+name,args[2]);return;}
                require(args.length==2,"error.usage");
                String actor=sender instanceof Player p?GuildMaster.id(p):null;
                String senderName=sender.getName();
                plugin.query(sender,c->new Guilds(c,plugin.settings).find(name),guild->{
                    String token=ask(sender,"delete:"+name,()->plugin.change(sender,c->{
                        new Guilds(c,plugin.settings).disband(new Guilds.Person(actor,senderName),guild.id(),true);
                        return true;
                    },success->plugin.send(sender,"guild.disband-done")));
                    plugin.send(sender,"admin.confirm-delete",Map.of("token",token,"guild",guild.name()));
                },null);
            }
            case "migrate" -> {
                String mode=args.length==1?"check":args[1];
                require(List.of("check","apply").contains(mode),"error.usage");
                if(mode.equals("check")) plugin.migration(sender,false);
                else if (args.length==3) confirm(sender,"migrate",args[2]);
                else plugin.send(sender,"admin.confirm-migrate",Map.of("token",ask(sender,"migrate",()->plugin.migration(sender,true))));
            }
            case "debug" -> {
                String server=Bukkit.getName();
                plugin.query(sender,c->{
                    int version=one(c,"PRAGMA user_version",row->row.getInt(1));
                    int pending=one(c,"SELECT count(*) FROM payment WHERE state IN ('reserved','charging','charged','review')",row->row.getInt(1));
                    return Map.of("schema",version,"pending",pending,"server",server);
                },data->plugin.send(sender,"admin.debug",data),null);
            }
            case "backup" -> plugin.query(sender,c->plugin.store.backup(c,"manual",plugin.settings.integer("storage","backup-keep")),
                    file->plugin.send(sender,"admin.backup",Map.of("file",file.getFileName())),null);
            case "restore" -> {
                String file=arg(args,1);
                if (args.length==3) {confirm(sender,"restore:"+file,args[2]);return;}
                require(args.length==2,"error.usage");
                String token=ask(sender,"restore:"+file,()->plugin.query(sender,c->{Restore.stage(plugin.store,file);return file;},
                        staged->plugin.send(sender,"admin.restored",Map.of("file",staged)),null));
                plugin.send(sender,"admin.confirm-restore",Map.of("token",token,"file",file));
            }
            case "payment" -> adminPayment(sender,args);
            default -> throw new Guilds.Rejected("error.usage");
        }
    }

    private void adminPayment(CommandSender sender, String[] args) {
        if (args.length==1) plugin.query(sender,c->list(c,"SELECT id,player,currency,amount,state,detail FROM payment WHERE state='review' ORDER BY at DESC LIMIT 50",
                row->Map.of("id",row.getString(1),"player",row.getString(2),"currency",row.getString(3),"amount",row.getString(4),
                        "state",row.getString(5),"detail",row.getString(6))),rows->{
            if (rows.isEmpty()) plugin.send(sender,"guild.empty");
            rows.forEach(values->plugin.send(sender,"admin.payment-row",values));
        },null);
        else {
            require(args.length==4 && args[1].equals("resolve") && args[3].equals("verified"),"error.usage");
            String id=UUID.fromString(args[2]).toString();
            plugin.query(sender,c->{
                new Guilds(c,plugin.settings).paymentState(id,"review","resolved","operator verified");
                return true;
            },ignored->plugin.send(sender,"admin.payment-resolved",Map.of("id",id)),null);
        }
    }

    private void guard(String action) {
        plugin.feature("guilds");
        String feature=FEATURE.get(action);
        if (feature!=null) plugin.feature(feature);
    }

    private <T> void query(Player p, String action, Operation<T> operation, Consumer<T> callback) {
        plugin.query(p,c->{guard(action);return operation.run(new Guilds(c,plugin.settings));},value->{
            if (p.isOnline() && p.hasPermission("guildmaster.use")) callback.accept(value);
        },null);
    }

    private <T> void change(Player p, String action, Operation<T> operation, Consumer<T> callback) {
        plugin.change(p,c->{guard(action);return operation.run(new Guilds(c,plugin.settings));},callback);
    }

    private void update(Player p, String action, String message, Operation<Void> operation) {
        change(p,action,operation,ignored->plugin.send(p,message));
    }

    private List<String> complete(CommandSender sender, String[] args, boolean admin) {
        if (!plugin.ready || args.length==0 || !admin && (!sender.hasPermission("guildmaster.use") || !plugin.settings.enabled("guilds"))) return List.of();
        var aliases=aliases(plugin.settings,admin);
        String action=aliases.get(args[0].toLowerCase(Locale.ROOT));
        Stream<String> options=Stream.empty();
        if (args.length==1) options=aliases.entrySet().stream().filter(entry->admin?allowedAdmin(sender,entry.getValue()):
                !FEATURE.containsKey(entry.getValue()) || plugin.settings.enabled(FEATURE.get(entry.getValue()))).map(Map.Entry::getKey);
        else if (action!=null) {
            if (admin && !allowedAdmin(sender,action)) return List.of();
            String feature=FEATURE.get(action);
            if (!admin && feature!=null && !plugin.settings.enabled(feature)) return List.of();
            if (args.length==2) options=switch(action) {
                case "light" -> Stream.of("on","off");
                case "settings" -> Stream.of("home","recruiting");
                case "war","alliance" -> Stream.of("list","info","propose","accept","deny","remove");
                case "application" -> Stream.of("accept","deny");
                case "territory" -> Stream.of("claim","release","list");
                case "migrate" -> Stream.of("check","apply");
                case "invite" -> Bukkit.getOnlinePlayers().stream().filter(target->!(sender instanceof Player p) || p.canSee(target)).map(Player::getName);
                case "info","apply","accept","deny" -> plugin.view.guilds().values().stream().map(Guilds.Guild::name);
                case "delete" -> admin?plugin.view.guilds().values().stream().map(Guilds.Guild::name):Stream.empty();
                case "kick","transfer","role" -> sender instanceof Player p?roster(p):Stream.empty();
                default -> Stream.empty();
            };
            if (args.length==3) options=switch(action) {
                case "create" -> plugin.settings.currencies.keySet().stream();
                case "role" -> plugin.settings.roles.keySet().stream().filter(role->!role.equals("leader"));
                case "alliance","war" -> plugin.view.guilds().values().stream().map(Guilds.Guild::name);
                default -> Stream.empty();
            };
        }
        String input=args[args.length-1].toLowerCase(Locale.ROOT);
        return options.filter(value->value.toLowerCase(Locale.ROOT).startsWith(input)).limit(50).toList();
    }

    private Stream<String> roster(Player player) {
        var me=plugin.view.members().get(GuildMaster.id(player));
        return me==null?Stream.empty():plugin.view.roster(me.guild()).stream().map(Guilds.Member::name);
    }

    private static String arg(String[] args, int index) {
        require(args.length>index && !args[index].isBlank(),"error.usage");
        return args[index];
    }

    private static String join(String[] args, int index) {
        arg(args,index);
        return String.join(" ",Arrays.copyOfRange(args,index,args.length));
    }
}
