package de.walahi.novosmp.angler;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AnglerCommand extends BaseCommand {
    private final AnglerFeature feature;
    private final boolean storage;

    public AnglerCommand(SMPCorePlugin plugin, AnglerFeature feature, boolean storage) {
        super(plugin);
        this.feature = feature;
        this.storage = storage;
    }

    @Override protected String permission() { return "smpcore.professions.use"; }

    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return plugin.messages().sendConfiguredAuto(sender, plugin.configs().angler(), "messages.player-only",
                    "<red>Dieser Befehl ist nur für Spieler.</red>");
        }
        if (storage) feature.openStorage(player);
        else feature.openFish(player);
        return true;
    }
}
