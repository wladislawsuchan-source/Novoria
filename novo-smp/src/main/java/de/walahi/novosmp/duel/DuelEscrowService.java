package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Owns all duel wager validation, debit, payout and refund operations. */
final class DuelEscrowService {
    private final NovoSMPPlugin plugin;
    private final DuelConfig config;
    private final EconomyService economy;

    DuelEscrowService(NovoSMPPlugin plugin, DuelConfig config, EconomyService economy) {
        this.plugin = plugin;
        this.config = config;
        this.economy = economy;
    }

    boolean canAffordBoth(Player challenger, Player target, long wager) {
        if (wager < config.minimumWager() || wager > config.maximumWager()) {
            send(challenger, config.message("wager.invalid", "<red>Der Einsatz ist ungültig.</red>"));
            return false;
        }
        if (wager <= 0L) return true;

        long challengerBalance = economy.balance(challenger.getUniqueId());
        long targetBalance = economy.balance(target.getUniqueId());
        if (challengerBalance < wager) {
            send(challenger, config.message("wager.own-insufficient", "<red>Du besitzt nicht genug Coins für diesen Einsatz.</red>"));
            return false;
        }
        if (targetBalance < wager) {
            send(challenger, config.message("wager.opponent-insufficient", "<red>Der Gegner besitzt nicht genug Coins für diesen Einsatz.</red>"));
            send(target, config.message("wager.requested-insufficient", "<red>Du besitzt nicht genug Coins für die angefragte Duellhöhe.</red>"));
            return false;
        }
        if (challengerBalance > Long.MAX_VALUE - wager || targetBalance > Long.MAX_VALUE - wager) {
            send(challenger, config.message("wager.technically-too-high-challenger", "<red>Der Einsatz ist für mindestens ein Konto technisch zu hoch.</red>"));
            send(target, config.message("wager.technically-too-high-target", "<red>Der angefragte Einsatz ist technisch zu hoch.</red>"));
            return false;
        }
        return true;
    }

    boolean withdraw(DuelMatch match, Player one, Player two) {
        long wager = match.request.wager();
        if (wager <= 0L) return true;

        EconomyOperationResult first = economy.withdraw(
                one.getUniqueId(), wager, "DUEL-ESCROW", ActionContext.system(one.getUniqueId())
        );
        if (first != EconomyOperationResult.SUCCESS) {
            send(one, config.message("wager.debit-failed", "<red>Dein Einsatz konnte nicht abgezogen werden.</red>"));
            send(two, config.message("wager.start-failed", "<red>Das Duell konnte wegen des Einsatzes nicht gestartet werden.</red>"));
            return false;
        }

        EconomyOperationResult second = economy.withdraw(
                two.getUniqueId(), wager, "DUEL-ESCROW", ActionContext.system(two.getUniqueId())
        );
        if (second == EconomyOperationResult.SUCCESS) return true;

        EconomyOperationResult rollback = economy.deposit(
                one.getUniqueId(), wager, "DUEL-ESCROW-ROLLBACK", ActionContext.system(one.getUniqueId())
        );
        if (rollback != EconomyOperationResult.SUCCESS) {
            plugin.getLogger().severe("Duell-Einsatz-Rollback für " + one.getUniqueId() + " fehlgeschlagen: " + rollback);
        }
        send(two, config.message("wager.debit-failed", "<red>Dein Einsatz konnte nicht abgezogen werden.</red>"));
        send(one, config.message("wager.start-failed", "<red>Das Duell konnte wegen des Einsatzes nicht gestartet werden.</red>"));
        return false;
    }

    void payoutWinner(DuelMatch match, UUID winner) {
        long payout;
        try {
            payout = Math.multiplyExact(match.request.wager(), 2L);
        } catch (ArithmeticException exception) {
            plugin.getLogger().severe("Duell-Auszahlung ist wegen Zahlenüberlauf fehlgeschlagen. Einsätze werden zurückgezahlt.");
            refund(match);
            return;
        }

        EconomyOperationResult result = economy.deposit(winner, payout, "DUEL-WIN", ActionContext.system(winner));
        if (result == EconomyOperationResult.SUCCESS) {
            match.escrowed = false;
            return;
        }

        plugin.getLogger().severe("Duell-Auszahlung an " + winner + " fehlgeschlagen: " + result
                + ". Einsätze werden zurückgezahlt.");
        refund(match);
    }

    void refund(DuelMatch match) {
        if (!match.escrowed || match.request.wager() <= 0L) return;

        EconomyOperationResult first = economy.deposit(
                match.request.challenger(), match.request.wager(), "DUEL-REFUND",
                ActionContext.system(match.request.challenger())
        );
        EconomyOperationResult second = economy.deposit(
                match.request.target(), match.request.wager(), "DUEL-REFUND",
                ActionContext.system(match.request.target())
        );
        if (first != EconomyOperationResult.SUCCESS || second != EconomyOperationResult.SUCCESS) {
            plugin.getLogger().severe("Mindestens eine Duell-Rückzahlung ist fehlgeschlagen: challenger="
                    + first + ", target=" + second);
            return;
        }
        match.escrowed = false;
    }

    private void send(Player player, String message) {
        if (player != null && player.isOnline()) DuelMessages.send(config, player, message);
    }
}
