package de.walahi.smpcore.bootstrap;

import de.walahi.smpcore.api.SMPCoreApi;
import de.walahi.smpcore.api.SMPCoreCapability;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Performs a lightweight runtime smoke test for the public API contract.
 * This deliberately avoids mutating player data and only verifies registration,
 * mandatory service accessors and capability consistency.
 */
public final class ApiContractVerifier {

    private final Plugin plugin;

    public ApiContractVerifier(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public boolean verify() {
        SMPCoreApi api = Bukkit.getServicesManager().load(SMPCoreApi.class);
        if (api == null) {
            return fail("SMPCoreApi wurde nicht im Bukkit ServicesManager registriert.");
        }

        try {
            Objects.requireNonNull(api.version(), "api.version()");
            Objects.requireNonNull(api.serverType(), "api.serverType()");
            Objects.requireNonNull(api.startedAt(), "api.startedAt()");
            Objects.requireNonNull(api.capabilities(), "api.capabilities()");
            Objects.requireNonNull(api.homes(), "api.homes()");
            Objects.requireNonNull(api.playerData(), "api.playerData()");
            Objects.requireNonNull(api.punishments(), "api.punishments()");
            Objects.requireNonNull(api.economy(), "api.economy()");
            Objects.requireNonNull(api.professions(), "api.professions()");

            if (!api.supports(SMPCoreCapability.HOMES)
                    || !api.supports(SMPCoreCapability.PLAYER_DATA)
                    || !api.supports(SMPCoreCapability.PUNISHMENTS)) {
                return fail("Pflicht-Capabilities der öffentlichen API fehlen.");
            }
            if (api.supports(SMPCoreCapability.ECONOMY) != api.economy().available()) {
                return fail("ECONOMY-Capability stimmt nicht mit EconomyApi.available() überein.");
            }
            if (api.supports(SMPCoreCapability.PROFESSIONS) != api.professions().available()) {
                return fail("PROFESSIONS-Capability stimmt nicht mit ProfessionApi.available() überein.");
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("API-Vertragstest fehlgeschlagen: " + exception.getMessage());
            return false;
        }

        plugin.getLogger().info("[OK] Öffentlicher API-Vertrag verifiziert");
        return true;
    }

    private boolean fail(String message) {
        plugin.getLogger().severe("API-Vertragstest fehlgeschlagen: " + message);
        return false;
    }
}
