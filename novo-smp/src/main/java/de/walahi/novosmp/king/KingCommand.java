package de.walahi.novosmp.king;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public final class KingCommand extends BaseCommand {
    private final DragonEggKingService kings;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<String, Long> resetConfirmations = new HashMap<>();
    public KingCommand(NovoSMPPlugin plugin, DragonEggKingService kings) { super(plugin); this.kings = kings; }
    @Override protected String permission() { return KingPermissions.USE; }

    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) { overview(sender); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("duel")) {
            if (!(sender instanceof Player player)) { send(sender, "<red>Nur für Spieler.</red>"); return true; }
            if (!sender.hasPermission(KingPermissions.DUEL)) { noPermission(sender); return true; }
            kings.challenge(player); return true;
        }
        if (!sender.hasPermission(KingPermissions.ADMIN)) { noPermission(sender); return true; }
        if (action.equals("locate")) { send(sender, kings.locateDescription()); return true; }
        if (action.equals("reset")) {
            String key = sender instanceof Player player ? player.getUniqueId().toString() : "CONSOLE";
            if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")
                    || resetConfirmations.getOrDefault(key, 0L) < System.currentTimeMillis()) {
                resetConfirmations.put(key, System.currentTimeMillis() + 30_000L);
                send(sender, "<red>Achtung:</red> <yellow>Dies entwertet das bisherige King-Ei. Bestätige binnen 30 Sekunden mit <white>/king reset confirm</white>.</yellow>");
                return true;
            }
            resetConfirmations.remove(key);
            send(sender, kings.resetToPortal()
                    ? "<green>Das kanonische Ei wurde eindeutig an die End-Portalposition zurückgesetzt.</green>"
                    : "<red>Reset fehlgeschlagen: Welt oder End-Portalposition nicht verfügbar.</red>"); return true;
        }
        if (action.equals("setholder") && args.length == 2) {
            Player target = Bukkit.getPlayerExact(args[1]);
            send(sender, target != null && kings.setHolder(target)
                    ? "<green>Das kanonische Ei wurde sicher an " + esc(target.getName()) + " übertragen.</green>"
                    : "<red>Spieler nicht online oder kein freier Inventarplatz.</red>"); return true;
        }
        send(sender, "<yellow>/king [duel|locate|reset|setholder &lt;Spieler&gt;]</yellow>"); return true;
    }

    private void overview(CommandSender sender) {
        DragonEggKingState state = kings.state();
        if (state == null) { send(sender, "<red>Das King-System ist nicht geladen.</red>"); return; }
        if (state.kind() == KingLocationKind.PLAYER) {
            send(sender, "<gray>Aktueller King:</gray> <yellow>" + esc(state.holderName()) + "</yellow>");
            send(sender, "<gray>Ei:</gray> <green>Aktiv im Inventar</green>");
            send(sender, "<gray>Pflichtduelle:</gray> <yellow>" + kings.mandatoryRemaining() + " / " + kings.mandatoryTotal() + "</yellow> <gray>verbleibend</gray>");
        } else if (state.kind() == KingLocationKind.ENDER_CHEST || state.kind() == KingLocationKind.CONTAINER || state.kind() == KingLocationKind.CLAN_CHEST) {
            send(sender, "<yellow>Das Drachenei ist aktuell eingelagert.</yellow>");
        } else send(sender, "<yellow>Das Drachenei befindet sich aktuell nicht im Inventar eines Spielers.</yellow>");
    }
    private void noPermission(CommandSender sender) { send(sender, "<red>Dafür hast du keine Berechtigung.</red>"); }
    private void send(CommandSender sender, String message) { sender.sendMessage(mm.deserialize("<dark_gray>[<gold>King</gold>]</dark_gray> " + message)); }
    private String esc(String value) { return value == null ? "Unbekannt" : value.replace("<", "\\<").replace(">", "\\>"); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("duel"));
            if (sender.hasPermission(KingPermissions.ADMIN)) values.addAll(List.of("locate", "reset", "setholder"));
            return match(values, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setholder") && sender.hasPermission(KingPermissions.ADMIN))
            return match(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("reset") && sender.hasPermission(KingPermissions.ADMIN)) return match(List.of("confirm"), args[1]);
        return List.of();
    }
    private List<String> match(List<String> values, String prefix) { String p=prefix.toLowerCase(Locale.ROOT);return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList(); }
}
