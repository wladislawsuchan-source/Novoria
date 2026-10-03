package de.walahi.novosmp.commands.admin;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.chestlog.ChestLogManager;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class ChestLogCommand extends BaseCommand {
    private final ChestLogManager manager;

    public ChestLogCommand(NovoSMPPlugin plugin, ChestLogManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.admin.chestlog";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cNur Spieler können /chestlog verwenden.");
            return true;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Container container)) {
            player.sendMessage("§cSchau eine Kiste, ein Fass oder eine Shulkerkiste an.");
            return true;
        }

        org.bukkit.Location logLocation = manager.normalizeContainerLocation(container);
        List<String> entries = manager.formattedEntries(logLocation, 20);
        player.sendMessage("§8§m---------------- §5ChestLog §8§m----------------");
        player.sendMessage("§7Ort: §f" + logLocation.getWorld().getName() + " §8| §f" + logLocation.getBlockX() + " " + logLocation.getBlockY() + " " + logLocation.getBlockZ());
        if (entries.isEmpty()) {
            player.sendMessage("§7Für diesen Container gibt es noch keine Einträge.");
        } else {
            entries.forEach(player::sendMessage);
        }
        player.sendMessage("§8§m------------------------------------------");
        return true;
    }
}
