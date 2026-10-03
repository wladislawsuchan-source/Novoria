package de.walahi.smpcore.homes;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Locale;

/** Zentrale, konfigurierbare Ermittlung des Home-Limits. */
public final class HomeLimitResolver {
    private HomeLimitResolver() { }

    public static int resolve(Player player, FileConfiguration config) {
        if (player.hasPermission("smpcore.home.bypass.limit")) return -1;

        int highest = -1;
        String prefix = "smpcore.home.limit.";
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String permission = info.getPermission().toLowerCase(Locale.ROOT);
            if (!permission.startsWith(prefix)) continue;
            try {
                highest = Math.max(highest, Integer.parseInt(permission.substring(prefix.length())));
            } catch (NumberFormatException ignored) { }
        }

        ConfigurationSection groupLimits = config.getConfigurationSection("homes.group-limits");
        if (groupLimits != null) {
            for (String group : groupLimits.getKeys(false)) {
                if (!player.hasPermission("group." + group.toLowerCase(Locale.ROOT))) continue;
                highest = Math.max(highest, Math.max(0, groupLimits.getInt(group, 0)));
            }
        }

        return highest >= 0 ? highest : Math.max(0, config.getInt("homes.default-limit", 3));
    }
}
