package de.walahi.smpcore.ranks;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Proxy;

/** Standalone regression check for Paper teams without an explicit color. */
public final class RankTeamColorCheck {
    public static void main(String[] args) {
        TeamState missing = new TeamState(null);
        RankManager.applyTeamColor(missing.team(), NamedTextColor.GRAY);
        check(missing.color == NamedTextColor.GRAY && missing.getterCalls == 0 && missing.setterCalls == 1,
                "colorless team is initialized without calling Team.color()");

        TeamState matching = new TeamState(NamedTextColor.GRAY);
        RankManager.applyTeamColor(matching.team(), NamedTextColor.GRAY);
        check(matching.color == NamedTextColor.GRAY && matching.getterCalls == 1 && matching.setterCalls == 0,
                "matching team keeps its existing color");

        TeamState different = new TeamState(NamedTextColor.RED);
        RankManager.applyTeamColor(different.team(), NamedTextColor.GRAY);
        check(different.color == NamedTextColor.GRAY && different.getterCalls == 1 && different.setterCalls == 1,
                "different color follows the existing update behavior");
        System.out.println("RANK_TEAM_COLOR_CHECK=PASS");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static final class TeamState {
        private NamedTextColor color;
        private int getterCalls;
        private int setterCalls;

        private TeamState(NamedTextColor initialColor) { color = initialColor; }

        private Team team() {
            return (Team) Proxy.newProxyInstance(Team.class.getClassLoader(), new Class<?>[] {Team.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("hasColor")) return color != null;
                        if (method.getName().equals("color") && (args == null || args.length == 0)) {
                            getterCalls++;
                            if (color == null) throw new IllegalStateException("Team does not have a color!");
                            return color;
                        }
                        if (method.getName().equals("color") && args.length == 1) {
                            setterCalls++;
                            color = (NamedTextColor) args[0];
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
