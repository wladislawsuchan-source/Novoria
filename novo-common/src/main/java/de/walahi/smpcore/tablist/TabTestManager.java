package de.walahi.smpcore.tablist;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.ranks.RankManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TabTestManager {
    private static final String TEST_TEAM_PREFIX = "smp_tst_";

    private final SMPCorePlugin plugin;
    private final RankManager rankManager;
    private final Map<UUID, TestSession> sessions = new HashMap<>();

    public TabTestManager(SMPCorePlugin plugin, RankManager rankManager) {
        this.plugin = plugin;
        this.rankManager = rankManager;
    }

    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
    }

    public int show(Player viewer, int requestedCount) {
        hide(viewer);
        int count = Math.max(1, Math.min(plugin.configs().scoreboards().getInt("tablist.simulation.max-players", 100), requestedCount));
        ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
        List<PlayerInfoData> entries = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        List<String> profileNames = new ArrayList<>();
        Set<String> teamNames = new LinkedHashSet<>();
        List<RankManager.RankDefinition> ranks = rankManager.getRanks();
        Scoreboard scoreboard = viewer.getScoreboard();

        for (int i = 0; i < count; i++) {
            RankManager.RankDefinition rank = pickRank(ranks, i, count);
            UUID id = UUID.randomUUID();
            ids.add(id);

            // Minecraft sortiert die Tablist anhand des Scoreboard-Teams und danach
            // anhand des echten Profilnamens. Der sichtbare Displayname ist dafür egal.
            String profileName = sortableProfileName(rank, i);
            profileNames.add(profileName);
            net.kyori.adventure.text.Component display = rankManager.tabName(rank, alphabeticalName(i));
            WrappedGameProfile profile = new WrappedGameProfile(id, profileName);

            String teamName = rankManager.teamName(rank);
            Team team = scoreboard.getTeam(teamName);
            if (team == null) team = scoreboard.registerNewTeam(teamName);
            team.addEntry(profileName);
            teamNames.add(teamName);

            entries.add(new PlayerInfoData(
                    id,
                    plugin.configs().scoreboards().getInt("tablist.simulation.fake-ping", 35),
                    true,
                    EnumWrappers.NativeGameMode.SURVIVAL,
                    profile,
                    WrappedChatComponent.fromJson(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                            .serialize(display))
            ));
        }

        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, EnumSet.of(
                EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME,
                EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
                EnumWrappers.PlayerInfoAction.UPDATE_LATENCY,
                EnumWrappers.PlayerInfoAction.UPDATE_LISTED
        ));
        if (packet.getPlayerInfoDataLists().size() > 1) packet.getPlayerInfoDataLists().write(1, entries);
        else packet.getPlayerInfoDataLists().write(0, entries);
        protocol.sendServerPacket(viewer, packet);
        sessions.put(viewer.getUniqueId(), new TestSession(ids, profileNames, teamNames));
        return count;
    }

    public void hide(Player viewer) {
        TestSession session = sessions.remove(viewer.getUniqueId());
        if (session == null) return;

        Scoreboard scoreboard = viewer.getScoreboard();
        for (String teamName : session.teamNames()) {
            Team team = scoreboard.getTeam(teamName);
            if (team == null) continue;
            for (String profileName : session.profileNames()) team.removeEntry(profileName);
            if (team.getEntries().isEmpty()) team.unregister();
        }

        if (session.ids().isEmpty() || !isAvailable()) return;
        ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
        PacketContainer remove = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        remove.getUUIDLists().write(0, session.ids());
        protocol.sendServerPacket(viewer, remove);
    }

    public void hideAll() {
        for (Player player : Bukkit.getOnlinePlayers()) hide(player);
    }

    private RankManager.RankDefinition pickRank(List<RankManager.RankDefinition> ranks, int index, int total) {
        if (ranks.isEmpty()) return rankManager.fallbackPlayerRank();
        if (index < ranks.size()) return ranks.get(index);
        int premiumStart = Math.max(ranks.size(), total / 3);
        if (index >= premiumStart) {
            for (RankManager.RankDefinition rank : ranks) {
                if (rank.key().equalsIgnoreCase(index % 3 == 0 ? "premium-plus" : "premium")) return rank;
            }
        }
        return ranks.get(ranks.size() - 1);
    }

    private String sortableProfileName(RankManager.RankDefinition rank, int index) {
        // Maximal 16 Zeichen (Minecraft-Profilnamen-Limit).
        int priority = Math.max(0, Math.min(999, rank.priority()));
        return String.format("%03dT%011d", priority, index + 1);
    }

    private String alphabeticalName(int index) {
        char first = (char) ('A' + (index % 26));
        return first + "Testspieler" + String.format("%02d", index + 1);
    }

    private record TestSession(List<UUID> ids, List<String> profileNames, Set<String> teamNames) {}
}
