package de.walahi.novosmp.auction;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

/** Coordinates auction persistence and periodic expiry processing. */
public final class AuctionManager {
    private final SMPCorePlugin plugin;
    private final AuctionRepository repository;
    private final AuctionSettings settings;
    private BukkitTask expiryTask;

    public AuctionManager(SMPCorePlugin plugin, AuctionRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.settings = new AuctionSettings(plugin);
    }

    public void start() {
        stop();
        long intervalSeconds = settings.expiryCheckSeconds();
        expiryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::expireSafely, 20L * 10L, 20L * intervalSeconds);
    }

    public void stop() {
        if (expiryTask == null) return;
        expiryTask.cancel();
        expiryTask = null;
    }

    public int listingLimit(Player player) {
        if (player.hasPermission(AuctionPermissions.LIMIT_UNLIMITED)) return Integer.MAX_VALUE;
        int highest = settings.defaultListingLimit();
        for (int limit : settings.listingLimits()) {
            if (limit > highest && player.hasPermission(AuctionPermissions.listingLimit(limit))) {
                highest = limit;
            }
        }
        return highest;
    }

    public int activeListings(UUID playerId) throws SQLException {
        return repository.countActive(playerId);
    }

    public int pendingCollect(UUID playerId) throws SQLException {
        return repository.countCollect(playerId);
    }

    public AuctionRepository repository() {
        return repository;
    }

    AuctionSettings settings() {
        return settings;
    }

    void broadcastCreated(Player seller, ItemStack item, long price) {
        Component message = Component.text("[AH] ", NamedTextColor.GOLD)
                .append(Component.text(seller.getName() + " verkauft ", NamedTextColor.GRAY))
                .append(item.effectiveName())
                .append(Component.text(" für " + price + " Coins ", NamedTextColor.GRAY))
                .append(Component.text("[Öffnen]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/ah")));
        Bukkit.broadcast(message);
    }

    private void expireSafely() {
        try {
            int expired = repository.expireDue(
                    System.currentTimeMillis(), settings.expiryBatchSize());
            if (expired > 0) {
                plugin.getLogger().info(expired
                        + " AH-Angebot(e) sind abgelaufen und wurden zu Collect verschoben.");
            }
        } catch (SQLException exception) {
            plugin.getLogger().log(Level.SEVERE, "AH-Ablaufprüfung fehlgeschlagen.", exception);
        }
    }
}
