package de.walahi.smpcore.api.internal;

import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.service.EconomyApi;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.services.EconomyService;
import java.util.Objects;
import java.util.UUID;

final class DefaultEconomyApi implements EconomyApi {
    private final EconomyService service;

    DefaultEconomyApi(EconomyService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override public boolean available() { return service.available(); }
    @Override public long balance(UUID uuid) { return service.balance(uuid); }
    @Override public EconomyOperationResult deposit(UUID uuid, long amount, String reason, ActionContext context) { return service.deposit(uuid, amount, reason, context); }
    @Override public EconomyOperationResult withdraw(UUID uuid, long amount, String reason, ActionContext context) { return service.withdraw(uuid, amount, reason, context); }
    @Override public EconomyOperationResult setBalance(UUID uuid, long amount, String reason, ActionContext context) { return service.setBalance(uuid, amount, reason, context); }
    @Override public EconomyOperationResult transfer(UUID from, UUID to, long amount, String reason, ActionContext context) { return service.transfer(from, to, amount, reason, context); }
}
