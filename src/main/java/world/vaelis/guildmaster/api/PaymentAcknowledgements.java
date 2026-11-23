package world.vaelis.guildmaster.api;

/**
 * Bukkit service for trusted economy adapters. Acknowledge on the server thread
 * before the configured command returns, after the provider commits its operation.
 */
public interface PaymentAcknowledgements {
    enum Outcome { SUCCEEDED, DECLINED }
    enum Phase { WITHDRAW, REFUND }

    boolean acknowledge(String transaction, Phase phase, Outcome outcome);
}
