package de.walahi.novosmp.duel;

import de.walahi.novosmp.NovoSMPPlugin;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DuelWagerSignInput implements Listener {
    private final NovoSMPPlugin plugin;
    private final DuelManager manager;
    private final Map<UUID, Location> sessions = new ConcurrentHashMap<>();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    public DuelWagerSignInput(NovoSMPPlugin plugin, DuelManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void open(Player player) {
        close(player);
        Location location = player.getLocation().getBlock().getLocation().add(0, 2, 0);
        sessions.put(player.getUniqueId(), location);
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!location.equals(sessions.get(player.getUniqueId())) || !player.isOnline()) return;
            Material signMaterial = manager.config().configuredMaterial("menus.wager-sign.material", Material.OAK_SIGN);
            if (!signMaterial.name().endsWith("_SIGN")) signMaterial = Material.OAK_SIGN;
            player.sendBlockChange(location, Bukkit.createBlockData(signMaterial));
            List<String> configuredLines = manager.config().strings("menus.wager-sign.lines",
                    List.of("0", "Duell-Einsatz", "Coins", "0 = kein Einsatz"));
            String[] lines = new String[4];
            for (int index = 0; index < lines.length; index++) {
                lines[index] = index < configuredLines.size() ? configuredLines.get(index) : "";
            }
            player.sendSignChange(location, lines);
            player.openVirtualSign(Position.block(location), Side.FRONT);
        });
    }

    @EventHandler
    public void onSign(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        Location location = sessions.get(player.getUniqueId());
        if (location == null || location.getBlockX() != event.getEditedBlockPosition().blockX()
                || location.getBlockY() != event.getEditedBlockPosition().blockY()
                || location.getBlockZ() != event.getEditedBlockPosition().blockZ()) return;
        event.setCancelled(true);
        sessions.remove(player.getUniqueId());
        String raw = event.lines().isEmpty() ? "" : plain.serialize(event.lines().get(0)).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            restore(player, location);
            Long wager = parse(raw);
            if (wager == null) {
                DuelMessages.send(manager.config(), player, manager.config().message("sign.invalid-wager", "§cGib einen gültigen Betrag ein, zum Beispiel 2500, 2.5k oder 1m."));
                manager.sounds().play(player, "error");
                if (manager.draft(player.getUniqueId()) != null) manager.reopenDraft(player);
                return;
            }
            manager.setWager(player, wager);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { close(event.getPlayer()); }

    private void close(Player player) {
        Location location = sessions.remove(player.getUniqueId());
        if (location != null) restore(player, location);
    }

    private void restore(Player player, Location location) {
        if (!player.isOnline()) return;
        Block block = location.getBlock();
        player.sendBlockChange(location, block.getBlockData());
    }

    private Long parse(String input) {
        if (input == null) return null;
        String text = input.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace(',', '.');
        if (text.isEmpty()) return null;
        BigDecimal multiplier = BigDecimal.ONE;
        if (text.endsWith("k")) { multiplier = BigDecimal.valueOf(1_000L); text = text.substring(0, text.length() - 1); }
        else if (text.endsWith("m")) { multiplier = BigDecimal.valueOf(1_000_000L); text = text.substring(0, text.length() - 1); }
        else if (text.endsWith("b")) { multiplier = BigDecimal.valueOf(1_000_000_000L); text = text.substring(0, text.length() - 1); }
        try {
            BigDecimal value = new BigDecimal(text).multiply(multiplier).setScale(0, RoundingMode.HALF_UP);
            if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(manager.config().maximumWager())) > 0) return null;
            return value.longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }
}
