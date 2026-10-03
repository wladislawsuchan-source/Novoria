package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DuelAdminCommand extends BaseCommand {
    private final DuelManager manager;

    public DuelAdminCommand(NovoSMPPlugin plugin, DuelManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @Override protected String permission() { return DuelPermissions.ADMIN; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("reload")) {
            manager.config().reload();
            manager.refreshDuelWorldSettings();
            feedback(sender, msg("admin.reloaded", "§aDuell-Konfiguration neu geladen."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            feedback(sender, msg("admin.player-only", "Kit- und Map-Befehle können nur von Spielern verwendet werden."));
            return true;
        }
        if (root.equals("kit")) return kit(player, args);
        if (root.equals("map")) return map(player, args);
        return usage(sender);
    }

    private boolean kit(Player player, String[] args) {
        if (args.length < 2) return usage(player);
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            String kits = String.join(", ", manager.config().kits().keySet());
            feedback(player, msg("admin.kits-list", "§eKits: §f%kits%", "%kits%",
                    kits.isBlank() ? manager.config().text("formats.none", "keine") : kits));
            return true;
        }
        if (args.length < 3) return usage(player);
        String id = DuelConfig.normalize(args[2]);
        if (id.isBlank()) {
            feedback(player, msg("admin.invalid-kit-name", "§cUngültiger Kit-Name."));
            return true;
        }
        switch (action) {
            case "create" -> {
                if (manager.config().kit(id) != null) {
                    feedback(player, msg("admin.kit-exists", "§cDieses Kit existiert bereits."));
                    return true;
                }
                Material icon = heldIcon(player, Material.IRON_SWORD);
                manager.config().saveKit(id, args[2], icon, DuelLoadout.capture(player));
                feedback(player, msg("admin.kit-created", "§aKit §f%kit% §aaus deinem aktuellen Inventar erstellt.", "%kit%", id));
            }
            case "edit" -> manager.editor().openAdminEditor(player, id);
            case "delete" -> feedback(player, manager.config().deleteKit(id) ? msg("admin.kit-deleted", "§aKit gelöscht.") : msg("admin.kit-not-found", "§cKit nicht gefunden."));
            case "seticon" -> {
                DuelKit kit = manager.config().kit(id);
                if (kit == null) feedback(player, msg("admin.kit-not-found", "§cKit nicht gefunden."));
                else {
                    manager.config().updateKitIcon(kit.id(), heldIcon(player, kit.icon()));
                    feedback(player, msg("admin.kit-icon-updated", "§aKit-Icon aktualisiert."));
                }
            }
            default -> usage(player);
        }
        return true;
    }

    private boolean map(Player player, String[] args) {
        if (args.length < 2) return usage(player);
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            List<String> entries = new ArrayList<>();
            for (DuelMap map : manager.config().maps().values()) {
                entries.add(map.id() + " (" + manager.config().text("formats.arena-count", "%count% Arenen",
                        "%count%", Integer.toString(manager.config().arenaCount(map.id()))) + ")");
            }
            feedback(player, msg("admin.maps-list", "§eMaps: §f%maps%", "%maps%",
                    entries.isEmpty() ? manager.config().text("formats.none", "keine") : String.join(", ", entries)));
            return true;
        }
        if (args.length < 3) return usage(player);
        String id = DuelConfig.normalize(args[2]);
        if (id.isBlank()) {
            feedback(player, msg("admin.invalid-map-name", "§cUngültiger Map-Name."));
            return true;
        }

        switch (action) {
            case "create" -> {
                if (manager.config().map(id) != null) {
                    feedback(player, msg("admin.map-exists", "§cDiese Map existiert bereits."));
                    return true;
                }
                try {
                    DuelMap map = manager.arenas().capture(player, id, args[2], heldIcon(player, Material.GRASS_BLOCK));
                    DuelMap overlap = manager.config().allArenas().stream().filter(map::overlaps).findFirst().orElse(null);
                    if (overlap != null) {
                        File schematic = manager.arenas().schematicFile(map);
                        if (schematic.isFile() && !schematic.delete()) {
                            plugin.getLogger().warning("Ungültiges Arena-Schematic konnte nicht gelöscht werden: " + schematic);
                        }
                        feedback(player, msg("admin.map-overlap",
                                "§cDie Auswahl überschneidet sich mit der Duell-Arena §f%arena%§c.",
                                "%arena%", overlap.id()));
                        return true;
                    }
                    manager.config().saveMap(map);
                    manager.refreshDuelWorldSettings();
                    feedback(player, msg("admin.map-created", "§aMap §f%map% §aaus deiner WorldEdit-Auswahl gespeichert.", "%map%", id));
                    feedback(player, msg("admin.map-spawn-hint",
                            "§eSetze jetzt /dueladmin map setspawn1 %map% und setspawn2.", "%map%", id));
                } catch (Exception exception) {
                    feedback(player, msg("admin.map-create-failed", "§cMap konnte nicht erstellt werden: %error%", "%error%", escape(exception.getMessage())));
                }
            }
            case "setspawn1", "setspawn2" -> {
                DuelMap map = manager.config().map(id);
                if (map == null) {
                    feedback(player, msg("admin.map-not-found", "§cMap nicht gefunden."));
                    return true;
                }
                if (!player.getWorld().getName().equalsIgnoreCase(map.worldName()) || !map.contains(player.getLocation())) {
                    feedback(player, msg("admin.spawn-outside", "§cDer Spawn muss innerhalb der ursprünglichen Arena-Auswahl liegen."));
                    return true;
                }
                DuelMap updated = new DuelMap(map.id(), map.displayName(), map.icon(), map.worldName(),
                        map.minX(), map.minY(), map.minZ(), map.maxX(), map.maxY(), map.maxZ(),
                        action.equals("setspawn1") ? player.getLocation().clone() : map.spawnOne(),
                        action.equals("setspawn2") ? player.getLocation().clone() : map.spawnTwo(),
                        map.schematicFile());
                manager.config().saveMap(updated);
                feedback(player, msg("admin.spawn-set", "§a%spawn% für §f%map% §agesetzt.", "%spawn%", action, "%map%", id));
            }
            case "addinstance" -> {
                if (args.length < 4) return usage(player);
                DuelMap original = manager.config().map(id);
                if (original == null) {
                    feedback(player, msg("admin.map-not-found", "§cMap nicht gefunden."));
                    return true;
                }
                if (!original.ready() || original.spawnOne() == null) {
                    feedback(player, msg("admin.original-not-ready",
                            "§cRichte zuerst Spawn 1 und Spawn 2 der ursprünglichen Map vollständig ein."));
                    return true;
                }
                String instanceId = DuelConfig.normalize(args[3]);
                if (instanceId.isBlank() || manager.config().arena(id, instanceId) != null) {
                    feedback(player, msg("admin.instance-name-invalid",
                            "§cDieser Instanzname ist ungültig, reserviert oder bereits vergeben."));
                    return true;
                }

                // Der Spieler steht am entsprechenden Spawn-1-Punkt der kopierten Arena.
                // Der ganzzahlige WorldEdit-Versatz wird daraus nur einmal berechnet und gespeichert.
                int offsetX = player.getLocation().getBlockX() - original.spawnOne().getBlockX();
                int offsetY = player.getLocation().getBlockY() - original.spawnOne().getBlockY();
                int offsetZ = player.getLocation().getBlockZ() - original.spawnOne().getBlockZ();
                DuelMap instance;
                try {
                    instance = manager.config().previewInstance(id, instanceId, player.getWorld().getName(),
                            offsetX, offsetY, offsetZ);
                } catch (IllegalArgumentException exception) {
                    feedback(player, msg("admin.instance-name-invalid-short", "§cUngültiger Instanzname."));
                    return true;
                }
                if (instance == null) {
                    feedback(player, msg("admin.instance-calc-failed", "§cInstanz konnte nicht berechnet werden."));
                    return true;
                }
                if (instance.minY() < player.getWorld().getMinHeight()
                        || instance.maxY() >= player.getWorld().getMaxHeight()) {
                    feedback(player, msg("admin.instance-height-invalid", "§cDie berechnete Arena liegt außerhalb der Welthöhe."));
                    return true;
                }
                DuelMap overlap = manager.config().allArenas().stream().filter(instance::overlaps).findFirst().orElse(null);
                if (overlap != null) {
                    feedback(player, msg("admin.instance-overlap",
                            "§cDie berechnete Instanz überschneidet sich mit §f%arena%§c.",
                            "%arena%", overlap.id()));
                    return true;
                }
                if (!manager.config().saveInstance(id, instanceId, player.getWorld().getName(), offsetX, offsetY, offsetZ)) {
                    feedback(player, msg("admin.instance-save-failed", "§cInstanz konnte nicht gespeichert werden."));
                    return true;
                }
                manager.refreshDuelWorldSettings();
                feedback(player, msg("admin.instance-added",
                        "§aInstanz §f%instance% §afür Map §f%map% §ahinzugefügt.",
                        "%instance%", instanceId, "%map%", id));
                feedback(player, msg("admin.instance-offset-info",
                        "§7Umriss und beide Spawns wurden automatisch über Spawn 1 verschoben."));
            }
            case "listinstances" -> {
                if (manager.config().map(id) == null) {
                    feedback(player, msg("admin.map-not-found", "§cMap nicht gefunden."));
                    return true;
                }
                feedback(player, msg("admin.instances-list", "§eArenen von §f%map%§e: §f%instances%",
                        "%map%", id, "%instances%", String.join(", ", manager.config().instanceIds(id))));
            }
            case "deleteinstance" -> {
                if (args.length < 4) return usage(player);
                String instanceId = DuelConfig.normalize(args[3]);
                DuelMap instance = manager.config().arena(id, instanceId);
                if (instance == null || instance.id().equals(id)) {
                    feedback(player, msg("admin.instance-not-found",
                            "§cInstanz nicht gefunden. Die Basis-Arena wird über 'map delete' gelöscht."));
                    return true;
                }
                if (manager.arenaBusy(instance)) {
                    feedback(player, msg("admin.instance-busy", "§cDiese Instanz wird gerade verwendet oder zurückgesetzt."));
                    return true;
                }
                feedback(player, manager.config().deleteInstance(id, instanceId)
                        ? msg("admin.instance-deleted", "§aInstanz gelöscht.")
                        : msg("admin.instance-not-found", "§cInstanz nicht gefunden."));
            }
            case "seticon" -> {
                DuelMap map = manager.config().map(id);
                if (map == null) feedback(player, msg("admin.map-not-found", "§cMap nicht gefunden."));
                else {
                    manager.config().saveMap(new DuelMap(map.id(), map.displayName(), heldIcon(player, map.icon()), map.worldName(),
                            map.minX(), map.minY(), map.minZ(), map.maxX(), map.maxY(), map.maxZ(),
                            map.spawnOne(), map.spawnTwo(), map.schematicFile()));
                    feedback(player, msg("admin.map-icon-updated", "§aMap-Icon aktualisiert."));
                }
            }
            case "reset" -> {
                DuelMap arena = args.length >= 4 ? manager.config().arena(id, args[3]) : manager.config().map(id);
                manager.resetArena(player, arena);
            }
            case "delete" -> {
                if (manager.mapBusy(id)) {
                    feedback(player, msg("admin.map-busy",
                            "§cMindestens eine Arena dieser Map wird gerade verwendet oder zurückgesetzt."));
                    return true;
                }
                DuelMap map = manager.config().map(id);
                boolean deleted = manager.config().deleteMap(id);
                if (deleted && map != null) {
                    File schematic = manager.arenas().schematicFile(map);
                    if (schematic.isFile() && !schematic.delete()) {
                        plugin.getLogger().warning("Schematic konnte nicht gelöscht werden: " + schematic);
                    }
                }
                feedback(player, deleted ? msg("admin.map-deleted", "§aMap samt Instanzen gelöscht.") : msg("admin.map-not-found", "§cMap nicht gefunden."));
            }
            default -> usage(player);
        }
        return true;
    }

    private String msg(String key, String fallback, String... replacements) {
        return manager.config().message(key, fallback, replacements);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>");
    }

    private void feedback(CommandSender sender, String message) {
        DuelMessages.send(manager.config(), sender, message);
        if (!(sender instanceof Player player) || message == null) return;
        if (message.startsWith("§c") || message.startsWith("<red>")) {
            manager.sounds().play(player, "error");
        } else if (message.startsWith("§a") || message.startsWith("<green>")) {
            String plain = message.toLowerCase(Locale.ROOT);
            manager.sounds().play(player, plain.contains("gelöscht") ? "admin-delete" : "admin-success");
        }
    }

    private Material heldIcon(Player player, Material fallback) {
        ItemStack item = player.getInventory().getItemInMainHand();
        return item == null || item.getType().isAir() || !item.getType().isItem() ? fallback : item.getType();
    }

    private boolean usage(CommandSender sender) {
        feedback(sender, msg("admin.usage.kit", "§e/dueladmin kit create|edit|delete|seticon|list <Name>"));
        feedback(sender, msg("admin.usage.map", "§e/dueladmin map create|setspawn1|setspawn2|seticon|reset|delete|list <Map>"));
        feedback(sender, msg("admin.usage.add-instance", "§e/dueladmin map addinstance <Map> <Instanz> §7(am Spawn 1 der Kopie stehen)"));
        feedback(sender, msg("admin.usage.list-instances", "§e/dueladmin map listinstances <Map>"));
        feedback(sender, msg("admin.usage.delete-instance", "§e/dueladmin map deleteinstance <Map> <Instanz>"));
        feedback(sender, msg("admin.usage.reload", "§e/dueladmin reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("kit", "map", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("kit")) return filter(List.of("create", "edit", "delete", "seticon", "list"), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("map")) return filter(List.of(
                "create", "setspawn1", "setspawn2", "addinstance", "listinstances", "deleteinstance",
                "seticon", "reset", "delete", "list"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("kit") && !args[1].equalsIgnoreCase("create")) return filter(new ArrayList<>(manager.config().kits().keySet()), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("map") && !args[1].equalsIgnoreCase("create")) {
            return filter(new ArrayList<>(manager.config().maps().keySet()), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("map")
                && (args[1].equalsIgnoreCase("deleteinstance") || args[1].equalsIgnoreCase("reset"))) {
            return filter(manager.config().instanceIds(args[2]), args[3]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
