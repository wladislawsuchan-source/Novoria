package de.walahi.novosmp.professions;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import de.walahi.smpcore.gui.MenuFormat;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compact chat view of every unfinished requirement for all active professions. */
public final class ContributionStatusCommand extends BaseCommand {
    private final ProfessionManager manager;

    public ContributionStatusCommand(SMPCorePlugin plugin, ProfessionManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override
    protected String permission() {
        return "smpcore.professions.use";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendRichMessage("<red>Dieser Befehl ist nur für Spieler.</red>");
            return true;
        }
        if (args.length > 1) {
            player.sendRichMessage("<gray>Benutzung: <yellow>/abgabe [Beruf]</yellow></gray>");
            return true;
        }

        List<String> active = new ArrayList<>(manager.state(player.getUniqueId()).activeProfessions());
        if (args.length == 1) {
            String requested = normalizeProfession(args[0]);
            if (requested == null || active.stream().noneMatch(id -> id.equalsIgnoreCase(requested))) {
                player.sendRichMessage("<red>Dieser Beruf ist bei dir nicht aktiv.</red> "
                        + "<gray>Möglich: holzfaeller, bergarbeiter, jaeger, angler</gray>");
                return true;
            }
            active.removeIf(id -> !id.equalsIgnoreCase(requested));
        }
        if (active.isEmpty()) {
            player.sendRichMessage("<dark_gray>[</dark_gray><light_purple>Abgabe</light_purple><dark_gray>]</dark_gray> "
                    + "<red>Du hast noch keinen aktiven Beruf.</red>");
            return true;
        }

        // /abgabe uses the existing contribution service: each material group removes at most
        // its still-open amount. Growth, mining and hunting objectives remain display-only.
        for (String professionId : active) {
            ProfessionProgress progress = manager.progress(player.getUniqueId(), professionId);
            int milestone = manager.pendingMilestone(progress);
            if (milestone <= 0) continue;
            MilestoneRequirement requirement = manager.config()
                    .requirement(professionId, progress.prestige(), milestone);
            if (!requirement.materials().isEmpty() || !requirement.fish().isEmpty()) {
                manager.contributeAll(player, professionId, milestone, false);
            }
        }

        player.sendRichMessage("<dark_gray>──────── </dark_gray><light_purple><bold>Offene Berufsziele</bold></light_purple><dark_gray> ────────</dark_gray>");
        for (String professionId : active) showProfession(player, professionId);
        return true;
    }

    private void showProfession(Player player, String professionId) {
        ProfessionConfig config = manager.config();
        ProfessionProgress progress = manager.progress(player.getUniqueId(), professionId);
        int milestone = manager.pendingMilestone(progress);
        String name = config.professionDisplayName(professionId);
        player.sendRichMessage("<gray>▸ </gray>" + name + " <dark_gray>• </dark_gray><gray>Prestige "
                + roman(progress.prestige()) + (milestone > 0 ? " • Ziel Level " + milestone : "") + "</gray>");
        if (milestone <= 0) {
            player.sendRichMessage("  <red>Keine Abgaben erforderlich.</red>");
            return;
        }

        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        Map<String, Long> current = manager.contributions(player, professionId, milestone);
        int shown = 0;
        for (Map.Entry<String, Long> entry : requirement.materials().entrySet()) {
            MaterialGroup group = config.materialGroup(entry.getKey());
            if (group != null && current.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                sendLine(player, "Abgeben", group.displayName(), current.getOrDefault(entry.getKey(), 0L), entry.getValue());
                shown++;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.fish().entrySet()) {
            var fish = manager.fishRegistry().find(entry.getKey());
            String key = manager.fishContributionKey(entry.getKey());
            if (fish != null && current.getOrDefault(key, 0L) < entry.getValue()) {
                sendLine(player, "Abgeben", fish.displayName(), current.getOrDefault(key, 0L), entry.getValue());
                shown++;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.growths().entrySet()) {
            GrowthGroup group = config.growthGroup(entry.getKey());
            String key = manager.growthContributionKey(entry.getKey());
            if (group != null && current.getOrDefault(key, 0L) < entry.getValue()) {
                sendLine(player, "Pflanzen", group.displayName(), current.getOrDefault(key, 0L), entry.getValue());
                shown++;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.mined().entrySet()) {
            MinedGroup group = config.minedGroup(entry.getKey());
            String key = manager.minedContributionKey(entry.getKey());
            if (group != null && current.getOrDefault(key, 0L) < entry.getValue()) {
                sendLine(player, "Selbst abbauen", group.displayName(), current.getOrDefault(key, 0L), entry.getValue());
                shown++;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.hunts().entrySet()) {
            HuntGroup group = config.huntGroup(entry.getKey());
            String key = manager.huntContributionKey(entry.getKey());
            if (group != null && current.getOrDefault(key, 0L) < entry.getValue()) {
                sendLine(player, "Jagen", group.displayName(), current.getOrDefault(key, 0L), entry.getValue());
                shown++;
            }
        }
        for (Map.Entry<String, Long> entry : requirement.skills().entrySet()) {
            if (current.getOrDefault(entry.getKey(), 0L) >= entry.getValue()) continue;
            String skillName = plugin.configs().angler().getString("gui.skills." + entry.getKey() + ".name", entry.getKey());
            sendLine(player, "Angeln", skillName, current.getOrDefault(entry.getKey(), 0L), entry.getValue());
            shown++;
        }
        if (shown == 0) player.sendRichMessage("  <red>Keine Abgaben erforderlich.</red>");
    }

    private void sendLine(Player player, String type, String display, long current, long required) {
        long missing = Math.max(0L, required - current);
        player.sendRichMessage("  <dark_gray>•</dark_gray> <gray>" + type + ": <white>" + display
                + "</white> <yellow>" + MenuFormat.integer(current) + "/" + MenuFormat.integer(required)
                + "</yellow> <dark_gray>(fehlen " + MenuFormat.integer(missing) + ")</dark_gray>");
    }

    private String normalizeProfession(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "holzfaeller", "holzfäller", "lumberjack" -> ProfessionManager.LUMBERJACK;
            case "bergarbeiter", "miner" -> ProfessionManager.MINER;
            case "jaeger", "jäger", "hunter" -> ProfessionManager.HUNTER;
            case "angler", "fischer" -> ProfessionManager.ANGLER;
            default -> null;
        };
    }

    private String roman(int value) {
        return switch (value) {
            case 0 -> "0";
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(value);
        };
    }
}
