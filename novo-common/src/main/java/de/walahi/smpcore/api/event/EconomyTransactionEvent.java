package de.walahi.smpcore.api.event;

import org.bukkit.event.HandlerList;
import java.util.Objects;
import java.util.UUID;

public final class EconomyTransactionEvent extends SMPCoreEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    public enum Type { DEPOSIT, WITHDRAW, TRANSFER, SET }

    private final UUID playerUuid;
    private final Type type;
    private final long amount;
    private final long balanceBefore;
    private final long balanceAfter;
    private final String reason;

    public EconomyTransactionEvent(UUID playerUuid, Type type, long amount,
                                   long balanceBefore, long balanceAfter, String reason) {
        this(playerUuid, type, amount, balanceBefore, balanceAfter, reason,
                ActionContext.system(playerUuid));
    }

    public EconomyTransactionEvent(UUID playerUuid, Type type, long amount,
                                   long balanceBefore, long balanceAfter, String reason,
                                   ActionContext context) {
        super(context);
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.type = Objects.requireNonNull(type, "type");
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.reason = Objects.requireNonNullElse(reason, "");
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public Type getType() { return type; }
    public long getAmount() { return amount; }
    public long getBalanceBefore() { return balanceBefore; }
    public long getBalanceAfter() { return balanceAfter; }
    public String getReason() { return reason; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
