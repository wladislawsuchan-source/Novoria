package de.walahi.novosmp.commands.gameplay;

import de.walahi.novosmp.back.DeathBackManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class StreamCommand extends BaseCommand {
    private final DeathBackManager manager;
    public StreamCommand(SMPCorePlugin plugin, DeathBackManager manager) { super(plugin); this.manager = manager; }
    @Override protected String permission() { return null; }
    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) return messageOrDefault(sender, "messages.player-only", "<red>Nur Spieler können diesen Befehl verwenden.</red>");

        List<String> allowedGroups = plugin.configs().server().getStringList("stream.allowed-groups");
        boolean rankAllowed = plugin.getCommandVisibilityManager() != null
                && plugin.getCommandVisibilityManager().hasAnyGroup(player, allowedGroups);
        if (!player.hasPermission("smpcore.command.stream") && !rankAllowed) {
            return messageOrDefault(sender, "staff.messages.no-permission",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Dafür hast du keine Berechtigung.</red>");
        }

        if (args.length != 0) { player.sendRichMessage("<gray>Benutzung: <yellow>/stream</yellow></gray>"); return true; }
        boolean enabled = manager.toggleStream(player);
        player.sendRichMessage(enabled
                ? "<dark_gray>[<light_purple>Stream</light_purple>]</dark_gray> <green>Stream-Modus aktiviert.</green> <gray>Todeskoordinaten werden nicht mehr im Chat angezeigt.</gray>"
                : "<dark_gray>[<light_purple>Stream</light_purple>]</dark_gray> <yellow>Stream-Modus deaktiviert.</yellow> <gray>Todeskoordinaten werden wieder angezeigt.</gray>");
        return true;
    }
}
