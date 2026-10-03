package de.walahi.novohub.feature;

import de.walahi.smpcore.SMPCorePlugin;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

/** Erzwingt den konfigurierten Spielerzustand im Hub. */
public final class HubPlayerStateManager implements Listener {
    private final SMPCorePlugin plugin;
    private final NamespacedKey premiumFlightMarker;

    public HubPlayerStateManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.premiumFlightMarker = new NamespacedKey(plugin, "premium_flight_granted");
        LuckPermsProvider.get().getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(event.getUser().getUniqueId());
                    if (player != null) updatePremiumFlight(player);
                }));
        Bukkit.getScheduler().runTask(plugin, this::refreshAll);
    }

    public void apply(Player player) {
        if (!isHub(player)) return;

        int food = clamp(plugin.configs().menus().getInt("hub.player-state.food-level", 20), 0, 20);
        float saturation = (float) Math.max(0.0, Math.min(20.0,
                plugin.configs().menus().getDouble("hub.player-state.saturation", 20.0)));
        double configuredHealth = plugin.configs().menus().getDouble("hub.player-state.health", 20.0);
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20.0 : player.getAttribute(Attribute.MAX_HEALTH).getValue();

        player.setFoodLevel(food);
        player.setSaturation(saturation);
        player.setExhaustion(0.0f);
        player.setHealth(Math.max(0.1, Math.min(maxHealth, configuredHealth)));
        updatePremiumFlight(player);
    }

    private void updatePremiumFlight(Player player) {
        boolean eligible = plugin.configs().server().getBoolean("premium-flight.enabled", true)
                && isHub(player) && plugin.getRankManager() != null
                && plugin.getRankManager().hasPremium(player);
        boolean survivalFlight = player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE;
        if (eligible && survivalFlight) {
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
                markManaged(player);
            } else if (isManaged(player)) {
                // Marker aus einem vorherigen Lauf beibehalten. Fremde Flugrechte werden nie übernommen.
                markManaged(player);
            }
            return;
        }

        if (!isManaged(player) || !survivalFlight) return;
        boolean staffFlight = player.hasPermission("smpcore.command.fly");
        boolean buildFlight = plugin.getBuildModeManager() != null
                && plugin.getBuildModeManager().isActive(player);
        boolean vanishFlight = plugin.getStaffCommands() != null
                && plugin.getStaffCommands().isVanished(player);
        if (staffFlight || buildFlight || vanishFlight) return;
        if (player.isFlying()) player.setFlying(false);
        player.setAllowFlight(false);
        clearManaged(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isHub(event.getPlayer())) apply(event.getPlayer());
            else updatePremiumFlight(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (isHub(event.getPlayer())) apply(event.getPlayer());
        else updatePremiumFlight(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> updatePremiumFlight(event.getPlayer()));
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) revokeManagedFlight(player);
    }

    public void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (isHub(player)) apply(player);
            else updatePremiumFlight(player);
        });
    }

    private void revokeManagedFlight(Player player) {
        if (!isManaged(player)) return;
        boolean survivalFlight = player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE;
        if (survivalFlight) {
            if (player.isFlying()) player.setFlying(false);
            player.setAllowFlight(false);
        }
        clearManaged(player);
    }

    private boolean isManaged(Player player) {
        Byte value = player.getPersistentDataContainer().get(premiumFlightMarker, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private void markManaged(Player player) {
        player.getPersistentDataContainer().set(premiumFlightMarker, PersistentDataType.BYTE, (byte) 1);
    }

    private void clearManaged(Player player) {
        player.getPersistentDataContainer().remove(premiumFlightMarker);
    }

    public boolean isHub(Player player) {
        String hub = plugin.configs().server().getString("world", "world");
        return player.getWorld().getName().equalsIgnoreCase(hub);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
