package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.novosmp.enderchest.EnderChestUpgradeMenu;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Reliable command entry point intended for Citizens NPC commands. */
public final class EnderChestUpgradeCommand extends BaseCommand {
    private final EnderChestUpgradeMenu menu;

    public EnderChestUpgradeCommand(SMPCorePlugin plugin, EnderChestUpgradeMenu menu) {
        super(plugin);
        this.menu = menu;
    }

    @Override
    protected String permission() {
        return "smpcore.ecupgrade.use";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "enderchest-upgrades.messages.player-only", "<red>Nur Spieler können dieses Menü öffnen.</red>");
        }
        menu.open(player);
        return true;
    }
}
