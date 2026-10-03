package de.walahi.novosmp.enchants;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Zehn-Sekunden-Bestätigung vor einem möglichen Werkzeugbruch. */
public final class MiningDurabilityGuard {
    private final MiningEnchantConfig config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();

    public MiningDurabilityGuard(MiningEnchantConfig config) {
        this.config = config;
    }

    /**
     * @param toolInstance stabile, unsichtbare Kennung genau dieser Spitzhacke
     * @return true, wenn der Abbau fortgesetzt werden darf
     */
    public boolean allow(Player player, ItemStack tool, String toolInstance, long requiredDurability) {
        UUID playerId = player.getUniqueId();
        if (!config.durabilitySafetyEnabled()
                || !MiningToolDurability.wouldBreak(tool, requiredDurability)) {
            confirmations.remove(playerId);
            return true;
        }

        long now = System.currentTimeMillis();
        Confirmation confirmation = confirmations.remove(playerId);
        if (confirmation != null
                && confirmation.expiresAt() > now
                && confirmation.toolInstance().equals(toolInstance)) {
            return true;
        }

        int seconds = config.durabilityConfirmationSeconds();
        confirmations.put(playerId, new Confirmation(toolInstance, now + seconds * 1000L));
        String raw = config.toolWouldBreakMessage();
        if (raw != null && !raw.isBlank()) {
            player.sendMessage(miniMessage.deserialize(
                    raw.replace("%seconds%", String.valueOf(seconds))));
        }
        return false;
    }

    public void clear(Player player) {
        if (player != null) confirmations.remove(player.getUniqueId());
    }

    public void clearAll() {
        confirmations.clear();
    }

    private record Confirmation(String toolInstance, long expiresAt) {}
}
