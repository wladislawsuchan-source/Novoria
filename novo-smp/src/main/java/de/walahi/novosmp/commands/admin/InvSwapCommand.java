package de.walahi.novosmp.commands.admin;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.duel.DuelManager;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Tauscht das vollständige tragbare Inventar zweier Online-Spieler atomar aus. */
public final class InvSwapCommand extends BaseCommand {
    private final DuelManager duelManager;

    public InvSwapCommand(NovoSMPPlugin plugin, DuelManager duelManager) {
        super(plugin);
        this.duelManager = duelManager;
    }

    @Override
    protected String permission() {
        return "smpcore.admin.invswap";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cNur Spieler können /invswap verwenden.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("§cBenutzung: /invswap <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage("§cDieser Spieler ist nicht online.");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage("§cDu kannst dein Inventar nicht mit dir selbst tauschen.");
            return true;
        }
        if (duelManager.inDuel(player.getUniqueId()) || duelManager.inDuel(target.getUniqueId())) {
            player.sendMessage("§cInventare können während eines Duells nicht getauscht werden.");
            return true;
        }

        // Offene Menüs zuerst sauber schließen. Ein verbliebenes Cursor-Item würde nicht zum
        // PlayerInventory gehören und könnte sonst beim Tausch verloren gehen oder duplizieren.
        player.closeInventory();
        target.closeInventory();
        if (hasCursorItem(player) || hasCursorItem(target)) {
            player.sendMessage("§cTausch abgebrochen: Einer von euch hält noch ein Item am Mauszeiger.");
            return true;
        }

        InventorySnapshot playerInventory = InventorySnapshot.capture(player);
        InventorySnapshot targetInventory = InventorySnapshot.capture(target);
        try {
            targetInventory.apply(player);
            playerInventory.apply(target);
        } catch (RuntimeException exception) {
            // Beide Seiten auf den vorherigen Stand zurücksetzen, falls ein unerwarteter Fehler auftritt.
            playerInventory.apply(player);
            targetInventory.apply(target);
            plugin.getLogger().warning("/invswap zwischen " + player.getName() + " und "
                    + target.getName() + " fehlgeschlagen: " + exception.getMessage());
            player.sendMessage("§cDer Inventartausch ist fehlgeschlagen; beide Inventare wurden wiederhergestellt.");
            return true;
        }

        player.updateInventory();
        target.updateInventory();
        player.sendMessage("§aDu hast dein Inventar mit §f" + target.getName() + " §agetauscht.");
        target.sendMessage("§e" + player.getName() + " §7hat euer Inventar getauscht.");
        plugin.getLogger().info(player.getName() + " hat sein Inventar per /invswap mit "
                + target.getName() + " getauscht.");
        return true;
    }

    private boolean hasCursorItem(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor != null && !cursor.getType().isAir() && cursor.getAmount() > 0;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player executor)
                        || !player.getUniqueId().equals(executor.getUniqueId()))
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }

    private record InventorySnapshot(ItemStack[] storage, ItemStack[] armor, ItemStack offHand) {
        private static InventorySnapshot capture(Player player) {
            PlayerInventory inventory = player.getInventory();
            return new InventorySnapshot(
                    cloneItems(inventory.getStorageContents()),
                    cloneItems(inventory.getArmorContents()),
                    cloneItem(inventory.getItemInOffHand())
            );
        }

        private void apply(Player player) {
            PlayerInventory inventory = player.getInventory();
            inventory.clear();
            inventory.setStorageContents(cloneItems(storage));
            inventory.setArmorContents(cloneItems(armor));
            inventory.setItemInOffHand(cloneItem(offHand));
        }

        private static ItemStack[] cloneItems(ItemStack[] items) {
            return Arrays.stream(items).map(InventorySnapshot::cloneItem).toArray(ItemStack[]::new);
        }

        private static ItemStack cloneItem(ItemStack item) {
            return item == null ? null : item.clone();
        }
    }
}
