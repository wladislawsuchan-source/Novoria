package de.walahi.novosmp.friends;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.friends.FriendEntry;
import de.walahi.smpcore.friends.FriendRepository;
import de.walahi.smpcore.friends.FriendRequest;
import de.walahi.smpcore.friends.FriendSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** All friend GUI rendering and click routing, identified through FriendMenuHolder. */
public final class FriendMenuController implements Listener {
    private final NovoSMPPlugin plugin;
    private final FriendRepository repository;
    private final FriendHomeRepository homes;
    private final FriendStateCache cache;
    private final FriendGlowController glow;
    private final FriendConfiguration config;
    private final FriendItemFactory items;
    private final Predicate<Player> vanished;

    public FriendMenuController(NovoSMPPlugin plugin, FriendRepository repository, FriendHomeRepository homes,
                                FriendStateCache cache, FriendGlowController glow, FriendConfiguration config,
                                Predicate<Player> vanished) {
        this.plugin = plugin;
        this.repository = repository;
        this.homes = homes;
        this.cache = cache;
        this.glow = glow;
        this.config = config;
        this.items = new FriendItemFactory(plugin, config);
        this.vanished = vanished;
    }

    public void openList(Player viewer) {
        openList(viewer, 0);
    }

    public void openList(Player viewer, int page) {
        List<FriendEntry> friends = repository.list(viewer.getUniqueId());
        int pageSize = boundedPageSize("menus.list.page-size", 45, "menus.list.size", 54);
        int maxPage = friends.isEmpty() ? 0 : (friends.size() - 1) / pageSize;
        int safePage = Math.max(0, Math.min(page, maxPage));
        Inventory inventory = create(FriendMenuType.LIST, viewer.getUniqueId(), safePage,
                "menus.list", 54, "<white>Freunde</white>", Map.of());

        int from = safePage * pageSize;
        int to = Math.min(from + pageSize, friends.size());
        for (int index = from; index < to; index++) {
            FriendEntry friend = friends.get(index);
            Player online = Bukkit.getPlayer(friend.uuid());
            boolean visible = online != null && online.isOnline() && !vanished.test(online);
            Map<String, String> placeholders = Map.of(
                    "player", safeName(friend.name()),
                    "world", visible ? worldLabel(online) : "Offline"
            );
            ItemStack icon = visible
                    ? items.head(friend.uuid(), "items.list.online", "<white><player></white>",
                    List.of("<green>Online</green>", "<gray>Welt: <white><world></white></gray>",
                            "<dark_gray>Klicken für Einstellungen</dark_gray>"),
                    "profile", friend.uuid(), null, placeholders)
                    : items.item("items.list.offline", Material.SKELETON_SKULL, "<white><player></white>",
                    List.of("<gray>Offline</gray>", "<dark_gray>Klicken für Einstellungen</dark_gray>"),
                    "profile", friend.uuid(), null, placeholders);
            inventory.setItem(index - from, icon);
        }

        set(inventory, "menus.list.slots.defaults", 45,
                items.head(viewer.getUniqueId(), "items.list.defaults", "<white><player></white>",
                        List.of("<gray>Allgemeine Einstellungen</gray>", "<dark_gray>Klicken zum Öffnen</dark_gray>"),
                        "defaults", viewer.getUniqueId(), null, Map.of("player", viewer.getName())));
        if (safePage > 0) {
            set(inventory, "menus.list.slots.previous", 48,
                    items.item("items.list.previous", Material.ARROW, "<white>Vorherige Seite</white>",
                            List.of("<gray>Seite <page> von <pages></gray>"), "list_prev", viewer.getUniqueId(), null,
                            Map.of("page", Integer.toString(safePage), "pages", Integer.toString(maxPage + 1))));
        }
        int requestCount = repository.incomingRequests(viewer.getUniqueId()).size();
        set(inventory, "menus.list.slots.requests", 49,
                items.item("items.list.requests", Material.PAPER, "<white>Freundesanfragen</white>",
                        List.of("<gray>Offene Anfragen: <white><count></white></gray>",
                                "<dark_gray>Klicken zum Öffnen</dark_gray>"),
                        "requests", viewer.getUniqueId(), null, Map.of("count", Integer.toString(requestCount))));
        if (safePage < maxPage) {
            set(inventory, "menus.list.slots.next", 50,
                    items.item("items.list.next", Material.ARROW, "<white>Nächste Seite</white>",
                            List.of("<gray>Seite <page> von <pages></gray>"), "list_next", viewer.getUniqueId(), null,
                            Map.of("page", Integer.toString(safePage + 2), "pages", Integer.toString(maxPage + 1))));
        }
        set(inventory, "menus.list.slots.summary", 53,
                items.item("items.list.summary", Material.BOOK, "<white>Übersicht</white>",
                        List.of("<gray>Freunde: <white><count></white></gray>",
                                "<gray>Seite: <white><page>/<pages></white></gray>"),
                        "noop", viewer.getUniqueId(), null, Map.of(
                                "count", Integer.toString(friends.size()),
                                "page", Integer.toString(safePage + 1),
                                "pages", Integer.toString(maxPage + 1))));
        viewer.openInventory(inventory);
    }

