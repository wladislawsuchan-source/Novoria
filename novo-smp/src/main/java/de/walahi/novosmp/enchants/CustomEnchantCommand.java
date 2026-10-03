package de.walahi.novosmp.enchants;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Setzt oder entfernt eine registrierte Custom-Verzauberung auf dem Item in der Haupthand. */
public final class CustomEnchantCommand extends BaseCommand {
    private final CustomEnchantmentService enchantments;
    private final String enchantmentId;

    public CustomEnchantCommand(SMPCorePlugin plugin, CustomEnchantmentService enchantments,
                                String enchantmentId) {
        super(plugin);
        this.enchantments = enchantments;
        this.enchantmentId = enchantmentId;
    }

    @Override
    protected String permission() {
        return "novosmp.enchant.admin";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        CustomEnchantment enchantment = enchantments.find(enchantmentId).orElse(null);
        if (enchantment == null) {
            return configured(sender, "messages.unknown",
                    "<red>Die Verzauberung <white>%enchantment%</white> ist nicht verfügbar.</red>",
                    "%enchantment%", enchantmentId);
        }
        if (enchantment.maxLevel() == 1) {
            return executeSingleLevel(sender, label, args, enchantment);
        }
        return executeMultiLevel(sender, label, args, enchantment);
    }

    private boolean executeSingleLevel(CommandSender sender, String label, String[] args,
                                       CustomEnchantment enchantment) {
        boolean remove = args.length > 0 && isRemoveArgument(args[0]);
        boolean legacyLevel = args.length > 0 && args[0].equals("1");
        if (args.length > 2 || (args.length == 2 && !remove && !legacyLevel)) {
            return configured(sender, "messages.usage-single",
                    "<yellow>/%label% [spieler] • /%label% entfernen [spieler]</yellow>",
                    "%label%", label);
        }

        String targetName = null;
        if (remove || legacyLevel) {
            if (args.length >= 2) targetName = args[1];
        } else if (args.length >= 1) {
            targetName = args[0];
        }

        Player target = resolveTarget(sender, targetName);
        if (target == null) {
            return configured(sender, "messages.no-target",
                    "<red>Gib einen Onlinespieler an oder führe den Befehl selbst als Spieler aus.</red>");
        }

        ItemStack held = target.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return emptyHand(sender, target);

        if (remove) {
            enchantments.remove(held, enchantmentId);
            target.getInventory().setItemInMainHand(held);
            return removed(sender, enchantment, target);
        }

        CustomEnchantmentService.ApplyResult result = enchantments.applyDetailed(held, enchantmentId, 1);
        if (result != CustomEnchantmentService.ApplyResult.SUCCESS) {
            return handleApplyFailure(sender, enchantment, held, result);
        }

        target.getInventory().setItemInMainHand(held);
        return configured(sender, "messages.applied-single",
                "<green><white>%enchantment%</white> wurde auf das Item von <white>%player%</white> gesetzt.</green>",
                "%enchantment%", enchantment.displayName(),
                "%player%", target.getName());
    }

    private boolean executeMultiLevel(CommandSender sender, String label, String[] args,
                                      CustomEnchantment enchantment) {
        if (args.length == 0) {
            return configured(sender, "messages.usage",
                    "<yellow>/%label% <1-%max%|entfernen> [spieler]</yellow>",
                    "%label%", label, "%max%", String.valueOf(enchantment.maxLevel()));
        }

        Player target = resolveTarget(sender, args.length >= 2 ? args[1] : null);
        if (target == null) {
            return configured(sender, "messages.no-target",
                    "<red>Gib einen Onlinespieler an oder führe den Befehl selbst als Spieler aus.</red>");
        }

        ItemStack held = target.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return emptyHand(sender, target);

        String levelArgument = args[0].toLowerCase(Locale.ROOT);
        if (isRemoveArgument(levelArgument)) {
            enchantments.remove(held, enchantmentId);
            target.getInventory().setItemInMainHand(held);
            return removed(sender, enchantment, target);
        }

        int level;
        try {
            level = Integer.parseInt(levelArgument);
        } catch (NumberFormatException exception) {
            return invalidLevel(sender, enchantment);
        }
        if (level < 1 || level > enchantment.maxLevel()) return invalidLevel(sender, enchantment);

        CustomEnchantmentService.ApplyResult result = enchantments.applyDetailed(held, enchantmentId, level);
        if (result != CustomEnchantmentService.ApplyResult.SUCCESS) {
            return handleApplyFailure(sender, enchantment, held, result);
        }

        target.getInventory().setItemInMainHand(held);
        return configured(sender, "messages.applied",
                "<green><white>%enchantment% %level%</white> wurde auf das Item von <white>%player%</white> gesetzt.</green>",
                "%enchantment%", enchantment.displayName(),
                "%level%", CustomEnchantmentService.roman(level),
                "%player%", target.getName());
    }

