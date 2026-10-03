package de.walahi.smpcore.tpa;

import org.bukkit.entity.Player;

import java.util.List;

public interface TpaAccess {
    void request(Player requester, String[] args);
    void accept(Player target, String[] args);
    void deny(Player target, String[] args);
    void cancel(Player requester);
    List<String> tabComplete(Player player, String commandName, String prefix);
    void handleQuit(Player player);
    void shutdown();
}
