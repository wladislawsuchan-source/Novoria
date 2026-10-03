package de.walahi.smpcore.tpa;

import org.bukkit.entity.Player;

import java.util.List;

public final class DisabledTpaAccess implements TpaAccess {
    @Override public void request(Player requester, String[] args) { }
    @Override public void accept(Player target, String[] args) { }
    @Override public void deny(Player target, String[] args) { }
    @Override public void cancel(Player requester) { }
    @Override public List<String> tabComplete(Player player, String commandName, String prefix) { return List.of(); }
    @Override public void handleQuit(Player player) { }
    @Override public void shutdown() { }
}
