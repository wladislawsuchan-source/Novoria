package de.walahi.novosmp.commands;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.chatevent.ChatEventManager;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ChatEventCommand extends BaseCommand {
    private final ChatEventManager manager;

    public ChatEventCommand(NovoSMPPlugin plugin, ChatEventManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.chatevent.admin";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        String action = args.length == 0 ? "start" : args[0].toLowerCase();
        switch (action) {
            case "start" -> sender.sendMessage(manager.startRandomEvent()
                    ? "§aChat-Event gestartet." : "§cEs konnte kein Chat-Event gestartet werden.");
            case "stop", "skip" -> sender.sendMessage(manager.stopCurrent(false)
                    ? "§aChat-Event beendet." : "§cAktuell läuft kein Chat-Event.");
            case "reload" -> {
                plugin.reloadConfig();
                manager.reload();
                sender.sendMessage("§aChat-Event-System neu geladen.");
            }
            default -> sender.sendMessage("§cVerwendung: /chatevent <start|stop|skip|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(permission())) return List.of();
        if (args.length == 1) return List.of("start", "stop", "skip", "reload");
        return List.of();
    }
}