    public void openRequests(Player viewer) {
        Inventory inventory = create(FriendMenuType.REQUESTS, viewer.getUniqueId(), 0,
                "menus.requests", 54, "<white>Freundesanfragen</white>", Map.of());
        int limit = Math.min(config.integer("menus.requests.content-limit", 45), inventory.getSize());
        int slot = 0;
        for (FriendRequest request : repository.incomingRequests(viewer.getUniqueId())) {
            if (slot >= limit) break;
            inventory.setItem(slot++, items.head(request.senderUuid(), "items.request", "<white><player></white>",
                    List.of("<green>Linksklick: Annehmen</green>", "<red>Rechtsklick: Ablehnen</red>"),
                    "request", request.senderUuid(), null, Map.of("player", safeName(request.senderName()))));
        }
        set(inventory, "menus.requests.back-slot", 45, back(viewer.getUniqueId(), "Zur Freundesliste", "back"));
        viewer.openInventory(inventory);
    }

    public void openProfile(Player viewer, UUID friend, String name) {
        FriendSettings settings = cache.settings(viewer.getUniqueId(), friend);
        Inventory inventory = create(FriendMenuType.PROFILE, friend, 0,
                "menus.profile", 36, "<white>Freund: <player></white>", Map.of("player", safeName(name)));
        set(inventory, "menus.profile.slots.tpa", 4,
                items.item(config.material("menus.profile.materials.tpa", Material.ENDER_PEARL), "items.tpa",
                        "<white>TPA senden</white>", List.of("<gray>Sendet diesem Freund eine</gray>",
                                "<gray>Teleportanfrage.</gray>"), "tpa", friend, null, Map.of()));
        set(inventory, "menus.profile.slots.glow", 10,
                toggle(config.material("menus.profile.materials.glow", Material.GLOW_INK_SAC), "Glow", settings.glow(),
                        "glow", friend, ""));
        set(inventory, "menus.profile.slots.chat", 12,
                toggle(config.material("menus.profile.materials.chat", Material.NAME_TAG), "Chat-Markierung",
                        settings.chatMark(), "chat", friend, ""));
        set(inventory, "menus.profile.slots.join-leave", 14,
                toggle(config.material("menus.profile.materials.join-leave", Material.BELL), "Join/Leave",
                        settings.joinLeave(), "join", friend, ""));
        set(inventory, "menus.profile.slots.friendly-fire", 16,
                toggle(config.material("menus.profile.materials.friendly-fire", Material.IRON_SWORD), "Friendly Fire",
                        settings.friendlyFire(), "fire", friend, ""));
        set(inventory, "menus.profile.slots.homes", 22,
                items.item(config.material("menus.profile.materials.homes", Material.RED_BED), "items.homes",
                        "<white>Home-Zugriffe</white>", List.of("<gray>Jedes Home einzeln erlauben</gray>"),
                        "homes", friend, null, Map.of()));
        set(inventory, "menus.profile.slots.reset", 25,
                items.item(config.material("menus.profile.materials.reset", Material.REPEATER), "items.reset",
                        "<white>Standards übernehmen</white>",
                        List.of("<gray>Setzt alle Einstellungen dieses Freundes</gray>",
                                "<gray>auf deine aktuellen allgemeinen Standards.</gray>"),
                        "reset_settings", friend, null, Map.of()));
        set(inventory, "menus.profile.slots.back", 27, back(viewer.getUniqueId(), "Zur Freundesliste", "back"));
        set(inventory, "menus.profile.slots.remove", 31,
                items.item("items.remove", Material.BARRIER, "<white>Freund entfernen</white>",
                        List.of("<red>Klicken zum Entfernen</red>"), "remove", friend, null, Map.of()));
        viewer.openInventory(inventory);
    }

