package guildmaster.probe;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import world.vaelis.guildmaster.api.PaymentAcknowledgements;

public final class Probe extends JavaPlugin implements CommandExecutor {
    private final Map<String,Double> accounts=new HashMap<>();

    @Override public void onEnable() {
        for (String command : new String[]{"gmtestpay","gmtestmove","gmteststate","gmtestkill","gmtestpapi"}) getCommand(command).setExecutor(this);
        Economy provider=(Economy)Proxy.newProxyInstance(Economy.class.getClassLoader(),new Class[]{Economy.class},
                (proxy,method,args)->switch (method.getName()) {
                    case "getName" -> "GuildMaster test provider";
                    case "isEnabled" -> true;
                    case "fractionalDigits" -> 2;
                    case "withdrawPlayer","depositPlayer" -> {
                        String user=args[0] instanceof OfflinePlayer player?player.getName():args[0].toString();
                        double amount=((Number)args[args.length-1]).doubleValue();
                        boolean refund=method.getName().equals("depositPlayer");
                        yield settle("vault",user,amount,refund);
                    }
                    case "toString" -> "GuildMaster test provider";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy==args[0];
                    default -> method.getReturnType().equals(boolean.class)?false:null;
                });
        Bukkit.getServicesManager().register(Economy.class,provider,this,ServicePriority.Highest);
    }

    private EconomyResponse settle(String currency, String player, double amount, boolean refund) {
        String key=currency+":"+player;
        double before=accounts.getOrDefault(key,1000.0);
        if (!refund && ("DeclineBot".equals(player) || before<amount)) {
            getLogger().info("BALANCE "+currency+" "+player+" declined");
            return new EconomyResponse(0,before,EconomyResponse.ResponseType.FAILURE,"declined");
        }
        double next=before+(refund?amount:-amount);
        accounts.put(key,next);
        getLogger().info("BALANCE "+currency+" "+player+" "+(refund?"refund ":"withdraw ")+amount+" = "+next);
        return new EconomyResponse(amount,next,EconomyResponse.ResponseType.SUCCESS,null);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("gmtestpay")) {
            if (!(sender instanceof ConsoleCommandSender) || args.length!=5) return false;
            var service=Bukkit.getServicesManager().load(PaymentAcknowledgements.class);
            if (service==null) return false;
            boolean refund=args[0].equals("refund");
            var result=settle(args[1],args[2],Double.parseDouble(args[3]),refund);
            if (!args[2].equals("UnknownBot")) service.acknowledge(args[4],
                    refund?PaymentAcknowledgements.Phase.REFUND:PaymentAcknowledgements.Phase.WITHDRAW,
                    result.transactionSuccess()?PaymentAcknowledgements.Outcome.SUCCEEDED:PaymentAcknowledgements.Outcome.DECLINED);
            return true;
        }
        if (!(sender instanceof Player player)) return false;
        if (command.getName().equals("gmtestpapi")) {
            var papi=Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi==null) return false;
            try {
                var api=Class.forName("me.clip.placeholderapi.PlaceholderAPI",false,papi.getClass().getClassLoader());
                String raw="PROBE guild=%guild_name% role=%guild_role% members=%guild_members%";
                player.sendMessage((String)api.getMethod("setPlaceholders",OfflinePlayer.class,String.class).invoke(null,player,raw));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unable to call PlaceholderAPI",error);
            }
        } else if (command.getName().equals("gmtestkill")) {
            Player victim=Bukkit.getPlayerExact(args[0]);
            victim.setGameMode(GameMode.SURVIVAL);
            victim.damage(100.0,player);
            player.sendMessage("PROBE attacked");
        } else if (command.getName().equals("gmtestmove")) {
            int x=args.length==0?5:Integer.parseInt(args[0]);
            World w=player.getWorld();
            w.getBlockAt(x,-61,5).setType(Material.STONE);
            w.getBlockAt(x,-60,5).setType(Material.AIR);
            w.getBlockAt(x,-59,5).setType(Material.AIR);
            player.teleport(new Location(w,x+0.5,-60,5.5));
            player.sendMessage("PROBE moved");
        } else {
            player.sendMessage("PROBE glow="+player.isGlowing()+" x="+player.getLocation().getBlockX());
        }
        return true;
    }
}
