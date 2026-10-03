package de.walahi.smpcore.api.internal;

import de.walahi.smpcore.api.SMPCoreApi;
import de.walahi.smpcore.api.SMPCoreCapability;
import de.walahi.smpcore.api.service.*;
import de.walahi.smpcore.network.ServerType;
import de.walahi.smpcore.services.ServiceManager;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Internal immutable implementation of the public SMPCore API. */
public final class DefaultSMPCoreApi implements SMPCoreApi {
    private final String version;
    private final ServerType serverType;
    private final Instant startedAt;
    private final Set<SMPCoreCapability> capabilities;
    private final HomeApi homes;
    private final PlayerDataApi playerData;
    private final PunishmentApi punishments;
    private final EconomyApi economy;
    private final ProfessionApi professions;

    public DefaultSMPCoreApi(String version, ServerType serverType, Instant startedAt, ServiceManager services) {
        this.version = Objects.requireNonNull(version, "version");
        this.serverType = Objects.requireNonNull(serverType, "serverType");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(services, "services");
        this.homes = new DefaultHomeApi(services.homes());
        this.playerData = new DefaultPlayerDataApi(services.playerData());
        this.punishments = new DefaultPunishmentApi(services.punishments());
        this.economy = new DefaultEconomyApi(services.economy());
        this.professions = new DefaultProfessionApi(services.professions());
        EnumSet<SMPCoreCapability> active = EnumSet.of(SMPCoreCapability.HOMES, SMPCoreCapability.PLAYER_DATA, SMPCoreCapability.PUNISHMENTS);
        if (economy.available()) active.add(SMPCoreCapability.ECONOMY);
        if (professions.available()) active.add(SMPCoreCapability.PROFESSIONS);
        this.capabilities = Set.copyOf(active);
    }

    @Override public String version() { return version; }
    @Override public ServerType serverType() { return serverType; }
    @Override public Instant startedAt() { return startedAt; }
    @Override public Set<SMPCoreCapability> capabilities() { return capabilities; }
    @Override public HomeApi homes() { return homes; }
    @Override public PlayerDataApi playerData() { return playerData; }
    @Override public PunishmentApi punishments() { return punishments; }
    @Override public EconomyApi economy() { return economy; }
    @Override public ProfessionApi professions() { return professions; }
}