    public void openDefaults(Player viewer) {
        FriendSettings settings = repository.getDefaults(viewer.getUniqueId());
        Inventory inventory = create(FriendMenuType.DEFAULTS, viewer.getUniqueId(), 0,
                "menus.defaults", 27, "<white>Freunde: Standard</white>", Map.of());
        set(inventory, "menus.defaults.slots.allow-glow", 4,
                toggle(config.material("menus.defaults.materials.allow-glow", Material.GLOW_BERRIES),
                        "Eigenen Glow erlauben", cache.allowsBeingGlowed(viewer.getUniqueId()), "allow_be_glowed",
                        viewer.getUniqueId(), "Erlaubt Freunden, dich für sich leuchten zu lassen"));
        set(inventory, "menus.defaults.slots.glow", 10,
                toggle(config.material("menus.defaults.materials.glow", Material.GLOW_INK_SAC),
                        "Freunde hervorheben", settings.glow(), "d_glow", viewer.getUniqueId(),
                        "Standard für neu angenommene Freunde"));
        set(inventory, "menus.defaults.slots.chat", 12,
                toggle(config.material("menus.defaults.materials.chat", Material.NAME_TAG),
                        "Chat-Markierung", settings.chatMark(), "d_chat", viewer.getUniqueId(),
                        "Standard für neu angenommene Freunde"));
        set(inventory, "menus.defaults.slots.join-leave", 14,
                toggle(config.material("menus.defaults.materials.join-leave", Material.BELL),
                        "Join/Leave", settings.joinLeave(), "d_join", viewer.getUniqueId(),
                        "Standard für neu angenommene Freunde"));
        set(inventory, "menus.defaults.slots.friendly-fire", 16,
                toggle(config.material("menus.defaults.materials.friendly-fire", Material.IRON_SWORD),
                        "Friendly Fire", settings.friendlyFire(), "d_fire", viewer.getUniqueId(),
                        "Standard für neu angenommene Freunde"));
        set(inventory, "menus.defaults.slots.back", 22, back(viewer.getUniqueId(), "Zur Freundesliste", "back"));
        viewer.openInventory(inventory);
    }

    public void openHomes(Player viewer, UUID friend) {
        List<FriendHomeRepository.HomeEntry> entries = homes.homes(viewer.getUniqueId());
        repository.deleteHomePermissionsExcept(viewer.getUniqueId(), entries.stream().map(FriendHomeRepository.HomeEntry::name).toList());
        Inventory inventory = create(FriendMenuType.HOMES, friend, 0,
                "menus.homes", 27, "<white>Home-Zugriff</white>", Map.of());
        List<Integer> slots = config.integerList("menus.homes.home-slots", List.of(10, 11, 12, 13, 14, 15, 16));
        for (int index = 0; index < Math.min(entries.size(), slots.size()); index++) {
            FriendHomeRepository.HomeEntry entry = entries.get(index);
            boolean allowed = repository.isHomeAllowed(viewer.getUniqueId(), friend, entry.name());
            Material material = allowed
                    ? config.material("menus.homes.allowed-material", Material.LIME_BED)
                    : config.material("menus.homes.denied-material", Material.RED_BED);
            String path = allowed ? "items.home.allowed" : "items.home.denied";
            ItemStack item = items.item(material, path, "<white><home></white>",
                    List.of(allowed ? "<green>Erlaubt</green>" : "<red>Verboten</red>",
                            "<gray>Klicken zum Umschalten</gray>"),
                    "home_toggle", friend, entry.name(), Map.of("home", safeName(entry.displayName())));
            set(inventory, slots.get(index), item);
        }
        if (entries.isEmpty()) {
            set(inventory, "menus.homes.empty-slot", 13,
                    items.item("items.home.empty", Material.BARRIER, "<white>Keine Homes vorhanden</white>",
                            List.of("<gray>Erstelle zuerst ein Home mit /sethome.</gray>"),
                            "noop", friend, null, Map.of()));
        }
        set(inventory, "menus.homes.info-slot", 18,
                items.item("items.home.info", Material.BOOK, "<white>Home-Freigaben</white>",
                        List.of("<gray>Jedes Home gilt nur für</gray>", "<gray>diesen einen Freund.</gray>"),
                        "noop", friend, null, Map.of()));
        set(inventory, "menus.homes.back-slot", 22, back(friend, "Zum Freund", "profile"));
        viewer.openInventory(inventory);
    }

