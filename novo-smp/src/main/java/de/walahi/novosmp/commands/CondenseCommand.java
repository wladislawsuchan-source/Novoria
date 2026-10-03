package de.walahi.novosmp.commands;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Safely condenses an explicit whitelist of plain vanilla resources. */
public final class CondenseCommand extends BaseCommand {
    private final NovoSMPPlugin smp;
    private final List<Family> families;

    public CondenseCommand(NovoSMPPlugin plugin) {
        super(plugin);
        this.smp = plugin;
        this.families = buildFamilies();
    }

    @Override
    protected String permission() {
        return null;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>Dieser Befehl ist nur für Spieler.</red>");
            return true;
        }
        if (args.length != 0) {
            player.sendRichMessage("<gray>Benutzung: <yellow>/condense</yellow></gray>");
            return true;
        }
        if (!plugin.getRankManager().hasPremium(player)) {
            player.sendRichMessage("<red>/condense ist ein Vorteil für Premium und Premium+.</red>");
            return true;
        }
        if (smp.isPlayerInDuel(player.getUniqueId())) {
            player.sendRichMessage("<red>Während eines Duells kannst du /condense nicht verwenden.</red>");
            return true;
        }

        ItemStack[] original = cloneContents(player.getInventory().getStorageContents());
        CondensePlan plan = plan(original);
        if (plan.savedItems() <= 0) {
            player.sendRichMessage("<gray>Du hast momentan keine verdichtbaren Mengen im Inventar.</gray>");
            return true;
        }

        ItemStack[] retained = cloneContents(original);
        for (int slot : plan.sourceSlots()) retained[slot] = null;
        player.getInventory().setStorageContents(retained);
        try {
            for (ItemStack output : plan.outputs()) {
                if (!player.getInventory().addItem(output).isEmpty()) {
                    player.getInventory().setStorageContents(original);
                    player.sendRichMessage("<red>Verdichten abgebrochen: Das Ergebnis passt nicht sicher ins Inventar.</red>");
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            player.getInventory().setStorageContents(original);
            plugin.getLogger().warning("/condense für " + player.getName() + " wurde sicher zurückgerollt: "
                    + exception.getMessage());
            player.sendRichMessage("<red>Verdichten wurde wegen eines Fehlers vollständig zurückgerollt.</red>");
            return true;
        }

        player.sendRichMessage("<green>Ressourcen verdichtet.</green> <gray>Eingesparte Einzelitems: <yellow>"
                + plan.savedItems() + "</yellow>.</gray>");
        return true;
    }

    private CondensePlan plan(ItemStack[] contents) {
        Set<Integer> sourceSlots = new HashSet<>();
        List<ItemStack> outputs = new ArrayList<>();
        int savedItems = 0;
        for (Family family : families) {
            long units = 0L;
            int originalItems = 0;
            Set<Integer> familySlots = new HashSet<>();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (!isPlain(stack)) continue;
                Integer unitValue = family.inputUnits().get(stack.getType());
                if (unitValue == null) continue;
                units += (long) stack.getAmount() * unitValue;
                originalItems += stack.getAmount();
                familySlots.add(slot);
            }
            if (units <= 0L) continue;

            List<ItemStack> familyOutputs = new ArrayList<>();
            long remaining = units;
            int outputItems = 0;
            for (Unit output : family.outputs()) {
                int amount = (int) (remaining / output.units());
                remaining %= output.units();
                outputItems += amount;
                appendStacks(familyOutputs, output.material(), amount);
            }
            if (originalItems <= outputItems) continue;
            sourceSlots.addAll(familySlots);
            outputs.addAll(familyOutputs);
            savedItems += originalItems - outputItems;
        }
        return new CondensePlan(sourceSlots, outputs, savedItems);
    }

    private boolean isPlain(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && !stack.hasItemMeta();
    }

    private void appendStacks(List<ItemStack> outputs, Material material, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int part = Math.min(material.getMaxStackSize(), remaining);
            outputs.add(new ItemStack(material, part));
            remaining -= part;
        }
    }

    private List<Family> buildFamilies() {
        List<Family> result = new ArrayList<>();
        result.add(nuggetFamily(Material.IRON_NUGGET, Material.IRON_INGOT, Material.IRON_BLOCK));
        result.add(nuggetFamily(Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK));
        Material copperNugget = Material.matchMaterial("COPPER_NUGGET");
        if (copperNugget != null) {
            result.add(nuggetFamily(copperNugget, Material.COPPER_INGOT, Material.COPPER_BLOCK));
        } else {
            result.add(blockFamily(Material.COPPER_INGOT, Material.COPPER_BLOCK));
        }
        result.add(blockFamily(Material.RAW_IRON, Material.RAW_IRON_BLOCK));
        result.add(blockFamily(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK));
        result.add(blockFamily(Material.RAW_COPPER, Material.RAW_COPPER_BLOCK));
        result.add(blockFamily(Material.COAL, Material.COAL_BLOCK));
        result.add(blockFamily(Material.REDSTONE, Material.REDSTONE_BLOCK));
        result.add(blockFamily(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK));
        result.add(blockFamily(Material.DIAMOND, Material.DIAMOND_BLOCK));
        result.add(blockFamily(Material.EMERALD, Material.EMERALD_BLOCK));
        return List.copyOf(result);
    }

    private Family nuggetFamily(Material nugget, Material ingot, Material block) {
        Map<Material, Integer> inputs = new LinkedHashMap<>();
        inputs.put(nugget, 1);
        inputs.put(ingot, 9);
        return new Family(inputs, List.of(new Unit(block, 81), new Unit(ingot, 9), new Unit(nugget, 1)));
    }

    private Family blockFamily(Material item, Material block) {
        return new Family(Map.of(item, 1), List.of(new Unit(block, 9), new Unit(item, 1)));
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            copy[index] = contents[index] == null ? null : contents[index].clone();
        }
        return copy;
    }

    private record Unit(Material material, int units) { }
    private record Family(Map<Material, Integer> inputUnits, List<Unit> outputs) { }
    private record CondensePlan(Set<Integer> sourceSlots, List<ItemStack> outputs, int savedItems) { }
}
