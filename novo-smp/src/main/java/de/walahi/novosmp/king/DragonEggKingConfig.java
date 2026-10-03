package de.walahi.novosmp.king;

import de.walahi.novosmp.NovoSMPPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.ZoneId;

public final class DragonEggKingConfig {
    private final NovoSMPPlugin plugin;
    public DragonEggKingConfig(NovoSMPPlugin plugin) { this.plugin = plugin; }
    private FileConfiguration c() { return plugin.configs().server(); }
    public boolean enabled() { return c().getBoolean("dragon-egg-king.enabled", true); }
    public String world() { return c().getString("dragon-egg-king.world", "smp_end"); }
    public int mandatoryPerDay() { return Math.max(0, c().getInt("dragon-egg-king.mandatory-duels-per-day", 3)); }
    public long mandatoryCost() { return Math.max(0L, c().getLong("dragon-egg-king.mandatory-challenge-cost", 20_000L)); }
    public int acceptTimeoutSeconds() { return Math.max(10, c().getInt("dragon-egg-king.mandatory-accept-timeout-seconds", 60)); }
    public int requestTimeoutSeconds() { return Math.max(acceptTimeoutSeconds(), c().getInt("dragon-egg-king.challenge-timeout-seconds", 90)); }
    public long cooldownMillis() { return Math.max(0L, c().getLong("dragon-egg-king.challenger-cooldown-minutes", 5L)) * 60_000L; }
    public boolean requireFreeSlot() { return c().getBoolean("dragon-egg-king.require-free-inventory-slot", true); }
    public boolean elytraProtected() { return c().getBoolean("dragon-egg-king.elytra-no-durability", true); }
    public boolean blockAfk() { return c().getBoolean("dragon-egg-king.block-challenge-while-afk", true); }
    public ZoneId zone() {
        try { return ZoneId.of(c().getString("dragon-egg-king.time-zone", "Europe/Berlin")); }
        catch (Exception ignored) { return ZoneId.of("Europe/Berlin"); }
    }
    public String message(String key, String fallback) {
        return c().getString("dragon-egg-king.messages." + key, fallback);
    }
}