    public void openAdminPlayers(Player viewer, int page) {
        if (!viewer.hasPermission(FriendPermissions.ADMIN_HOME)) {
            send(viewer, "messages.no-permission", "<red>Dafür hast du keine Berechtigung.</red>");
            return;
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort((first, second) -> first.getName().compareToIgnoreCase(second.getName()));
        int pageSize = boundedPageSize("menus.admin-players.page-size", 45, "menus.admin-players.size", 54);
        int maxPage = players.isEmpty() ? 0 : (players.size() - 1) / pageSize;
        int safePage = Math.max(0, Math.min(page, maxPage));
        Inventory inventory = create(FriendMenuType.ADMIN_PLAYERS, viewer.getUniqueId(), safePage,
                "menus.admin-players", 54, "<white>Admin-Spieler</white>", Map.of());
        int start = safePage * pageSize;
        int end = Math.min(players.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            Player target = players.get(index);
            boolean enabled = glow.hasAdminGlow(viewer.getUniqueId(), target.getUniqueId());
            String path = enabled ? "items.admin-player.enabled" : "items.admin-player.disabled";
            inventory.setItem(index - start, items.head(target.getUniqueId(), path, "<white><player></white>",
                    List.of(enabled ? "<green>Glow: Aktiv</green>" : "<red>Glow: Inaktiv</red>",
                            "<gray>Linksklick: Glow umschalten</gray>",
                            "<gray>Rechtsklick: Homes öffnen</gray>",
                            "<dark_gray>Welt: <world></dark_gray>"),
                    "admin_player", target.getUniqueId(), null,
                    Map.of("player", target.getName(), "world", target.getWorld().getName())));
        }
        if (safePage > 0) {
            set(inventory, "menus.admin-players.slots.previous", 48,
                    items.item("items.list.previous", Material.ARROW, "<white>Vorherige Seite</white>",
                            List.of("<gray>Seite <page> von <pages></gray>"), "admin_prev", viewer.getUniqueId(), null,
                            Map.of("page", Integer.toString(safePage), "pages", Integer.toString(maxPage + 1))));
        }
        set(inventory, "menus.admin-players.slots.summary", 49,
                items.item("items.admin-summary", Material.BOOK, "<white>Spielerübersicht</white>",
                        List.of("<gray>Online: <white><count></white></gray>",
                                "<gray>Seite: <white><page>/<pages></white></gray>"),
                        "noop", viewer.getUniqueId(), null, Map.of(
                                "count", Integer.toString(players.size()),
                                "page", Integer.toString(safePage + 1),
                                "pages", Integer.toString(maxPage + 1))));
        if (safePage < maxPage) {
            set(inventory, "menus.admin-players.slots.next", 50,
                    items.item("items.list.next", Material.ARROW, "<white>Nächste Seite</white>",
                            List.of("<gray>Seite <page> von <pages></gray>"), "admin_next", viewer.getUniqueId(), null,
                            Map.of("page", Integer.toString(safePage + 2), "pages", Integer.toString(maxPage + 1))));
        }
        viewer.openInventory(inventory);
    }

    public void openAdminHomes(Player viewer, UUID owner, String ownerName) {
        if (!viewer.hasPermission(FriendPermissions.ADMIN_HOME)) {
            send(viewer, "messages.no-permission", "<red>Dafür hast du keine Berechtigung.</red>");
            return;
        }
        List<FriendHomeRepository.HomeEntry> entries = homes.homes(owner);
        Inventory inventory = create(FriendMenuType.ADMIN_HOMES, owner, 0,
                "menus.admin-homes", 27, "<white>Admin-Homes: <player></white>",
                Map.of("player", safeName(ownerName)));
        List<Integer> slots = config.integerList("menus.admin-homes.home-slots", List.of(10, 11, 12, 13, 14, 15, 16));
        for (int index = 0; index < Math.min(entries.size(), slots.size()); index++) {
            FriendHomeRepository.HomeEntry entry = entries.get(index);
            set(inventory, slots.get(index),
                    items.item("items.admin-home", Material.RED_BED, "<white><home></white>",
                            List.of("<gray>Klicken zum Teleportieren</gray>",
                                    "<dark_gray>Freigabe wird als Admin umgangen</dark_gray>"),
                            "admin_home", owner, entry.name(), Map.of("home", safeName(entry.displayName()))));
        }
        if (entries.isEmpty()) {
            set(inventory, "menus.admin-homes.empty-slot", 13,
                    items.item("items.admin-home-empty", Material.BARRIER, "<white>Keine Homes vorhanden</white>",
                            List.of("<gray>Dieser Spieler besitzt keine Homes.</gray>"),
                            "noop", owner, null, Map.of()));
        }
        set(inventory, "menus.admin-homes.owner-slot", 18,
                items.item("items.admin-owner", Material.PLAYER_HEAD, "<white><player></white>",
                        List.of("<gray>Homes: <white><count></white></gray>"),
                        "noop", owner, null, Map.of("player", safeName(ownerName), "count", Integer.toString(entries.size()))));
        set(inventory, "menus.admin-homes.close-slot", 22,
                items.item("items.close", Material.BARRIER, "<white>Schließen</white>",
                        List.of("<gray>Menü schließen</gray>"), "admin_close", owner, null, Map.of()));
        viewer.openInventory(inventory);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof FriendMenuHolder holder)) return;
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        String action = items.action(clicked);
        if (action == null || action.equals("noop")) return;
        UUID friend = items.uuid(clicked, holder.subject() == null ? player.getUniqueId() : holder.subject());

