package de.walahi.smpcore.api.service;

import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.economy.EconomyOperationResult;
import java.util.UUID;

public interface EconomyApi {
    boolean available();
    long balance(UUID playerUuid);
    EconomyOperationResult deposit(UUID playerUuid, long amount, String reason, ActionContext context);
    EconomyOperationResult withdraw(UUID playerUuid, long amount, String reason, ActionContext context);
    EconomyOperationResult setBalance(UUID playerUuid, long amount, String reason, ActionContext context);
    EconomyOperationResult transfer(UUID fromPlayerUuid, UUID toPlayerUuid, long amount, String reason, ActionContext context);
}