    private boolean handleApplyFailure(CommandSender sender, CustomEnchantment enchantment,
                                       ItemStack held, CustomEnchantmentService.ApplyResult result) {
        if (result == CustomEnchantmentService.ApplyResult.CONFLICT) {
            String conflictName = enchantments.conflictingEnchantment(held, enchantmentId)
                    .map(CustomEnchantment::displayName)
                    .orElse("einer vorhandenen Verzauberung");
            return configured(sender, "messages.conflict",
                    "<red><white>%enchantment%</white> kann nicht mit <white>%conflict%</white> kombiniert werden.</red>",
                    "%enchantment%", enchantment.displayName(),
                    "%conflict%", conflictName);
        }
        return configured(sender, "messages.not-applicable",
                "<red><white>%enchantment%</white> passt nicht auf dieses Item.</red>",
                "%enchantment%", enchantment.displayName());
    }

    private boolean emptyHand(CommandSender sender, Player target) {
        return configured(sender, "messages.empty-hand",
                "<red><white>%player%</white> hält nichts in der Hand.</red>",
                "%player%", target.getName());
    }

    private boolean removed(CommandSender sender, CustomEnchantment enchantment, Player target) {
        return configured(sender, "messages.removed",
                "<green><white>%enchantment%</white> wurde vom Item von <white>%player%</white> entfernt.</green>",
                "%enchantment%", enchantment.displayName(),
                "%player%", target.getName());
    }

    private boolean invalidLevel(CommandSender sender, CustomEnchantment enchantment) {
        return configured(sender, "messages.invalid-level",
                "<red>Die Stufe muss zwischen 1 und %max% liegen.</red>",
                "%max%", String.valueOf(enchantment.maxLevel()));
    }

    private boolean configured(CommandSender sender, String path, String fallback, String... replacements) {
        FileConfiguration source = plugin.configs().customEnchants();
        return plugin.messages().sendConfiguredAuto(sender, source, path, fallback, replacements);
    }

    private Player resolveTarget(CommandSender sender, String targetName) {
        if (targetName != null && !targetName.isBlank()) return Bukkit.getPlayerExact(targetName);
        return sender instanceof Player player ? player : null;
    }

    private boolean isRemoveArgument(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.equals("entfernen") || normalized.equals("remove") || normalized.equals("0");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();

        CustomEnchantment enchantment = enchantments.find(enchantmentId).orElse(null);
        if (enchantment == null) return List.of();

        if (enchantment.maxLevel() == 1) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>();
                options.add("entfernen");
                Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
                return filtered(options, args[0]);
            }
            if (args.length == 2 && isRemoveArgument(args[0])) {
                return filtered(onlinePlayerNames(), args[1]);
            }
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (int level = 1; level <= enchantment.maxLevel(); level++) options.add(String.valueOf(level));
            options.add("entfernen");
            return filtered(options, args[0]);
        }
        if (args.length == 2) return filtered(onlinePlayerNames(), args[1]);
        return List.of();
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }

    private List<String> filtered(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(option);
        }
        return result;
    }
}
