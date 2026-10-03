package de.walahi.novosmp.commands.admin;

import de.walahi.novosmp.ip.PlayerIpRepository;
import de.walahi.novosmp.ip.PlayerIpTracker;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IpCommand extends BaseCommand {
    private final PlayerIpRepository repository;

    public IpCommand(SMPCorePlugin plugin, PlayerIpRepository repository) {
        super(plugin);
        this.repository = repository;
    }

    @Override protected String permission() { return "smpcore.admin"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("§cBenutzung: /ip <Spieler>");
            return true;
        }

        String searchedName = args[0];
        Player onlineTarget = Bukkit.getPlayerExact(searchedName);
        String liveIp = onlineTarget == null ? null : PlayerIpTracker.address(onlineTarget);
        Map<String, List<String>> onlineByIp = onlinePlayersByIp();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String ipAddress = liveIp != null
                        ? liveIp
                        : repository.latestIpByName(searchedName).orElse(null);
                if (ipAddress == null) {
                    Bukkit.getScheduler().runTask(plugin,
                            () -> sender.sendMessage("§cFür diesen Spieler wurde noch keine IP gespeichert."));
                    return;
                }
                List<String> historicalNames = repository.playerNamesByIp(ipAddress);
                List<String> onlineNames = onlineByIp.getOrDefault(ipAddress, List.of());
                Bukkit.getScheduler().runTask(plugin, () -> sendResult(
                        sender, onlineTarget == null ? searchedName : onlineTarget.getName(),
                        ipAddress, onlineNames, historicalNames));
            } catch (Exception exception) {
                plugin.getLogger().warning("IP-Abfrage fehlgeschlagen: " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> sender.sendMessage("§cDie IP-Abfrage ist fehlgeschlagen."));
            }
        });
        return true;
    }

    private Map<String, List<String>> onlinePlayersByIp() {
        Map<String, java.util.ArrayList<String>> mutable = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String address = PlayerIpTracker.address(player);
            if (address != null) mutable.computeIfAbsent(address, ignored -> new java.util.ArrayList<>()).add(player.getName());
        }
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        mutable.forEach((address, names) -> snapshot.put(address, List.copyOf(names)));
        return snapshot;
    }

    private void sendResult(CommandSender sender, String targetName, String ipAddress,
                            List<String> onlineNames, List<String> historicalNames) {
        sender.sendMessage("§8§m--------------- §5IP-Auskunft §8§m---------------");
        sender.sendMessage("§7Spieler: §f" + targetName);
        sender.sendMessage("§7IP: §f" + ipAddress);
        sender.sendMessage("§7Aktuell über diese IP online: §f" + onlineNames.size());
        sender.sendMessage("§7Online: §f" + (onlineNames.isEmpty() ? "Niemand" : String.join(", ", onlineNames)));
        sender.sendMessage("§7Bisherige Accounts: §f" +
                (historicalNames.isEmpty() ? targetName : String.join(", ", historicalNames)));
        sender.sendMessage("§8§m------------------------------------------");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
