package de.walahi.novosmp.enchants;

import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** Gemeinsame Blockregeln für 3x3 und Veinminer. */
public final class MiningBlockRules {
    private final MiningEnchantConfig config;

    public MiningBlockRules(MiningEnchantConfig config) {
        this.config = config;
    }

    /**
     * Prüft, ob ein Block von der 3x3-Fähigkeit des konkreten Werkzeugs erfasst wird.
     * Spitzhacken bearbeiten nur Spitzhackenblöcke, Schaufeln nur Schaufelblöcke.
     */
    public boolean canMineWithThreeByThreeTool(Block block, ItemStack tool, Player player) {
        if (!basicChecks(block, tool, player)) return false;
        String toolName = tool.getType().name().toUpperCase(Locale.ROOT);
        if (toolName.endsWith("_PICKAXE")) {
            return Tag.MINEABLE_PICKAXE.isTagged(block.getType());
        }
        if (toolName.endsWith("_SHOVEL")) {
            return Tag.MINEABLE_SHOVEL.isTagged(block.getType());
        }
        return false;
    }

    /**
     * Prüft, ob der Block als zusätzlicher Veinminer-Block abgebaut werden darf.
     * Veinminer bleibt absichtlich auf Spitzhacken beschränkt.
     */
    public boolean canMineWithPickaxe(Block block, ItemStack tool, Player player) {
        return basicChecks(block, tool, player) && Tag.MINEABLE_PICKAXE.isTagged(block.getType());
    }

    private boolean basicChecks(Block block, ItemStack tool, Player player) {
        if (block == null || block.getType().isAir() || tool == null || tool.getType().isAir()) return false;
        if (config.isThreeByThreeExcluded(block.getType())
                && !(config.netheriteObsidianThreeByThree()
                && block.getType() == org.bukkit.Material.OBSIDIAN
                && tool.getType() == org.bukkit.Material.NETHERITE_PICKAXE)) return false;
        // Container und andere besondere Block-Entities (z. B. Endertruhen,
        // Beacons, Spawner oder Shulkerboxen) werden niemals als Nebenblock entfernt.
        if (block.getState() instanceof TileState) return false;
        // Das konkrete Werkzeug muss einen Drop erhalten. So verschwinden keine Blöcke,
        // die das verwendete Werkzeug nicht korrekt abbauen kann.
        return !block.getDrops(tool, player).isEmpty();
    }

    public boolean sameOreFamily(Block block, String family) {
        if (block == null || family == null) return false;
        return family.equals(config.oreFamily(block.getType()));
    }
}
