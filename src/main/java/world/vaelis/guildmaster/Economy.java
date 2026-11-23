package world.vaelis.guildmaster;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import world.vaelis.guildmaster.api.PaymentAcknowledgements;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Economy implements PaymentAcknowledgements {
    enum Result { SUCCESS, DECLINED, UNKNOWN }
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z]+)}");
    private final JavaPlugin plugin;
    private Invocation current;

    private static final class Invocation {
        final String id;
        final Phase phase;
        Outcome outcome;
        Invocation(String id, Phase phase) { this.id=id; this.phase=phase; }
    }

    Economy(JavaPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getServicesManager().register(PaymentAcknowledgements.class,this,plugin,ServicePriority.Normal);
    }

    @Override public boolean acknowledge(String transaction, Phase phase, Outcome outcome) {
        if (!Bukkit.isPrimaryThread() || current==null || !current.id.equals(transaction) || current.phase!=phase
                || current.outcome!=null || outcome==null) return false;
        current.outcome=outcome;
        return true;
    }

    Result change(Settings settings, Guilds.Reservation order, boolean refund) {
        Settings.Currency currency = order.currency();
        if (currency.amount().signum()==0) return Result.SUCCESS;
        if (currency.type().equals("VAULT")) return vault(settings,order.actor().id(),currency.amount(),refund);

        String template = refund?currency.refund():currency.withdraw();
        if (template.isBlank()) return refund?Result.UNKNOWN:Result.DECLINED;
        if (current!=null) return Result.UNKNOWN;
        BigDecimal before=currency.balance().isEmpty()?null:balance(settings,order);
        if (!currency.balance().isEmpty() && before==null) return Result.DECLINED;
        Invocation invocation = new Invocation(order.id(),refund?Phase.REFUND:Phase.WITHDRAW);
        Map<String,String> params = Map.of("player",order.actor().name(),"uuid",order.actor().id(),
                "amount",currency.amount().toPlainString(),"guild",order.guild(),"currency",currency.id(),"transaction",order.id());
        // Replace on the original template so placeholders contained in a name stay literal.
        String command = TOKEN.matcher(template).replaceAll(match->Matcher.quoteReplacement(params.getOrDefault(match.group(1),match.group())));
        if (command.chars().anyMatch(Character::isISOControl)) return Result.DECLINED;
        current=invocation;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command);
            return result(settings,order,refund,invocation,before);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING,"Currency command failed for payment "+order.id(),error);
            return result(settings,order,refund,invocation,before);
        } finally {
            current=null;
        }
    }

    private Result result(Settings settings, Guilds.Reservation order, boolean refund, Invocation invocation, BigDecimal before) {
        if (invocation.outcome!=null) return invocation.outcome==Outcome.SUCCEEDED?Result.SUCCESS:Result.DECLINED;
        if (before==null) return Result.UNKNOWN;
        BigDecimal after=balance(settings,order);
        if (after==null) return Result.UNKNOWN;
        BigDecimal delta=refund?after.subtract(before):before.subtract(after);
        if (delta.compareTo(order.currency().amount())==0) return Result.SUCCESS;
        return delta.signum()==0?Result.DECLINED:Result.UNKNOWN;
    }

    private BigDecimal balance(Settings settings, Guilds.Reservation order) {
        if (!settings.bool("integrations","placeholderapi") || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        try {
            return GuildPlaceholders.balance(Bukkit.getOfflinePlayer(UUID.fromString(order.actor().id())),order.currency().balance());
        } catch (LinkageError | RuntimeException error) {
            plugin.getLogger().log(Level.FINE,"Balance verification unavailable for "+order.currency().id(),error);
            return null;
        }
    }

    Result vault(Settings settings, String id, BigDecimal amount, boolean refund) {
        if (!settings.bool("integrations","vault") || !Bukkit.getPluginManager().isPluginEnabled("Vault")) return Result.DECLINED;
        var manager = Bukkit.getServicesManager();
        for (Class<?> type : manager.getKnownServices()) {
            if (!type.getName().equals("net.milkbowl.vault.economy.Economy")) continue;
            Object provider = manager.load(type);
            if (provider==null) continue;
            try {
                var method = type.getMethod(refund?"depositPlayer":"withdrawPlayer",OfflinePlayer.class,double.class);
                Object response = method.invoke(provider,Bukkit.getOfflinePlayer(UUID.fromString(id)),amount.doubleValue());
                return (boolean)response.getClass().getMethod("transactionSuccess").invoke(response)?Result.SUCCESS:Result.DECLINED;
            } catch (ReflectiveOperationException | RuntimeException error) {
                plugin.getLogger().log(Level.WARNING,"Vault economy result could not be verified",error);
                return Result.UNKNOWN;
            }
        }
        return Result.DECLINED;
    }
}
