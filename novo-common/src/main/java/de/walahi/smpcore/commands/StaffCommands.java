package de.walahi.smpcore.commands;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.BlockState;
import org.bukkit.block.EnderChest;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.event.server.TabCompleteEvent;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.playerdata.NativePlayerDataAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StaffCommands implements CommandExecutor, TabCompleter, Listener {
    private final SMPCorePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Set<UUID> vanished = new HashSet<>();
    /** Private visibility only. Unlike moderation vanish, DND never changes gameplay state. */
    private final Set<UUID> dnd = new HashSet<>();
    /** Staff-Spieler, die bei Vanish-Wechseln keine Freundesmeldungen ausloesen wollen. */
    private final Set<UUID> friendNotifyDisabled = new HashSet<>();
    private final Map<UUID, FlightState> previousFlightStates = new HashMap<>();
    /**
     * Kurzlebige Zuordnung echter Container-Interaktionen von Vanish-Spielern.
     * Sie dient als zweite Schutzschicht für BLOCK_ACTION-Pakete, falls Paper den
     * Viewer-Zähler beim Senden des Pakets noch nicht aktualisiert hat.
     * Der echte Container bleibt dabei bewusst unangetastet, damit LootTables,
     * Locks und andere Plugins aus 1.53.37 weiterhin korrekt funktionieren.
     */
    private final Map<VanishBlockKey, VanishContainerInteraction> silentContainerInteractions = new ConcurrentHashMap<>();
    /**
     * Dauerhafte Vanish-Container-Sessions. Anders als der kurze Pre-Open-Guard bleiben
     * diese Eintraege exakt bis InventoryClose aktiv. Dadurch koennen auch spaete
     * BLOCK_ACTION-Pakete einer lange geoeffneten (Doppel-)Kiste keinen Vanish-Staff leaken.
     */
    private final Map<VanishBlockKey, Set<UUID>> silentContainerOpeners = new ConcurrentHashMap<>();
    private final Map<UUID, Set<VanishBlockKey>> silentContainersByActor = new ConcurrentHashMap<>();
    private final File vanishFile;
    private final File dndFile;

    public StaffCommands(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.vanishFile = new File(plugin.getDataFolder(), "vanished.yml");
        this.dndFile = new File(plugin.getDataFolder(), "dnd.yml");
        loadVanishedPlayers();
        if (plugin.isSmpServer()) loadDndPlayers();
        if (plugin.isSmpServer()) registerSilentContainerPackets();
    }

    public boolean isVanished(Player player) {
        return player != null && vanished.contains(player.getUniqueId());
    }

    public boolean isDnd(Player player) {
        return player != null && dnd.contains(player.getUniqueId());
    }

    public boolean hasOnlineDndPlayers() {
        return Bukkit.getOnlinePlayers().stream().anyMatch(this::isDnd);
    }

    public boolean isHiddenFromPlayers(Player player) {
        return isVanished(player) || isDnd(player);
    }

    /** DND is private from ordinary ranks, but Admin and Owner retain staff visibility. */
    public boolean canSeeDnd(Player viewer, Player target) {
        if (viewer == null || target == null) return false;
        if (viewer.getUniqueId().equals(target.getUniqueId())) return true;
        if (plugin.getRankManager() == null) return false;
        String rank = plugin.getRankManager().resolve(viewer).key();
        return rank.equalsIgnoreCase("admin") || rank.equalsIgnoreCase("owner");
    }

    public void refreshDndVisibilityFor(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return;
        for (UUID uuid : dnd) {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null && target.isOnline()) updatePlayerVisibility(viewer, target);
        }
        if (plugin.getTabVisibilityManager() != null) plugin.getTabVisibilityManager().refreshNow();
        plugin.refreshVisibleOnlineDisplays();
    }

    /**
     * Liefert den Flugzustand, der ohne Vanish gelten soll. Dadurch speichern
     * Hub/SMP-Profile nicht versehentlich den temporären Vanish-Flugmodus.
     */
    public boolean persistedAllowFlight(Player player) {
        FlightState state = previousFlightStates.get(player.getUniqueId());
        return isVanished(player) && state != null ? state.allowFlight() : player.getAllowFlight();
    }

    public boolean persistedFlying(Player player) {
        FlightState state = previousFlightStates.get(player.getUniqueId());
        return isVanished(player) && state != null ? state.flying() : player.isFlying();
    }

    public boolean canSeeVanished(Player viewer, Player vanishedPlayer) {
        if (viewer == null || vanishedPlayer == null) return false;
        if (viewer.getUniqueId().equals(vanishedPlayer.getUniqueId())) return true;
        if (plugin.getRankManager() == null) return false;

        var viewerRank = plugin.getRankManager().resolve(viewer);
        var vanishedRank = plugin.getRankManager().resolve(vanishedPlayer);
        // Der Owner sieht jeden Vanish-Spieler. Ein unsichtbarer Owner bleibt für alle anderen verborgen.
        if (viewerRank.key().equalsIgnoreCase("owner")) return true;
        if (vanishedRank.key().equalsIgnoreCase("owner")) return false;
        // Kleinere Zahl = höherer Rang; gleiche Ränge sehen sich nicht.
        return viewerRank.priority() < vanishedRank.priority();
    }

    public void register(String... commands) {
        for (String command : commands) {
            if (plugin.getCommand(command) == null) {
                plugin.getLogger().warning("Command '" + command + "' fehlt in plugin.yml.");
                continue;
            }
            plugin.getCommand(command).setExecutor(this);
            plugin.getCommand(command).setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "fly" -> fly(sender, args);
            case "vanish" -> vanish(sender, args);
            case "dnd" -> dnd(sender, args);
            case "list" -> list(sender);
            case "ec" -> enderChest(sender);
            case "ecsee" -> enderChestSee(sender, args);
            case "invsee" -> inventorySee(sender, args);
            default -> false;
        };
    }

    private boolean fly(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.command.fly")) return noPermission(sender);
        if (args.length > 0 && !sender.hasPermission("smpcore.command.fly.others")) return noPermission(sender);
        Player target = resolveTarget(sender, args, "staff.messages.fly-usage");
        if (target == null) return true;
        boolean enabled = !target.getAllowFlight();
        target.setAllowFlight(enabled);
        if (!enabled) target.setFlying(false);
        message(target, enabled ? "staff.messages.fly-enabled" : "staff.messages.fly-disabled",
                "%player%", target.getName());
        if (sender != target) message(sender, enabled ? "staff.messages.fly-enabled-other" : "staff.messages.fly-disabled-other",
                "%player%", target.getName());
        return true;
    }

    private boolean vanish(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.command.vanish")) return noPermission(sender);
        if (args.length > 0 && args[0].equalsIgnoreCase("friendnotify")) {
            return vanishFriendNotify(sender, args);
        }
        if (args.length > 0 && !sender.hasPermission("smpcore.command.vanish.others")) return noPermission(sender);
        Player target = resolveTarget(sender, args, "staff.messages.vanish-usage");
        if (target == null) return true;
        boolean enable = !vanished.contains(target.getUniqueId());
        if (enable) {
            previousFlightStates.put(target.getUniqueId(), new FlightState(target.getAllowFlight(), target.isFlying()));
            vanished.add(target.getUniqueId());
            saveVanishedPlayers();
            target.setAllowFlight(true);
            target.setFlying(true);
            clearMobTargets(target);
            for (Player viewer : Bukkit.getOnlinePlayers()) updatePlayerVisibility(viewer, target);
        } else {
            vanished.remove(target.getUniqueId());
            clearSilentContainerState(target.getUniqueId());
            saveVanishedPlayers();
            restoreFlightState(target);
            for (Player viewer : Bukkit.getOnlinePlayers()) updatePlayerVisibility(viewer, target);
        }
        if (!isDnd(target) && !friendNotifyDisabled.contains(target.getUniqueId())) {
            plugin.notifyFriendVanishState(target, enable);
        }
        if (plugin.getTabVisibilityManager() != null) plugin.getTabVisibilityManager().refreshNow();
        else if (plugin.getRankManager() != null) plugin.getRankManager().applyAll();
        plugin.refreshVisibleOnlineDisplays();
        message(target, enable ? "staff.messages.vanish-enabled" : "staff.messages.vanish-disabled", "%player%", target.getName());
        if (sender != target) message(sender, enable ? "staff.messages.vanish-enabled-other" : "staff.messages.vanish-disabled-other", "%player%", target.getName());
        return true;
    }

    private boolean dnd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (!sender.hasPermission("smpcore.command.dnd")) return noPermission(sender);
        if (args.length != 0) return usage(sender, "staff.messages.dnd-usage");

        boolean enable = dnd.add(player.getUniqueId());
        if (!enable) dnd.remove(player.getUniqueId());
        saveDndPlayers();

        if (enable) reapplyDndVisibility(player);
        else {
            for (Player viewer : Bukkit.getOnlinePlayers()) updatePlayerVisibility(viewer, player);
            player.listPlayer(player);
        }
        if (!enable) {
            if (plugin.getTabVisibilityManager() != null) plugin.getTabVisibilityManager().refreshNow();
            else if (plugin.getRankManager() != null) plugin.getRankManager().applyAll();
        }
        plugin.refreshVisibleOnlineDisplays();
        plugin.notifyDndVisibilityState(player);
        return message(player, enable ? "staff.messages.dnd-enabled" : "staff.messages.dnd-disabled");
    }

    private boolean list(CommandSender sender) {
        List<String> names = Bukkit.getOnlinePlayers().stream()
                .filter(target -> !(sender instanceof Player viewer) || target.equals(viewer)
                        || viewer.canSee(target) && (!isDnd(target) || canSeeDnd(viewer, target))
                        && (!isVanished(target) || canSeeVanished(viewer, target)))
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return message(sender, "staff.messages.online-list", "%count%", Integer.toString(names.size()),
                "%players%", String.join(", ", names));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVisibleListCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().trim().toLowerCase(Locale.ROOT);
        String command = raw.split("\\s+", 2)[0];
        command = command.substring(command.lastIndexOf(':') + 1);
        if (command.startsWith("/")) command = command.substring(1);
        if (!command.equals("list") && !command.equals("players")) return;
        event.setCancelled(true);
        list(event.getPlayer());
    }

    private boolean vanishFriendNotify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            return usage(sender, "staff.messages.vanish-friendnotify-usage");
        }

        boolean enabled = args[1].equalsIgnoreCase("on");
        if (enabled) {
            friendNotifyDisabled.remove(player.getUniqueId());
        } else {
            friendNotifyDisabled.add(player.getUniqueId());
        }
        saveVanishedPlayers();
        return message(player, enabled
                ? "staff.messages.vanish-friendnotify-enabled"
                : "staff.messages.vanish-friendnotify-disabled");
    }

    private boolean enderChest(CommandSender sender) {
        if (!(sender instanceof Player player)) return message(sender, "staff.messages.smp-player-only");
        if (!sender.hasPermission("smpcore.command.ec")) return message(sender, "staff.messages.smp-no-permission");
        if (!isSmpWorld(player)) return message(player, "staff.messages.smp-only");
        if (plugin.expandableEnderChestManager() == null) {
            player.sendMessage("§cDas erweiterte Enderchest-System ist auf diesem Server nicht verfügbar.");
            return true;
        }
        plugin.expandableEnderChestManager().open(player);
        return true;
    }

    private boolean enderChestSee(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (!sender.hasPermission("smpcore.command.ecsee")) return noPermission(sender);
        if (args.length != 1) return usage(sender, "staff.messages.ecsee-usage");

        Player onlineTarget = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(args[0]);
        if (onlineTarget == null && (!target.hasPlayedBefore() || target.getName() == null)) {
            return playerNotFound(sender, args[0]);
        }
        if (plugin.expandableEnderChestManager() == null) {
            player.sendMessage("§cDas erweiterte Enderchest-System ist auf diesem Server nicht verfügbar.");
            return true;
        }

        if (!plugin.expandableEnderChestManager().open(player, target)) return true;
        message(player, "staff.messages.ecsee-opened", "%player%", target.getName());
        return true;
    }

    private boolean inventorySee(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (!sender.hasPermission("smpcore.command.invsee")) return noPermission(sender);
        if (args.length != 1) return usage(sender, "staff.messages.invsee-usage");
        Player target = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer offlineTarget = target != null ? target : Bukkit.getOfflinePlayer(args[0]);
        if (target == null && (!offlineTarget.hasPlayedBefore() || offlineTarget.getName() == null)) {
            return playerNotFound(sender, args[0]);
        }

        String targetName = target != null ? target.getName() : offlineTarget.getName();
        InvSeeHolder holder = new InvSeeHolder(offlineTarget.getUniqueId());
        Inventory view = Bukkit.createInventory(holder, 54, miniMessage.deserialize(
                plugin.configs().main().getString("staff.messages.invsee-title", "<dark_gray>Inventar: <white>%player%</white>")
                        .replace("%player%", targetName)
        ));
        holder.inventory = view;

        if (target != null) {
            copyPlayerInventoryToView(target, view);
        } else {
            try {
                copyNativeInventoryToView(NativePlayerDataAccess.loadInventory(offlineTarget), view);
            } catch (IOException exception) {
                plugin.getLogger().warning("Offline-Inventar von " + targetName + " konnte nicht gelesen werden: " + exception.getMessage());
                player.sendMessage(Component.text("Das Offline-Inventar konnte nicht aus den Minecraft-Spielerdaten geladen werden."));
                return true;
            }
        }
        holder.originalContents = cloneContents(view.getContents());

        player.openInventory(view);
        message(player, "staff.messages.invsee-opened", "%player%", targetName);
        return true;
    }

    private void copyPlayerInventoryToView(Player target, Inventory view) {
        ItemStack[] contents = target.getInventory().getContents();
        // Hauptinventar: drei Reihen oben.
        for (int source = 9; source <= 35; source++) {
            view.setItem(source - 9, cloneOrNull(contents[source]));
        }
        // Rüstung und Offhand in der vorletzten Reihe.
        view.setItem(36, cloneOrNull(target.getInventory().getHelmet()));
        view.setItem(37, cloneOrNull(target.getInventory().getChestplate()));
        view.setItem(38, cloneOrNull(target.getInventory().getLeggings()));
        view.setItem(39, cloneOrNull(target.getInventory().getBoots()));
        view.setItem(40, cloneOrNull(target.getInventory().getItemInOffHand()));
        // Hotbar bewusst in der untersten Reihe.
        for (int source = 0; source <= 8; source++) {
            view.setItem(45 + source, cloneOrNull(contents[source]));
        }
    }

    private boolean copyChangedViewSlotsToPlayerInventory(Inventory view, ItemStack[] original, Player target) {
        boolean changed = false;
        for (int source = 9; source <= 35; source++) {
            int guiSlot = source - 9;
            if (!sameItem(originalAt(original, guiSlot), view.getItem(guiSlot))) {
                target.getInventory().setItem(source, cloneOrNull(view.getItem(guiSlot)));
                changed = true;
            }
        }
        if (!sameItem(originalAt(original, 36), view.getItem(36))) {
            target.getInventory().setHelmet(cloneOrNull(view.getItem(36)));
            changed = true;
        }
        if (!sameItem(originalAt(original, 37), view.getItem(37))) {
            target.getInventory().setChestplate(cloneOrNull(view.getItem(37)));
            changed = true;
        }
        if (!sameItem(originalAt(original, 38), view.getItem(38))) {
            target.getInventory().setLeggings(cloneOrNull(view.getItem(38)));
            changed = true;
        }
        if (!sameItem(originalAt(original, 39), view.getItem(39))) {
            target.getInventory().setBoots(cloneOrNull(view.getItem(39)));
            changed = true;
        }
        if (!sameItem(originalAt(original, 40), view.getItem(40))) {
            target.getInventory().setItemInOffHand(cloneOrNull(view.getItem(40)));
            changed = true;
        }
        for (int source = 0; source <= 8; source++) {
            int guiSlot = 45 + source;
            if (!sameItem(originalAt(original, guiSlot), view.getItem(guiSlot))) {
                target.getInventory().setItem(source, cloneOrNull(view.getItem(guiSlot)));
                changed = true;
            }
        }
        if (changed) target.updateInventory();
        return changed;
    }

    private void copyNativeInventoryToView(Map<Integer, ItemStack> nativeSlots, Inventory view) {
        for (int source = 9; source <= 35; source++) {
            view.setItem(source - 9, cloneOrNull(nativeSlots.get(source)));
        }
        view.setItem(36, cloneOrNull(nativeSlots.get(NativePlayerDataAccess.HEAD_SLOT))); // Helm
        view.setItem(37, cloneOrNull(nativeSlots.get(NativePlayerDataAccess.CHEST_SLOT))); // Brustplatte
        view.setItem(38, cloneOrNull(nativeSlots.get(NativePlayerDataAccess.LEGS_SLOT))); // Hose
        view.setItem(39, cloneOrNull(nativeSlots.get(NativePlayerDataAccess.FEET_SLOT))); // Schuhe
        view.setItem(40, cloneOrNull(nativeSlots.get(NativePlayerDataAccess.OFFHAND_SLOT)));
        for (int source = 0; source <= 8; source++) {
            view.setItem(45 + source, cloneOrNull(nativeSlots.get(source)));
        }
    }

    private Map<Integer, ItemStack> nativeInventoryFromView(Inventory view, Set<Integer> managedSlots) {
        Map<Integer, ItemStack> slots = new HashMap<>();
        for (Integer slot : managedSlots) {
            if (slot == null) continue;
            int guiSlot = nativeToGuiSlot(slot);
            if (guiSlot >= 0) slots.put(slot, cloneOrNull(view.getItem(guiSlot)));
        }
        return slots;
    }

    private Set<Integer> changedNativeInventorySlots(Inventory view, ItemStack[] original) {
        Set<Integer> changed = new HashSet<>();
        for (int slot = 0; slot <= 35; slot++) {
            int guiSlot = nativeToGuiSlot(slot);
            if (guiSlot >= 0 && !sameItem(originalAt(original, guiSlot), view.getItem(guiSlot))) changed.add(slot);
        }
        addChangedEquipmentSlot(changed, NativePlayerDataAccess.HEAD_SLOT, 36, view, original);
        addChangedEquipmentSlot(changed, NativePlayerDataAccess.CHEST_SLOT, 37, view, original);
        addChangedEquipmentSlot(changed, NativePlayerDataAccess.LEGS_SLOT, 38, view, original);
        addChangedEquipmentSlot(changed, NativePlayerDataAccess.FEET_SLOT, 39, view, original);
        addChangedEquipmentSlot(changed, NativePlayerDataAccess.OFFHAND_SLOT, 40, view, original);
        return changed;
    }

    private void addChangedEquipmentSlot(Set<Integer> changed, int nativeSlot, int guiSlot,
                                         Inventory view, ItemStack[] original) {
        if (!sameItem(originalAt(original, guiSlot), view.getItem(guiSlot))) changed.add(nativeSlot);
    }

    private int nativeToGuiSlot(int nativeSlot) {
        if (nativeSlot >= 9 && nativeSlot <= 35) return nativeSlot - 9;
        if (nativeSlot >= 0 && nativeSlot <= 8) return 45 + nativeSlot;
        return switch (nativeSlot) {
            case NativePlayerDataAccess.HEAD_SLOT -> 36;
            case NativePlayerDataAccess.CHEST_SLOT -> 37;
            case NativePlayerDataAccess.LEGS_SLOT -> 38;
            case NativePlayerDataAccess.FEET_SLOT -> 39;
            case NativePlayerDataAccess.OFFHAND_SLOT -> 40;
            default -> -1;
        };
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clones = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) clones[index] = cloneOrNull(contents[index]);
        return clones;
    }

    private ItemStack originalAt(ItemStack[] original, int slot) {
        return original != null && slot >= 0 && slot < original.length ? original[slot] : null;
    }

    private boolean sameItem(ItemStack first, ItemStack second) {
        return Objects.equals(first, second);
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private Player resolveTarget(CommandSender sender, String[] args, String usagePath) {
        if (args.length == 0) {
            if (sender instanceof Player player) return player;
            usage(sender, usagePath);
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) playerNotFound(sender, args[0]);
        return target;
    }

    private boolean isSmpWorld(Player player) {
        return plugin.isSmpGameplayWorld(player.getWorld());
    }

    private boolean noPermission(CommandSender sender) { return message(sender, "staff.messages.no-permission"); }
    private boolean playerOnly(CommandSender sender) { return message(sender, "staff.messages.player-only"); }
    private boolean usage(CommandSender sender, String path) { return message(sender, path); }
    private boolean playerNotFound(CommandSender sender, String player) { return message(sender, "staff.messages.player-not-found", "%player%", player); }

    private boolean message(CommandSender sender, String path, String... replacements) {
        String raw = plugin.configs().main().getString(path, "<red>Nachricht fehlt: " + path + "</red>");
        for (int i = 0; i + 1 < replacements.length; i += 2) raw = raw.replace(replacements[i], replacements[i + 1]);
        Component component = miniMessage.deserialize(raw);
        sender.sendMessage(component);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (UUID uuid : vanished) {
            Player hidden = Bukkit.getPlayer(uuid);
            if (hidden != null) updatePlayerVisibility(joined, hidden);
        }
        for (UUID uuid : dnd) {
            Player hidden = Bukkit.getPlayer(uuid);
            if (hidden != null) updatePlayerVisibility(joined, hidden);
        }
        if (vanished.contains(joined.getUniqueId())) {
            event.joinMessage(null);
            previousFlightStates.putIfAbsent(joined.getUniqueId(), new FlightState(joined.getAllowFlight(), joined.isFlying()));
            joined.setAllowFlight(true);
            clearMobTargets(joined);
            Bukkit.getScheduler().runTask(plugin, () -> reapplyVanishState(joined));
            for (Player viewer : Bukkit.getOnlinePlayers()) updatePlayerVisibility(viewer, joined);
        }
        if (dnd.contains(joined.getUniqueId())) {
            event.joinMessage(null);
            for (Player viewer : Bukkit.getOnlinePlayers()) updatePlayerVisibility(viewer, joined);
            joined.listPlayer(joined);
            Bukkit.getScheduler().runTask(plugin, () -> reapplyDndVisibility(joined));
        }
        Bukkit.getScheduler().runTask(plugin, plugin::refreshVisibleOnlineDisplays);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        clearSilentContainerState(player.getUniqueId());
        if (vanished.contains(player.getUniqueId())) {
            event.quitMessage(null);
            previousFlightStates.remove(player.getUniqueId());
            saveVanishedPlayers();
        } else {
            previousFlightStates.remove(player.getUniqueId());
        }
        if (dnd.contains(player.getUniqueId())) event.quitMessage(null);
        Bukkit.getScheduler().runTask(plugin, plugin::refreshVisibleOnlineDisplays);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanishedTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isVanished(player)) return;

        // Der aktuelle Flugzustand wird vor dem Teleport gespeichert. Vanish
        // garantiert nur die FlugERLAUBNIS; ob der Spieler gerade fliegt,
        // bleibt wie im Creative-Modus per Doppelleertaste steuerbar.
        boolean wasFlying = player.isFlying();
        reapplyVanishStateLater(player, wasFlying, 1L);
        reapplyVanishStateLater(player, wasFlying, 3L);
        reapplyVanishStateLater(player, wasFlying, 10L);
    }

    private void reapplyVanishStateLater(Player player, boolean flyingState, long delayTicks) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null && isVanished(online)) {
                reapplyVanishState(online);
                online.setFlying(flyingState);
            }
        }, delayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVanishedRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (isDnd(player)) Bukkit.getScheduler().runTask(plugin, () -> reapplyDndVisibility(player));
        if (!isVanished(player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            reapplyVanishState(player);
            player.setFlying(false);
        });
    }

    public void reapplyVanishState(Player player) {
        if (player == null || !player.isOnline() || !isVanished(player)) return;
        // Vanish gibt dauerhaft Flugerlaubnis, erzwingt aber nicht dauerhaft
        // den aktiven Flugmodus. Dadurch funktionieren Starten und Landen mit
        // Doppelleertaste genau wie im Creative-Modus.
        player.setAllowFlight(true);
        clearMobTargets(player);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            updatePlayerVisibility(viewer, player);
        }

        if (plugin.getTabVisibilityManager() != null) plugin.getTabVisibilityManager().refreshNow();
        else if (plugin.getRankManager() != null) plugin.getRankManager().apply(player);
    }

    public void reapplyDndVisibility(Player player) {
        if (player == null || !player.isOnline() || !isDnd(player)) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) updatePlayerVisibility(viewer, player);
        // Keep the player's own TAB entry: it uses the normal Vanish gray styling,
        // while ordinary viewers never receive the DND entry.
        player.listPlayer(player);
        if (plugin.getTabVisibilityManager() != null) plugin.getTabVisibilityManager().refreshNow();
        else if (plugin.getRankManager() != null) plugin.getRankManager().apply(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVanishedDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && isVanished(player)) {
            event.setCancelled(true);
            if (event.getEntity() instanceof Mob mob) mob.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !isVanished(player)) return;
        Inventory inventory = event.getInventory();
        if (!isVisibleLidInventory(inventory)) return;
        openSilentContainerSession(player, inventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            closeSilentContainerSession(player.getUniqueId());
        }
        InventoryHolder rawHolder = event.getInventory().getHolder(false);
        if (rawHolder instanceof InvSeeHolder holder) {
            Player target = Bukkit.getPlayer(holder.targetId);
            if (target != null && target.isOnline()) {
                // Nur Slots zurückschreiben, die der Staff im InvSee tatsächlich verändert hat.
                // Live-Änderungen des Zielspielers in allen unberührten Slots bleiben dadurch erhalten.
                copyChangedViewSlotsToPlayerInventory(event.getInventory(), holder.originalContents, target);
            } else {
                Set<Integer> changedSlots = changedNativeInventorySlots(event.getInventory(), holder.originalContents);
                if (changedSlots.isEmpty()) return;
                OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(holder.targetId);
                try {
                    NativePlayerDataAccess.saveInventory(
                            offlineTarget,
                            nativeInventoryFromView(event.getInventory(), changedSlots),
                            changedSlots
                    );
                } catch (IOException exception) {
                    plugin.getLogger().severe("Offline-Inventar von " + holder.targetId
                            + " konnte nicht in playerdata gespeichert werden: " + exception.getMessage());
                    event.getPlayer().sendMessage(Component.text(
                            "Das Offline-Inventar konnte nicht in den Minecraft-Spielerdaten gespeichert werden."
                    ));
                }
            }
            return;
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanishedContainerOpen(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isVanished(player) || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        BlockState state = event.getClickedBlock().getState();
        if (state instanceof EnderChest) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(player.getEnderChest()));
            return;
        }

        // 1.53.37 muss den echten Interaktionspfad behalten, damit LootTables und
        // Schutzplugins korrekt arbeiten. Für sichtbare Lid-Container merken wir aber
        // zusätzlich den Vanish-Öffner, damit die Animation selbst bei einem Race im
        // serverseitigen Viewer-Zähler nicht zu versteckten Zuschauern durchrutscht.
        if (state instanceof Lidded && state instanceof InventoryHolder holder) {
            rememberSilentContainerInteraction(player, state, holder.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanishedGameEvent(GenericGameEvent event) {
        // Sculk-Sensoren und der Warden reagieren auf GameEvents/Vibrationen.
        // Events, deren Quelle ein Vanish-Spieler ist, werden vollständig verworfen.
        if (event.getEntity() instanceof Player player && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    /** Entfernt Vanish-Spieler aus allen serverseitigen Befehlsvorschlägen für Spieler, die sie nicht sehen dürfen. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player viewer) || vanished.isEmpty() && dnd.isEmpty()) return;

        Set<String> hiddenNames = new HashSet<>();
        for (UUID uuid : dnd) {
            Player hidden = Bukkit.getPlayer(uuid);
            if (hidden != null && hidden.isOnline() && !canSeeDnd(viewer, hidden)) {
                hiddenNames.add(hidden.getName().toLowerCase(Locale.ROOT));
            }
        }
        for (UUID uuid : vanished) {
            Player hidden = Bukkit.getPlayer(uuid);
            if (hidden == null || !hidden.isOnline() || canSeeVanished(viewer, hidden)) continue;
            hiddenNames.add(hidden.getName().toLowerCase(Locale.ROOT));
        }
        if (hiddenNames.isEmpty()) return;

        event.getCompletions().removeIf(completion -> {
            if (completion == null) return false;
            String candidate = completion;
            int space = candidate.indexOf(' ');
            if (space >= 0) candidate = candidate.substring(0, space);
            return hiddenNames.contains(candidate.toLowerCase(Locale.ROOT));
        });
    }

    private void loadVanishedPlayers() {
        if (!vanishFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(vanishFile);
        for (String raw : yaml.getStringList("vanished")) {
            try { vanished.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
        for (String raw : yaml.getStringList("friend-notify-disabled")) {
            try { friendNotifyDisabled.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void loadDndPlayers() {
        if (!dndFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dndFile);
        for (String raw : yaml.getStringList("players")) {
            try { dnd.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void saveDndPlayers() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("players", dnd.stream().map(UUID::toString).sorted().toList());
        try {
            File parent = dndFile.getParentFile();
            if (parent != null) parent.mkdirs();
            yaml.save(dndFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("DND-Status konnte nicht gespeichert werden: " + exception.getMessage());
        }
    }

    private void saveVanishedPlayers() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("vanished", vanished.stream().map(UUID::toString).sorted().toList());
        yaml.set("friend-notify-disabled", friendNotifyDisabled.stream().map(UUID::toString).sorted().toList());
        try {
            File parent = vanishFile.getParentFile();
            if (parent != null) parent.mkdirs();
            yaml.save(vanishFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Vanish-Status konnte nicht gespeichert werden: " + exception.getMessage());
        }
    }

    private void clearMobTargets(Player target) {
        for (var entity : target.getWorld().getLivingEntities()) {
            if (entity instanceof Mob mob && target.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
        }
    }

    private void restoreFlightState(Player target) {
        FlightState state = previousFlightStates.remove(target.getUniqueId());
        if (state == null) return;
        target.setAllowFlight(state.allowFlight());
        target.setFlying(state.allowFlight() && state.flying());
    }

    private void updatePlayerVisibility(Player viewer, Player target) {
        if (viewer.getUniqueId().equals(target.getUniqueId())) return;
        if (isDnd(target) && !canSeeDnd(viewer, target)
                || isVanished(target) && !canSeeVanished(viewer, target)) {
            viewer.hidePlayer(plugin, target);
        } else {
            viewer.showPlayer(plugin, target);
        }
    }

    private void rememberSilentContainerInteraction(Player actor, BlockState state, Inventory inventory) {
        rememberSilentContainerBlock(actor, state);
        if (inventory instanceof DoubleChestInventory doubleChest) {
            rememberInventoryHolderBlock(actor, doubleChest.getLeftSide().getHolder());
            rememberInventoryHolderBlock(actor, doubleChest.getRightSide().getHolder());
        }
    }

    private void rememberInventoryHolderBlock(Player actor, InventoryHolder holder) {
        if (holder instanceof BlockState blockState) rememberSilentContainerBlock(actor, blockState);
    }

    private void rememberSilentContainerBlock(Player actor, BlockState state) {
        VanishBlockKey key = VanishBlockKey.of(state);
        Set<UUID> allowedViewers = new HashSet<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (canSeeVanished(viewer, actor)) allowedViewers.add(viewer.getUniqueId());
        }
        allowedViewers.add(actor.getUniqueId());
        VanishContainerInteraction interaction = new VanishContainerInteraction(
                actor.getUniqueId(), Set.copyOf(allowedViewers), System.nanoTime() + 2_000_000_000L);
        silentContainerInteractions.put(key, interaction);
        // Nur Race-Guard bis InventoryOpen. Die eigentliche Session hat bewusst KEIN Zeitlimit.
        Bukkit.getScheduler().runTaskLater(plugin, () -> silentContainerInteractions.remove(key, interaction), 50L);
    }

    private boolean isVisibleLidInventory(Inventory inventory) {
        if (inventory == null) return false;
        if (inventory instanceof DoubleChestInventory) return true;
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof Lidded;
    }

    private void openSilentContainerSession(Player actor, Inventory inventory) {
        closeSilentContainerSession(actor.getUniqueId());
        Set<VanishBlockKey> keys = containerKeys(inventory);
        if (keys.isEmpty()) return;
        Set<VanishBlockKey> immutable = Set.copyOf(keys);
        silentContainersByActor.put(actor.getUniqueId(), immutable);
        for (VanishBlockKey key : immutable) {
            silentContainerOpeners.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet())
                    .add(actor.getUniqueId());
            // Die aktive Session ersetzt den kurzlebigen Pre-Open-Guard fuer diesen Block.
            VanishContainerInteraction pending = silentContainerInteractions.get(key);
            if (pending != null && pending.actorId().equals(actor.getUniqueId())) {
                silentContainerInteractions.remove(key, pending);
            }
        }
    }

    private Set<VanishBlockKey> containerKeys(Inventory inventory) {
        Set<VanishBlockKey> keys = new HashSet<>();
        if (inventory instanceof DoubleChestInventory doubleChest) {
            addContainerKey(keys, doubleChest.getLeftSide().getHolder());
            addContainerKey(keys, doubleChest.getRightSide().getHolder());
        } else {
            addContainerKey(keys, inventory.getHolder(false));
        }
        return keys;
    }

    private void addContainerKey(Set<VanishBlockKey> keys, InventoryHolder holder) {
        if (holder instanceof BlockState state && state instanceof Lidded) {
            keys.add(VanishBlockKey.of(state));
        }
    }

    private void closeSilentContainerSession(UUID actorId) {
        Set<VanishBlockKey> keys = silentContainersByActor.remove(actorId);
        if (keys == null) return;
        for (VanishBlockKey key : keys) {
            Set<UUID> openers = silentContainerOpeners.get(key);
            if (openers == null) continue;
            openers.remove(actorId);
            if (openers.isEmpty()) silentContainerOpeners.remove(key, openers);
        }
    }

    private void clearSilentContainerState(UUID actorId) {
        closeSilentContainerSession(actorId);
        silentContainerInteractions.entrySet().removeIf(entry -> entry.getValue().actorId().equals(actorId));
    }

    /**
     * Kombiniert den loot-sicheren echten Containerpfad aus 1.53.37 mit der
     * viewer-spezifischen Vanish-Absicherung aus 1.53.36. Der echte Block wird nie
     * virtualisiert; nur das ausgehende BLOCK_ACTION-Paket wird pro Zuschauer korrigiert.
     */
    private void registerSilentContainerPackets() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) return;
        try {
            ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                    plugin,
                    ListenerPriority.HIGHEST,
                    PacketType.Play.Server.BLOCK_ACTION
            ) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    Player viewer = event.getPlayer();
                    if (viewer == null || !viewer.isOnline()) return;

                    BlockPosition position = event.getPacket().getBlockPositionModifier().readSafely(0);
                    Integer action = event.getPacket().getIntegers().readSafely(0);
                    Integer value = event.getPacket().getIntegers().readSafely(1);
                    if (position == null || action == null || value == null || action != 1) return;

                    BlockState state = viewer.getWorld().getBlockAt(position.getX(), position.getY(), position.getZ()).getState();
                    if (!(state instanceof Container container)) return;

                    Set<UUID> hiddenOpenerIds = new HashSet<>();
                    int visibleViewers = 0;
                    for (var human : container.getInventory().getViewers()) {
                        if (!(human instanceof Player opener)) continue;
                        if (isVanished(opener) && !canSeeVanished(viewer, opener)) {
                            hiddenOpenerIds.add(opener.getUniqueId());
                        } else {
                            visibleViewers++;
                        }
                    }

                    VanishBlockKey key = new VanishBlockKey(
                            viewer.getWorld().getUID(), position.getX(), position.getY(), position.getZ());

                    // Dauerhafte Session: bleibt aktiv, solange der Vanish-Spieler das
                    // Inventar wirklich offen hat. Das deckt auch spaete/erneute
                    // BLOCK_ACTION-Pakete bei lange geoeffneten Doppelkisten ab.
                    Set<UUID> sessionOpeners = silentContainerOpeners.get(key);
                    if (sessionOpeners != null) {
                        for (UUID actorId : sessionOpeners) {
                            Player actor = Bukkit.getPlayer(actorId);
                            if (actor != null && actor.isOnline() && isVanished(actor)
                                    && !canSeeVanished(viewer, actor)) {
                                hiddenOpenerIds.add(actorId);
                            }
                        }
                    }

                    // Backup gegen den kurzen Zeitpunkt, an dem BLOCK_ACTION bereits
                    // gesendet wird, InventoryOpen/getViewers() aber noch nicht fertig ist.
                    VanishContainerInteraction interaction = silentContainerInteractions.get(key);
                    if (interaction != null) {
                        if (interaction.expiresAtNanos() < System.nanoTime()) {
                            silentContainerInteractions.remove(key, interaction);
                        } else if (!interaction.allowedViewerIds().contains(viewer.getUniqueId())) {
                            Player actor = Bukkit.getPlayer(interaction.actorId());
                            if (actor != null && actor.isOnline() && isVanished(actor)) {
                                hiddenOpenerIds.add(actor.getUniqueId());
                            }
                        }
                    }

                    int hiddenViewers = hiddenOpenerIds.size();
                    if (hiddenViewers == 0) return;

                    // Truhen/Barrels verwenden value als Viewer-Zähler. Shulkerboxen
                    // verwenden dasselbe Paket als Auf/Zu-Schalter. Sichtbare Öffner
                    // bleiben deshalb für den jeweiligen Zuschauer vollständig erhalten.
                    int renderedValue = state instanceof ShulkerBox
                            ? (visibleViewers > 0 ? 1 : 0)
                            : Math.max(0, value - hiddenViewers);
                    event.getPacket().getIntegers().write(1, renderedValue);
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Vanish-Container-Paketfilter konnte nicht registriert werden: " + exception.getMessage());
        }
    }

    private record VanishBlockKey(UUID worldId, int x, int y, int z) {
        private static VanishBlockKey of(BlockState state) {
            return new VanishBlockKey(state.getWorld().getUID(), state.getX(), state.getY(), state.getZ());
        }
    }

    private record VanishContainerInteraction(UUID actorId, Set<UUID> allowedViewerIds, long expiresAtNanos) { }

    private record FlightState(boolean allowFlight, boolean flying) { }

    private static final class InvSeeHolder implements InventoryHolder {
        private final UUID targetId;
        private Inventory inventory;
        private ItemStack[] originalContents;

        private InvSeeHolder(UUID targetId) {
            this.targetId = targetId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("dnd")) return Collections.emptyList();
        if (name.equals("vanish")) {
            if (args.length == 1) {
                List<String> values = new ArrayList<>();
                values.add("friendnotify");
                if (sender.hasPermission("smpcore.command.vanish.others")) {
                    for (Player player : Bukkit.getOnlinePlayers()) values.add(player.getName());
                }
                return filter(values, args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("friendnotify")) {
                return filter(List.of("on", "off"), args[1]);
            }
            return Collections.emptyList();
        }
        if (!name.equals("ec") && args.length == 1) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return filter(names, args[args.length - 1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
