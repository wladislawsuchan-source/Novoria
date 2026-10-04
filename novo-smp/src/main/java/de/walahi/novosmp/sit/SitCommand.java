package de.walahi.novosmp.sit;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Toggle command; gameplay and cleanup live in SitManager. */
public final class SitCommand extends BaseCommand {
    private final SitManager seats;

    public SitCommand(NovoSMPPlugin plugin, SitManager seats) {
        super(plugin);
        this.seats = seats;
    }

    @Override protected String permission() { return null; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player))
            return messageOrDefault(sender, "sit.messages.player-only", "<red>Nur Spieler können /sit nutzen.</red>");
        if (args.length != 0)
            return messageOrDefault(sender, "sit.messages.usage", "<gray>Benutzung: <yellow>/sit</yellow></gray>");
        return switch (seats.toggle(player)) {
            case SAT -> messageOrDefault(player, "sit.messages.sitting", "<green>Du sitzt jetzt.</green>");
            case STOOD -> messageOrDefault(player, "sit.messages.stood", "<gray>Du bist wieder aufgestanden.</gray>");
            case COMBAT -> messageOrDefault(player, "sit.messages.combat", "<red>Du kannst dich im Kampf nicht hinsetzen.</red>");
            case INVALID -> messageOrDefault(player, "sit.messages.invalid", "<red>Du kannst dich hier nicht hinsetzen.</red>");
        };
    }
}