        switch (action) {
            case "list_prev" -> openList(player, holder.page() - 1);
            case "list_next" -> openList(player, holder.page() + 1);
            case "admin_prev" -> openAdminPlayers(player, holder.page() - 1);
            case "admin_next" -> openAdminPlayers(player, holder.page() + 1);
            case "defaults" -> openDefaults(player);
            case "requests" -> openRequests(player);
            case "back" -> openList(player);
            case "request" -> handleRequestClick(player, friend, event.isLeftClick(), event.isRightClick());
            case "profile" -> openProfile(player, friend, friendName(friend));
            case "tpa" -> sendTpa(player, friend);
            case "homes" -> openHomes(player, friend);
            case "admin_player" -> handleAdminPlayer(player, friend, event.isRightClick());
            case "admin_close" -> player.closeInventory();
            case "admin_home" -> teleportAdminHome(player, friend, items.home(clicked));
            case "remove" -> {
                repository.remove(player.getUniqueId(), friend);
                cache.invalidatePair(player.getUniqueId(), friend);
                glow.refreshAll();
                player.closeInventory();
                send(player, "messages.friend-removed", "<red>Freund entfernt.</red>");
            }
            case "reset_settings" -> {
                repository.resetSettingsToDefaults(player.getUniqueId(), friend);
                cache.invalidateSettings(player.getUniqueId(), friend);
                glow.refreshAll();
                actionBar(player, "messages.settings-reset", "<green>Freundeseinstellungen auf Standard zurückgesetzt.</green>");
                openProfile(player, friend, friendName(friend));
            }
            case "glow", "chat", "join", "fire" -> toggleFriendSetting(player, friend, action);
            case "allow_be_glowed" -> toggleOwnGlowPermission(player);
            case "d_glow", "d_chat", "d_join", "d_fire" -> toggleDefaults(player, action);
            case "home_toggle" -> toggleHome(player, friend, items.home(clicked));
            default -> { }
        }
    }

    private void handleRequestClick(Player player, UUID friend, boolean left, boolean right) {
        if (right) {
            repository.deny(friend, player.getUniqueId());
            send(player, "messages.request-denied", "<gray>Freundesanfrage abgelehnt.</gray>");
            openRequests(player);
        } else if (left) {
            repository.accept(friend, friendName(friend), player.getUniqueId(), player.getName());
            cache.invalidatePair(player.getUniqueId(), friend);
            send(player, "messages.now-friends", "<green>Ihr seid jetzt Freunde.</green>");
            glow.refreshAll();
            openRequests(player);
        }
    }

    private void sendTpa(Player player, UUID friend) {
        Player target = Bukkit.getPlayer(friend);
        if (target == null || !target.isOnline()) {
            player.closeInventory();
            send(player, "messages.friend-not-online", "<red>Dieser Freund ist nicht online.</red>");
            return;
        }
        player.closeInventory();
        player.performCommand("tpa " + target.getName());
    }

    private void handleAdminPlayer(Player player, UUID targetId, boolean rightClick) {
        if (!player.hasPermission(FriendPermissions.ADMIN_HOME)) {
            player.closeInventory();
            send(player, "messages.no-permission", "<red>Dafür hast du keine Berechtigung.</red>");
            return;
        }
        if (rightClick) {
            openAdminHomes(player, targetId, friendName(targetId));
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            send(player, "messages.player-not-online", "<red>Dieser Spieler ist nicht mehr online.</red>");
            openAdminPlayers(player, 0);
            return;
        }
        boolean enabled = glow.toggleAdminGlow(player.getUniqueId(), targetId);
        actionBar(player, enabled ? "messages.admin-glow-enabled" : "messages.admin-glow-disabled",
                enabled ? "<green>Admin-Glow aktiviert.</green>" : "<red>Admin-Glow deaktiviert.</red>");
        openAdminPlayers(player, 0);
    }

    private void teleportAdminHome(Player player, UUID owner, String home) {
        if (!player.hasPermission(FriendPermissions.ADMIN_HOME)) {
            player.closeInventory();
            send(player, "messages.no-permission", "<red>Dafür hast du keine Berechtigung.</red>");
            return;
        }
        Location location = homes.load(owner, home);
        if (location == null) {
            send(player, "messages.admin-home-missing", "<red>Dieses Home existiert nicht mehr oder seine Welt ist nicht geladen.</red>");
            openAdminHomes(player, owner, friendName(owner));
            return;
        }
        player.closeInventory();
        player.teleportAsync(location).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () ->
                send(player, success ? "messages.admin-home-success" : "messages.teleport-failed",
                        success ? "<green>Du wurdest zum Admin-Home teleportiert.</green>" : "<red>Der Teleport ist fehlgeschlagen.</red>")));
    }

    private void toggleFriendSetting(Player player, UUID friend, String action) {
        FriendSettings current = cache.settings(player.getUniqueId(), friend);
        FriendSettings changed = switch (action) {
            case "glow" -> new FriendSettings(!current.glow(), current.chatMark(), current.joinLeave(), current.friendlyFire());
            case "chat" -> new FriendSettings(current.glow(), !current.chatMark(), current.joinLeave(), current.friendlyFire());
            case "join" -> new FriendSettings(current.glow(), current.chatMark(), !current.joinLeave(), current.friendlyFire());
            default -> new FriendSettings(current.glow(), current.chatMark(), current.joinLeave(), !current.friendlyFire());
        };
        repository.setSettings(player.getUniqueId(), friend, changed);
        cache.invalidateSettings(player.getUniqueId(), friend);
        glow.refreshAll();
        actionBar(player, "messages.setting-saved", "<green>Einstellung gespeichert.</green>");
        openProfile(player, friend, friendName(friend));
    }

    private void toggleOwnGlowPermission(Player player) {
        boolean allowed = !cache.allowsBeingGlowed(player.getUniqueId());
        repository.setAllowsBeingGlowed(player.getUniqueId(), allowed);
        cache.invalidateGlowPermission(player.getUniqueId());
        glow.refreshAll();
        actionBar(player, allowed ? "messages.glow-allowed" : "messages.glow-denied",
                allowed ? "<green>Freunde dürfen dich jetzt hervorheben.</green>"
                        : "<red>Freunde dürfen dich nicht mehr hervorheben.</red>");
        openDefaults(player);
    }

    private void toggleDefaults(Player player, String action) {
        FriendSettings current = repository.getDefaults(player.getUniqueId());
        FriendSettings changed = switch (action) {
            case "d_glow" -> new FriendSettings(!current.glow(), current.chatMark(), current.joinLeave(), current.friendlyFire());
            case "d_chat" -> new FriendSettings(current.glow(), !current.chatMark(), current.joinLeave(), current.friendlyFire());
            case "d_join" -> new FriendSettings(current.glow(), current.chatMark(), !current.joinLeave(), current.friendlyFire());
            default -> new FriendSettings(current.glow(), current.chatMark(), current.joinLeave(), !current.friendlyFire());
        };
        repository.setDefaults(player.getUniqueId(), changed);
        cache.invalidatePlayer(player.getUniqueId());
        actionBar(player, "messages.defaults-saved", "<green>Allgemeiner Standard gespeichert.</green>");
        openDefaults(player);
    }

    private void toggleHome(Player player, UUID friend, String home) {
        if (home == null) return;
        boolean allowed = !repository.isHomeAllowed(player.getUniqueId(), friend, home);
        repository.setHomeAllowed(player.getUniqueId(), friend, home, allowed);
        actionBar(player, "messages.home-access-saved", "<green>Home-Zugriff gespeichert.</green>");
        openHomes(player, friend);
    }

    private ItemStack toggle(Material material, String label, boolean enabled, String action, UUID uuid, String description) {
        String path = enabled ? "items.toggle.enabled" : "items.toggle.disabled";
        return items.item(material, path, "<white><label></white>",
                List.of(enabled ? "<green>Aktiviert</green>" : "<red>Deaktiviert</red>",
                        "<gray><description></gray>", "<dark_gray>Klicken zum Umschalten</dark_gray>"),
                action, uuid, null, Map.of("label", label, "description", description));
    }

    private ItemStack back(UUID uuid, String destination, String action) {
        return items.item("items.back", Material.ARROW, "<white>Zurück</white>",
                List.of("<gray><destination></gray>"), action, uuid, null, Map.of("destination", destination));
    }

    private Inventory create(FriendMenuType type, UUID subject, int page, String path, int fallbackSize,
                             String fallbackTitle, Map<String, String> placeholders) {
        int size = validSize(config.integer(path + ".size", fallbackSize), fallbackSize);
        FriendMenuHolder holder = new FriendMenuHolder(type, subject, page);
        Inventory inventory = Bukkit.createInventory(holder, size,
                config.component(path + ".title", fallbackTitle, placeholders));
        holder.bind(inventory);
        return inventory;
    }

    private int boundedPageSize(String pageSizePath, int fallback, String sizePath, int fallbackSize) {
        int size = validSize(config.integer(sizePath, fallbackSize), fallbackSize);
        return Math.max(1, Math.min(config.integer(pageSizePath, fallback), size));
    }

    private int validSize(int configured, int fallback) {
        if (configured >= 9 && configured <= 54 && configured % 9 == 0) return configured;
        return fallback;
    }

    private void set(Inventory inventory, String slotPath, int fallback, ItemStack item) {
        set(inventory, config.integer(slotPath, fallback), item);
    }

    private void set(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) inventory.setItem(slot, item);
    }

    private void send(Player player, String path, String fallback) {
        player.sendMessage(config.component(path, fallback));
    }

    private void actionBar(Player player, String path, String fallback) {
        player.sendActionBar(config.component(path, fallback));
    }

    private String friendName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return safeName(name);
    }

    private String safeName(String value) {
        return value == null || value.isBlank() ? "Spieler" : value;
    }

    private String worldLabel(Player player) {
        String name = player.getWorld().getName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("nether")) return "Nether";
        if (name.contains("end")) return "End";
        if (name.contains("spawn")) return "SMP-Spawn";
        return "Overworld";
    }
}
