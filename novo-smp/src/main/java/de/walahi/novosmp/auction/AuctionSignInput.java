package de.walahi.novosmp.auction;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.GuiNavigator;
import de.walahi.smpcore.gui.MenuFormat;
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
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Opens Paper's virtual sign editor without changing a real block. */
public final class AuctionSignInput implements Listener {
    private final SMPCorePlugin plugin;
    private final AuctionMenu menu;
    private final GuiNavigator navigator;
    private final AuctionMenuConfig config;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    AuctionSignInput(SMPCorePlugin plugin, AuctionMenu menu,
                     GuiNavigator navigator, AuctionMenuConfig config) {
        this.plugin = plugin;
        this.menu = menu;
        this.navigator = navigator;
        this.config = config;
    }

    void open(Player player, ItemStack snapshot) {
        close(player.getUniqueId());
        Location location = player.getLocation().getBlock().getLocation().add(0, 2, 0);
        Session session = new Session(location, snapshot.clone());
        sessions.put(player.getUniqueId(), session);
        navigator.setExternalInput(player, true);

        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Session current = sessions.get(player.getUniqueId());
            if (current != session || !player.isOnline()) return;
            Material material = virtualSignMaterial();
            player.sendBlockChange(location, Bukkit.createBlockData(material));
            player.sendSignChange(location, signLines());
            player.openVirtualSign(Position.block(location), Side.FRONT);
        });

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Session current = sessions.get(player.getUniqueId());
            if (current != session) return;
            close(player.getUniqueId());
            if (player.isOnline()) {
                menu.sendMessage(player, "input-timeout",
                        "<yellow>Die Preiseingabe ist abgelaufen.</yellow>");
            }
        }, config.inputTimeoutTicks());
    }

    @EventHandler
    public void onVirtualSignChange(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !sameBlock(session.location(), event.getEditedBlockPosition())) return;

        event.setCancelled(true);
        sessions.remove(player.getUniqueId());
        String raw = enteredPriceLine(event.lines());
        Bukkit.getScheduler().runTask(plugin, () -> {
            restoreClientBlock(player, session.location());
            navigator.setExternalInput(player, false);
            handlePrice(player, session, raw);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer().getUniqueId());
    }

    private void handlePrice(Player player, Session session, String raw) {
        if (!player.isOnline()) return;
        Long price = AuctionPriceParser.parse(raw);
        if (price == null) {
            menu.sendMessage(player, "input-invalid",
                    "<red>Gib einen gültigen Preis ein, zum Beispiel 2500, 2.5k oder 1m.</red>");
            menu.openListings(player, 0);
            return;
        }
        if (price < menu.minimumPrice() || price > menu.maximumPrice()) {
            menu.sendMessage(player, "input-range",
                    "<red>Der Preis muss zwischen <yellow>%min%</yellow> und <yellow>%max%</yellow> Coins liegen.</red>",
                    Map.of(
                            "%min%", MenuFormat.integer(menu.minimumPrice()),
                            "%max%", MenuFormat.integer(menu.maximumPrice())
                    ));
            menu.openListings(player, 0);
            return;
        }
        menu.openSellConfirmation(player, price, session.snapshot());
    }

    private String enteredPriceLine(List<Component> lines) {
        int configured = config.integer("input.line-index", 0);
        int index = Math.max(0, Math.min(3, configured));
        return lines.size() > index ? plain.serialize(lines.get(index)).trim() : "";
    }

    private Material virtualSignMaterial() {
        Material configured = config.material("input.material", Material.OAK_SIGN);
        String name = configured.name();
        if (!configured.isBlock() || !name.endsWith("_SIGN") || name.contains("WALL")) {
            plugin.getLogger().warning("auctionhouse.yml: auction-ui.input.material muss ein stehendes Schild sein; OAK_SIGN wird verwendet.");
            return Material.OAK_SIGN;
        }
        return configured;
    }

    private String[] signLines() {
        List<String> configured = config.strings("input.lines",
                List.of("", "Preis eingeben", "Gesamtpreis", "Coins"));
        String[] lines = new String[4];
        for (int index = 0; index < lines.length; index++) {
            lines[index] = index < configured.size() ? configured.get(index) : "";
        }
        return lines;
    }

    private void close(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            restoreClientBlock(player, session.location());
            navigator.setExternalInput(player, false);
        }
    }

    private void restoreClientBlock(Player player, Location location) {
        Block realBlock = location.getBlock();
        player.sendBlockChange(location, realBlock.getBlockData());
    }

    private boolean sameBlock(Location location, io.papermc.paper.math.BlockPosition position) {
        return location.getBlockX() == position.blockX()
                && location.getBlockY() == position.blockY()
                && location.getBlockZ() == position.blockZ();
    }

    private record Session(Location location, ItemStack snapshot) {
    }
}
