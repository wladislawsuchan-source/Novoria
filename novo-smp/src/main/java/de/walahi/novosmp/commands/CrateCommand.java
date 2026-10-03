package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CrateCommand extends BaseCommand {
    private final CrateManager crates;
    public CrateCommand(SMPCorePlugin plugin, CrateManager crates) { super(plugin); this.crates = crates; }
    @Override protected String permission() { return null; }

    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) return help(sender);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "keys" -> keys(sender, args);
            case "givekey" -> giveKey(sender, args);
            case "takekey" -> takeKey(sender, args);
            case "set" -> setCrate(sender, args);
            case "remove" -> removeCrate(sender);
            case "reload" -> reload(sender);
            default -> help(sender);
        };
    }

    private boolean keys(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("smpcore.crate.admin")) return noPermission(sender);
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) return fail(sender, "Spieler ist nicht online.");
        } else if (sender instanceof Player player) target = player;
        else return fail(sender, "Nutze /crate keys <Spieler>.");
        sender.sendRichMessage("<gold>Keys von " + target.getName() + ":</gold>");
        for (CrateDefinition crate : crates.all()) sender.sendRichMessage("<gray>- <white>" + crate.displayName() + ": <yellow>" + crates.keys().get(target, crate.id()));
        return true;
    }

    private boolean giveKey(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.crate.admin")) return noPermission(sender);
        if (args.length < 4) return fail(sender, "Nutze /crate givekey <Spieler|all> <Kiste> <Anzahl>.");
        CrateDefinition crate = crates.find(args[2]).orElse(null);
        Integer amount = positive(args[3]);
        if (crate == null || amount == null) return fail(sender, "Ungültige Kiste oder Anzahl.");
        if (args[1].equalsIgnoreCase("all")) {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) { crates.keys().add(player, crate, amount); count++; }
            sender.sendRichMessage("<green>" + amount + "x " + crate.displayName() + "-Key an " + count + " Spieler vergeben.</green>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return fail(sender, "Der Spieler muss online sein, da Keys echte Items sind.");
        crates.keys().add(target, crate, amount);
        sender.sendRichMessage("<green>" + amount + "x " + crate.displayName() + "-Key an " + target.getName() + " vergeben.</green>");
        return true;
    }

    private boolean takeKey(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.crate.admin")) return noPermission(sender);
        if (args.length < 4) return fail(sender, "Nutze /crate takekey <Spieler> <Kiste> <Anzahl>.");
        CrateDefinition crate = crates.find(args[2]).orElse(null);
        Integer amount = positive(args[3]);
        Player target = Bukkit.getPlayerExact(args[1]);
        if (crate == null || amount == null || target == null) return fail(sender, "Ungültige Eingabe oder Spieler ist offline.");
        if (!crates.keys().take(target, crate.id(), amount)) return fail(sender, "Der Spieler besitzt nicht genügend Keys.");
        sender.sendRichMessage("<green>Keys entfernt.</green>");
        return true;
    }


    private boolean setCrate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.crate.admin")) return noPermission(sender);
        if (!(sender instanceof Player player)) return fail(sender, "Nur für Spieler.");
        if (args.length < 2) return fail(sender, "Nutze /crate set <Kiste> und schaue einen Block an.");
        CrateDefinition crate = crates.find(args[1]).orElse(null);
        if (crate == null) return fail(sender, "Unbekannte Kiste.");
        org.bukkit.block.Block block = player.getTargetBlockExact(6);
        if (block == null || !block.getType().name().endsWith("SHULKER_BOX"))
            return fail(sender, "Du musst eine Shulkerbox ansehen.");
        if (!crates.setCrate(block.getLocation(), crate.id()))
            return fail(sender, "Die Crate konnte nicht gespeichert werden.");
        sender.sendRichMessage("<green>Der Block wurde als <white>" + crate.displayName() + "</white> gesetzt.</green>");
        return true;
    }

    private boolean removeCrate(CommandSender sender) {
        if (!sender.hasPermission("smpcore.crate.admin")) return noPermission(sender);
        if (!(sender instanceof Player player)) return fail(sender, "Nur für Spieler.");
        org.bukkit.block.Block block = player.getTargetBlockExact(6);
        if (block == null || !crates.removeCrate(block.getLocation())) return fail(sender, "Der angesehene Block ist keine Crate.");
        sender.sendRichMessage("<green>Crate-Verknüpfung entfernt.</green>");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("smpcore.crate.admin")) return noPermission(sender);
        crates.reload();
        sender.sendRichMessage("<green>Crates und Keys neu geladen.</green>");
        return true;
    }

    private boolean help(CommandSender sender) {
        sender.sendRichMessage("<gold>/crate keys [Spieler]</gold> <gray>– Keys anzeigen</gray>");
        sender.sendRichMessage("<gray>Kisten werden ausschließlich per Rechtsklick auf die physische Crate geöffnet.</gray>");
        if (sender.hasPermission("smpcore.crate.admin")) {
            sender.sendRichMessage("<gold>/crate givekey <Spieler|all> <Kiste> <Anzahl></gold>");
            sender.sendRichMessage("<gold>/crate set <Kiste></gold> <gray>– angesehenen Block als Crate setzen</gray>");
            sender.sendRichMessage("<gold>/crate remove</gold> <gray>– Crate-Verknüpfung entfernen</gray>");
        }
        return true;
    }

    private boolean noPermission(CommandSender sender) { return fail(sender, "Dafür hast du keine Berechtigung."); }
    private boolean fail(CommandSender sender, String text) { sender.sendRichMessage("<dark_gray>[<gold>Kiste</gold>]</dark_gray> <red>" + text + "</red>"); return true; }
    private Integer positive(String raw) { try { int value = Integer.parseInt(raw); return value > 0 ? value : null; } catch (NumberFormatException exception) { return null; } }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subcommands = sender.hasPermission("smpcore.crate.admin")
                    ? List.of("keys", "givekey", "takekey", "set", "remove", "reload")
                    : List.of("keys");
            return filter(subcommands, args[0]);
        }
        if (!sender.hasPermission("smpcore.crate.admin") && args.length >= 2
                && !args[0].equalsIgnoreCase("keys")) return List.of();
        if (args.length == 2 && List.of("givekey", "takekey", "keys").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            if (args[0].equalsIgnoreCase("givekey")) names.add("all");
            return filter(names, args[1]);
        }
        if ((args.length == 2 && args[0].equalsIgnoreCase("set")) ||
                (args.length == 3 && List.of("givekey", "takekey").contains(args[0].toLowerCase(Locale.ROOT))))
            return filter(new ArrayList<>(crates.ids()), args[args.length - 1]);
        return List.of();
    }
    private List<String> filter(List<String> values, String prefix) { String p = prefix.toLowerCase(Locale.ROOT); return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList(); }
}
