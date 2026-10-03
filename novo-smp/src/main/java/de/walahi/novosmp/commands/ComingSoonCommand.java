package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;

/**
 * Gemeinsamer Platzhalter für noch nicht veröffentlichte Novoria-Systeme.
 */
public final class ComingSoonCommand extends BaseCommand {
    private final String permission;

    public ComingSoonCommand(SMPCorePlugin plugin, String permission) {
        super(plugin);
        this.permission = permission;
    }

    @Override
    protected String permission() {
        return permission;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        return messageOrDefault(
                sender,
                "placeholder-commands.message",
                "<gold><bold>Novoria</bold></gold> <dark_gray>»</dark_gray> <yellow>Wird noch hinzugefügt.</yellow>"
        );
    }
}
