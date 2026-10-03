package de.walahi.smpcore.api;

import de.walahi.smpcore.api.service.EconomyApi;
import de.walahi.smpcore.api.service.HomeApi;
import de.walahi.smpcore.api.service.PlayerDataApi;
import de.walahi.smpcore.api.service.ProfessionApi;
import de.walahi.smpcore.api.service.PunishmentApi;
import de.walahi.smpcore.network.ServerType;

import java.time.Instant;
import java.util.Set;

/** Public entry point for integrations with SMPCore. */
public interface SMPCoreApi {
    String version();
    ServerType serverType();
    Instant startedAt();
    Set<SMPCoreCapability> capabilities();
    default boolean supports(SMPCoreCapability capability) { return capabilities().contains(capability); }
    HomeApi homes();
    PlayerDataApi playerData();
    PunishmentApi punishments();
    EconomyApi economy();
    ProfessionApi professions();
}
