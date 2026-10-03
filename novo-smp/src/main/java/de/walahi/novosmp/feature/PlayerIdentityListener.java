package de.walahi.novosmp.feature;

import de.walahi.smpcore.database.DatabaseManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Keeps readable player_name columns current without using names as identity keys. */
public final class PlayerIdentityListener implements Listener {
    private final JavaPlugin plugin;
    private final DatabaseManager database;

    public PlayerIdentityListener(JavaPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        List<Update> updates = List.of(
                new Update("player_stats", "player_uuid", "player_name"),
                new Update("vote_events", "player_uuid", "player_name"),
                new Update("economy_accounts", "player_uuid", "player_name"),
                new Update("lumi_accounts", "player_uuid", "player_name"),
                new Update("daily_rewards", "player_uuid", "player_name"),
                new Update("enderchest_upgrades", "player_uuid", "player_name"),
                new Update("homes", "player_uuid", "player_name"),
                new Update("ah_stats", "player_uuid", "player_name"),
                new Update("ah_collect", "owner_uuid", "owner_name"),
                new Update("ah_history", "player_uuid", "player_name")
        );
        try (Connection connection = database.connection()) {
            for (Update update : updates) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + database.table(update.table) + " SET " + update.nameColumn + " = ? WHERE " + update.uuidColumn + " = ?")) {
                    statement.setString(1, name);
                    statement.setString(2, uuid);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Spielername konnte nicht in allen Datenbanktabellen aktualisiert werden: " + exception.getMessage());
        }
    }

    private record Update(String table, String uuidColumn, String nameColumn) { }
}
