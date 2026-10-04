package de.walahi.novosmp.quests;

import de.walahi.smpcore.database.migration.CreateQuestSystemMigration;
import de.walahi.smpcore.storage.StorageDialect;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Deterministic source checks; run with the built plugin and Paper API on the classpath. */
public final class QuestRulesCheck {
    private QuestRulesCheck() { }

    public static void main(String[] args) throws Exception {
        YamlConfiguration yaml;
        try (var stream = QuestRulesCheck.class.getClassLoader().getResourceAsStream("quests.yml")) {
            check(stream != null, "quests.yml packaged");
            yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        check(yaml.getMapList("daily.pool").size() == 12, "12 daily definitions");
        check(yaml.getMapList("global.pool").size() == 17, "17 global definitions");
        int[] players = {0, 1, 4, 5, 9, 10, 14, 15, 19, 20, 50};
        int[] rows = {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5};
        for (int i = 0; i < players.length; i++) check(QuestConfig.rows(yaml.getIntegerList("global.row-thresholds"), players[i]) == rows[i], "rows " + players[i]);

        ZoneId berlin = ZoneId.of("Europe/Berlin");
        check(QuestClock.day(Instant.parse("2026-10-03T21:59:59Z"), berlin, 0, 0).equals(LocalDate.of(2026, 10, 3)), "before midnight");
        check(QuestClock.day(Instant.parse("2026-10-03T22:00:00Z"), berlin, 0, 0).equals(LocalDate.of(2026, 10, 4)), "after midnight");
        check(QuestClock.untilReset(Instant.parse("2026-03-28T23:00:00Z"), berlin, 0, 0) == 23L * 3600_000L,
                "spring DST day is 23 hours");
        check(QuestClock.untilReset(Instant.parse("2026-10-24T22:00:00Z"), berlin, 0, 0) == 25L * 3600_000L,
                "autumn DST day is 25 hours");

        QuestDefinition diamond = new QuestDefinition("diamond_30", QuestType.MATERIAL_BREAK, 30, 3,
                Material.DIAMOND_ORE, "diamond", Material.DIAMOND_ORE, null);
        QuestDefinition deep = new QuestDefinition("deep", QuestType.MATERIAL_BREAK, 50, 5,
                Material.DEEPSLATE_DIAMOND_ORE, "deep", Material.DEEPSLATE_DIAMOND_ORE, null);
        QuestDefinition ore = new QuestDefinition("ore", QuestType.ORE_BREAK, 5, 3,
                Material.IRON_ORE, "ore", null, null);
        check(diamond.matches(QuestType.MATERIAL_BREAK, Material.DIAMOND_ORE, null, true), "diamond target");
        check(!diamond.matches(QuestType.MATERIAL_BREAK, Material.DEEPSLATE_DIAMOND_ORE, null, true), "diamond/deepslate separate");
        check(deep.matches(QuestType.MATERIAL_BREAK, Material.DEEPSLATE_DIAMOND_ORE, null, true), "deepslate target");
        check(ore.matches(QuestType.MATERIAL_BREAK, Material.DIAMOND_ORE, null, true), "daily ore group");
        check(ore.matches(QuestType.MATERIAL_BREAK, Material.DEEPSLATE_DIAMOND_ORE, null, true), "daily deepslate group");
        QuestDefinition cow = new QuestDefinition("cow", QuestType.BREED, 50, 3, Material.WHEAT, "cow", null, EntityType.COW);
        QuestDefinition dragon = new QuestDefinition("dragon", QuestType.BOSS_KILL, 1, 5, Material.DRAGON_HEAD, "dragon", null, EntityType.ENDER_DRAGON);
        check(cow.matches(QuestType.BREED, null, EntityType.COW, false), "cow breed");
        check(!cow.matches(QuestType.BREED, null, EntityType.SHEEP, false), "sheep not cow");
        check(dragon.matches(QuestType.ENTITY_KILL, null, EntityType.ENDER_DRAGON, false), "dragon boss");
        check(!dragon.matches(QuestType.ENTITY_KILL, null, EntityType.WITHER, false), "wither not dragon");

        UUID player = UUID.randomUUID();
        QuestState.Daily first = new QuestState.Daily(UUID.randomUUID(), player, LocalDate.now(), 0, "mob_30", 0, false, false);
        QuestState.Daily second = new QuestState.Daily(UUID.randomUUID(), player, LocalDate.now(), 1, "mob_30", 0, false, false);
        check(!first.id.equals(second.id), "daily duplicate instances independent");
        for (QuestState.Daily daily : List.of(first, second)) daily.progress++;
        check(first.progress == 1 && second.progress == 1, "one event advances both daily duplicates");

        QuestState.Global a = new QuestState.Global(0, UUID.randomUUID(), "diamond_30", 0, 1, null);
        QuestState.Global b = new QuestState.Global(1, UUID.randomUUID(), "diamond_30", 0, 1, null);
        a.participants.put(player, new QuestState.Participant(player, "A", 5, 1));
        check(b.participants.isEmpty(), "later global duplicate starts at zero");
        b.participants.put(player, new QuestState.Participant(player, "A", 25, 2));
        check(a.participants.get(player).progress == 5 && b.participants.get(player).progress == 25, "instance progress independent");

        QuestState.Global ranking = new QuestState.Global(2, UUID.randomUUID(), "mob_250", 0, 1, null);
        UUID early = UUID.randomUUID(), late = UUID.randomUUID();
        ranking.participants.put(early, new QuestState.Participant(early, "Early", 20, 100));
        ranking.participants.put(late, new QuestState.Participant(late, "Late", 20, 101));
        for (int p : List.of(10, 5, 0)) {
            UUID id = UUID.randomUUID(); ranking.participants.put(id, new QuestState.Participant(id, "Other", p, 102));
        }
        check(ranking.topThree().size() == 3 && ranking.topThree().getFirst().player.equals(early)
                && ranking.topThree().get(1).player.equals(late), "top 3 and earlier tie");

        long cooldown = 900_000L, activeSince = 1_000_000L, freezeAt = activeSince + 390_000L;
        cooldown -= freezeAt - activeSince;
        check(cooldown == 510_000L, "cooldown freeze keeps 8:30");

        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new CreateQuestSystemMigration().apply(connection, StorageDialect.SQLITE, "test_");
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name LIKE 'test_quest_%'");
                 var result = query.executeQuery()) {
                check(result.next() && result.getInt(1) == 4, "quest migration creates four tables");
            }
        }
        if (args.length == 1) {
            Path database = Path.of(args[0]).toAbsolutePath().normalize();
            check(Files.isRegularFile(database), "live test database exists");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:file:" + database.toString().replace('\\', '/') + "?mode=ro")) {
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM schema_migrations WHERE version=35");
                     var result = query.executeQuery()) {
                    check(result.next() && result.getInt(1) == 1, "test server migration 35 installed");
                }
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM quest_global_slot");
                     var result = query.executeQuery()) {
                    check(result.next() && result.getInt(1) == 0, "zero-player start created no global slots");
                }
            }
        }
        System.out.println("QUEST_RULES=PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
