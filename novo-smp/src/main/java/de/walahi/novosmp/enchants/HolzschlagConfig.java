package de.walahi.novosmp.enchants;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lädt die {@code holzschlag.yml} und stellt die Stufenwerte bereit.
 *
 * <p>Fehlt eine Stufe in der Datei, wird sie mit sinnvollen Werten ergänzt statt den
 * Start abzubrechen — eine unvollständige Config soll den Server nicht lahmlegen.</p>
 */
public final class HolzschlagConfig {
    public static final String CONFIG_FILE = "holzschlag.yml";
    public static final String ENCHANTMENT_ID = "holzschlag";

    /** Einheitlicher Cooldown für alle Stufen, solange die Config nichts anderes vorgibt. */
    private static final int DEFAULT_COOLDOWN_SECONDS = 2;

    private final SMPCorePlugin plugin;
    private final Map<Integer, HolzschlagLevel> levels = new LinkedHashMap<>();

    private boolean enabled = true;
    private String displayName = "Holzschlag";
    private String color = "<gray>";
    private int maxLevel = 5;
    private boolean disableWhileSneaking = true;
    private boolean respectProtection = true;
    private boolean ignorePlayerPlacedLeaves = true;
    private String cooldownMessage = "<red>Holzschlag ist noch %seconds%s im Cooldown.</red>";
    private boolean cooldownActionbar = true;
    private boolean durabilitySafetyEnabled = true;
    private int durabilityConfirmationSeconds = 10;
    private String toolWouldBreakMessage = "<red>Deine Axt würde bei diesem Holzschlag zerbrechen. "
            + "Baue innerhalb von <white>%seconds%</white> Sekunden erneut ab, "
            + "wenn sie trotzdem zerbrechen darf.</red>";

    public HolzschlagConfig(SMPCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.configs().holzschlagFile().reload();
        var config = plugin.configs().holzschlag();

        enabled = config.getBoolean("enabled", true);
        displayName = config.getString("display-name", "Holzschlag");
        color = config.getString("color", "<gray>");
        maxLevel = Math.max(1, config.getInt("max-level", 5));
        disableWhileSneaking = config.getBoolean("disable-while-sneaking", true);
        respectProtection = config.getBoolean("respect-protection", true);
        ignorePlayerPlacedLeaves = config.getBoolean("leaves.ignore-player-placed", true);
        cooldownMessage = config.getString("messages.cooldown",
                "<red>Holzschlag ist noch %seconds%s im Cooldown.</red>");
        cooldownActionbar = config.getBoolean("messages.cooldown-actionbar", true);
        durabilitySafetyEnabled = config.getBoolean("durability-safety.enabled", true);
        durabilityConfirmationSeconds = Math.max(1,
                config.getInt("durability-safety.confirmation-seconds", 10));
        toolWouldBreakMessage = config.getString("messages.tool-would-break",
                "<red>Deine Axt würde bei diesem Holzschlag zerbrechen. "
                        + "Baue innerhalb von <white>%seconds%</white> Sekunden erneut ab, "
                        + "wenn sie trotzdem zerbrechen darf.</red>");

        if (!respectProtection) {
            plugin.getLogger().warning("Holzschlag: 'respect-protection' steht auf false. "
                    + "Der Baumfäller umgeht damit WorldGuard und jeden anderen Regionsschutz.");
        }

        levels.clear();
        ConfigurationSection section = config.getConfigurationSection("levels");
        for (int level = 1; level <= maxLevel; level++) {
            ConfigurationSection levelSection = section == null
                    ? null : section.getConfigurationSection(String.valueOf(level));
            levels.put(level, readLevel(level, levelSection));
        }
        plugin.getLogger().info("Holzschlag geladen: " + levels.size() + " Stufen"
                + (enabled ? "" : " (deaktiviert)"));
    }

    private HolzschlagLevel readLevel(int level, ConfigurationSection section) {
        // Fallback-Kurve, falls die Stufe in der Config fehlt.
        int defaultBlocks = (int) Math.pow(2, level + 2);
        if (section == null) {
            plugin.getLogger().warning("Holzschlag-Stufe " + level + " fehlt in " + CONFIG_FILE
                    + " und wird mit Standardwerten geladen.");
            return new HolzschlagLevel(defaultBlocks, DEFAULT_COOLDOWN_SECONDS,
                    level >= 4, level >= 4 ? 512 : 0, 1, true, true);
        }
        return new HolzschlagLevel(
                Math.max(1, section.getInt("max-blocks", defaultBlocks)),
                Math.max(0, section.getInt("cooldown-seconds", DEFAULT_COOLDOWN_SECONDS)),
                section.getBoolean("break-leaves", level >= 4),
                Math.max(0, section.getInt("max-leaves", level >= 4 ? 512 : 0)),
                Math.max(0, section.getInt("durability-per-block", 1)),
                section.getBoolean("same-material-only", true),
                section.getBoolean("include-diagonals", true)
        );
    }

    /** Stufenwerte, bei unbekannter Stufe die höchste konfigurierte. */
    public HolzschlagLevel level(int level) {
        HolzschlagLevel settings = levels.get(level);
        if (settings != null) return settings;
        return levels.getOrDefault(maxLevel, levels.values().iterator().next());
    }

    public CustomEnchantment enchantment() {
        return new CustomEnchantment(ENCHANTMENT_ID, displayName, color, maxLevel,
                CustomEnchantment.Applicability.AXE);
    }

    public boolean enabled() { return enabled; }
    public int maxLevel() { return maxLevel; }
    public boolean disableWhileSneaking() { return disableWhileSneaking; }
    public boolean respectProtection() { return respectProtection; }
    public boolean ignorePlayerPlacedLeaves() { return ignorePlayerPlacedLeaves; }
    public String cooldownMessage() { return cooldownMessage; }
    public boolean cooldownActionbar() { return cooldownActionbar; }
    public boolean durabilitySafetyEnabled() { return durabilitySafetyEnabled; }
    public int durabilityConfirmationSeconds() { return durabilityConfirmationSeconds; }
    public String toolWouldBreakMessage() { return toolWouldBreakMessage; }
}
