package de.walahi.novosmp.commands.admin;

import de.walahi.novosmp.feature.PerformanceCleanupManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;

public final class PerformanceClearCommand extends BaseCommand {
    private final PerformanceCleanupManager cleanupManager;

    public PerformanceClearCommand(SMPCorePlugin plugin, PerformanceCleanupManager cleanupManager) {
        super(plugin);
        this.cleanupManager = cleanupManager;
    }

    @Override protected String permission() { return "smpcore.admin.entityclear"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 0) {
            sender.sendRichMessage("<dark_gray>[<red>Team</red>]</dark_gray> <gray>Benutzung: <yellow>/entityclear</yellow></gray>");
            return true;
        }
        PerformanceCleanupManager.CleanupResult result = cleanupManager.runCleanup(false);
        sender.sendRichMessage("<dark_gray>[<red>Team</red>]</dark_gray> <green>EntityClear abgeschlossen:</green> "
                + "<gray>Gescannt: <white>" + result.scanned() + "</white> • Entfernt: <yellow>" + result.removed()
                + "</yellow> • Geschützt: <aqua>" + result.protectedEntities() + "</aqua></gray>");
        sender.sendRichMessage("<dark_gray>Details:</dark_gray> <gray>Drops <white>" + result.drops()
                + "</white>, XP <white>" + result.experience() + "</white>, Monster <white>" + result.monsters()
                + "</white>, Tiere <white>" + result.animals() + "</white>, Wasser/Ambient <white>"
                + result.waterAmbient() + "</white>, Projektile <white>" + result.projectiles()
                + "</white>, temporär <white>" + result.temporaryEntities() + "</white>, sonstige Mobs <white>"
                + result.otherMobs() + "</white>.</gray>");
        return true;
    }
}
