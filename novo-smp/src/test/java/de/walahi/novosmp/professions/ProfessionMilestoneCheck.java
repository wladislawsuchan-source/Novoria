package de.walahi.novosmp.professions;

import org.bukkit.configuration.file.YamlConfiguration;
import de.walahi.smpcore.database.migration.CreateProfessionsMigration;
import de.walahi.smpcore.storage.StorageDialect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Standalone milestone-message and menu-route regression checks. */
public final class ProfessionMilestoneCheck {
    public static void main(String[] args) throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File("novo-smp/src/main/resources/professions.yml"));
        check(ProfessionRepository.shouldCreditGreenHit(25, 25, true)
                        && !ProfessionRepository.shouldCreditGreenHit(25, 50, true)
                        && ProfessionRepository.shouldCreditGreenHit(50, 50, true)
                        && !ProfessionRepository.shouldCreditGreenHit(50, 75, true)
                        && !ProfessionRepository.shouldCreditGreenHit(50, 50, false),
                "Green hits count only for the current stage");
        for (int level : List.of(10, 20, 30, 40, 60, 70, 80, 90))
            check(ProfessionFeedback.noticeFor(level) == ProfessionFeedback.Notice.REWARD,
                    "reward message at level " + level);
        for (int level : List.of(25, 50, 75, 100))
            check(ProfessionFeedback.noticeFor(level) == ProfessionFeedback.Notice.LIMIT,
                    "limit message, not reward message, at level " + level);
        check(ProfessionFeedback.noticeFor(49) == ProfessionFeedback.Notice.NONE,
                "no repeated milestone message on other levels");
        for (String id : List.of(ProfessionManager.LUMBERJACK, ProfessionManager.MINER,
                ProfessionManager.HUNTER, ProfessionManager.ANGLER)) {
            check(ProfessionFeedback.rewardCommand(id).equals("/berufe rewards " + id),
                    "correct rewards menu route for " + id);
            check(ProfessionFeedback.progressCommand(id, 75).equals("/berufe progress " + id + " 75"),
                    "correct progress menu route for " + id);
        }
        check(config.getString("feedback.reward-reached-chat", "").contains("%command%")
                        && config.getString("feedback.limit-reached-chat", "").contains("%command%"),
                "clickable milestone messages configured");
        check(hasRunCommand(MiniMessage.miniMessage().deserialize(config.getString("feedback.reward-reached-chat")
                        .replace("%command%", ProfessionFeedback.rewardCommand(ProfessionManager.ANGLER))),
                        "/berufe rewards angler")
                        && hasRunCommand(MiniMessage.miniMessage().deserialize(config.getString("feedback.limit-reached-chat")
                        .replace("%command%", ProfessionFeedback.progressCommand(ProfessionManager.MINER, 50))),
                        "/berufe progress bergarbeiter 50"),
                "chat components carry the correct clickable menu commands");
        check(config.isConfigurationSection("level-rewards.rewards.50")
                        && config.isConfigurationSection("level-rewards.rewards.100"),
                "level 50 and 100 rewards remain available");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new CreateProfessionsMigration().apply(connection, StorageDialect.SQLITE, "");
            UUID player = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO profession_contributions(player_uuid,profession_id,prestige,milestone,requirement_id,amount,updated_at) VALUES (?,'angler',0,?,?,?,0)")) {
                for (Object[] row : new Object[][] {{25, "green_hits", 25}, {50, "green_hits", 12}, {50, "max_combo", 10}}) {
                    insert.setString(1, player.toString());
                    insert.setInt(2, (int) row[0]);
                    insert.setString(3, (String) row[1]);
                    insert.setInt(4, (int) row[2]);
                    insert.executeUpdate();
                }
            }
            ProfessionRepository.resetGreenHitStage(connection, "profession_contributions", player, 0, 50);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT milestone,requirement_id,amount FROM profession_contributions WHERE player_uuid=?")) {
                query.setString(1, player.toString());
                try (ResultSet rows = query.executeQuery()) {
                    boolean previous = false, next = false, combo = false;
                    while (rows.next()) {
                        int level = rows.getInt(1);
                        String id = rows.getString(2);
                        if (level == 25 && id.equals("green_hits")) previous = rows.getInt(3) == 25;
                        if (level == 50 && id.equals("green_hits")) next = true;
                        if (level == 50 && id.equals("max_combo")) combo = rows.getInt(3) == 10;
                    }
                    check(previous && !next && combo, "next Green-hit stage starts at zero without resetting combo");
                }
            }
        }
        System.out.println("Profession milestone checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean hasRunCommand(Component component, String command) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND && click.value().equals(command))
            return true;
        for (Component child : component.children())
            if (hasRunCommand(child, command)) return true;
        return false;
    }
}
