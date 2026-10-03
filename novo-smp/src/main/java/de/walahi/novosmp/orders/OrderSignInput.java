package de.walahi.novosmp.orders;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.GuiNavigator;
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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Virtual sign input used by the order creation editor. No real sign is placed. */
public final class OrderSignInput implements Listener {
    private final SMPCorePlugin plugin;
    private final OrderMenu menu;
    private final OrderMenuConfig config;
    private final GuiNavigator navigator;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    public OrderSignInput(SMPCorePlugin plugin, OrderMenu menu, GuiNavigator navigator) {
        this.plugin = plugin;
        this.menu = menu;
        this.config = new OrderMenuConfig(plugin);
        this.navigator = navigator;
    }

    public void openAmount(Player player) { open(player, Step.AMOUNT); }
    public void openPrice(Player player) { open(player, Step.PRICE); }
    public void openSearch(Player player) { open(player, Step.SEARCH); }

    private void open(Player player, Step step) {
        close(player.getUniqueId(), false);
        // Nutze fuer Menge und Preis bewusst unterschiedliche virtuelle Positionen.
        // Einige Clients öffnen denselben virtuellen Sign-Editor direkt hintereinander
        // nicht zuverlässig erneut, obwohl serverseitig eine neue Session existiert.
        int yOffset = switch (step) {
            case AMOUNT -> 2;
            case PRICE -> 3;
            case SEARCH -> 4;
        };
        Location location = player.getLocation().getBlock().getLocation().add(0, yOffset, 0);
        Session session = new Session(location, step);
        sessions.put(player.getUniqueId(), session);
        navigator.setExternalInput(player, true);
        player.closeInventory();

        // Erst das Inventar sauber schließen lassen, dann den virtuellen Block senden
        // und den Editor einen Tick später öffnen. Gerade die zweite Eingabe
        // (Stückpreis) wurde sonst vom Client häufig verschluckt.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session || !player.isOnline()) return;
            player.sendBlockChange(location, Bukkit.createBlockData(Material.OAK_SIGN));
            // Die Zahl wird direkt in Zeile 1 eingegeben. Die restlichen Zeilen erklaeren die Eingabe.
            List<String> configuredLines = switch (step) {
                case AMOUNT -> configStrings("input.amount.sign-lines",
                        List.of("", "Menge eingeben", "1 - 1.000.000", "Items"));
                case PRICE -> configStrings("input.price.sign-lines",
                        List.of("", "Preis eingeben", "pro Item", "mind. %minimum% Coins"));
                case SEARCH -> configStrings("input.search.sign-lines",
                        List.of("", "Item suchen", "leer = alles", ""));
            };
            String[] signLines = configuredLines.stream()
                    .map(line -> line
                            .replace("%minimum%", step == Step.AMOUNT
                                    ? Integer.toString(menu.minimumAmount())
                                    : Long.toString(menu.minimumPricePerItem()))
                            .replace("%maximum%", step == Step.AMOUNT
                                    ? Integer.toString(menu.maximumAmount())
                                    : Long.toString(menu.maximumPricePerItem())))
                    .limit(4)
                    .toArray(String[]::new);
            if (signLines.length < 4) signLines = java.util.Arrays.copyOf(signLines, 4);
            player.sendSignChange(location, signLines);

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sessions.get(player.getUniqueId()) != session || !player.isOnline()) return;
                player.openVirtualSign(Position.block(location), Side.FRONT);
            }, 1L);
        }, 1L);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessions.get(player.getUniqueId()) != session) return;
            close(player.getUniqueId(), true);
            if (player.isOnline()) {
                menu.sendMessage(player, "input-timeout", "<yellow>Die Eingabe ist abgelaufen.</yellow>");
                if (step == Step.SEARCH) menu.reopenItemSelection(player);
                else menu.reopenCreationEditor(player);
            }
        }, Math.max(20L, config.integer("input.timeout-ticks", 1200)));
    }

    @EventHandler
    public void onChange(UncheckedSignChangeEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        // Es kann immer nur eine aktive virtuelle Schildeingabe pro Spieler geben.
        // Deshalb nicht zusätzlich auf die vom Client gemeldete Blockposition prüfen:
        // Einige Clients melden beim zweiten Öffnen (z. B. Stückpreis nach Menge)
        // kurzzeitig die vorherige virtuelle Position und der Input würde ignoriert.
        if (session == null) return;

        event.setCancelled(true);
        sessions.remove(player.getUniqueId());
        String raw = enteredText(event.lines());
        Bukkit.getScheduler().runTask(plugin, () -> {
            restore(player, session.location());
            navigator.setExternalInput(player, false);
            handle(player, session.step(), raw);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer().getUniqueId(), false);
    }

    private void handle(Player player, Step step, String raw) {
        if (!player.isOnline()) return;
        if (step == Step.SEARCH) {
            menu.setSearch(player, raw);
            menu.reopenItemSelection(player);
            return;
        }
        if (step == Step.AMOUNT) {
            Integer amount = parseAmount(raw);
            if (amount == null) {
                menu.sendMessage(player, "input-amount-invalid",
                        "<red>Gib eine gültige Menge zwischen %minimum% und %maximum% ein.</red>",
                        Map.of(
                                "%minimum%", Integer.toString(minimumAmount()),
                                "%maximum%", Integer.toString(maximumAmount())
                        ));
            } else {
                menu.setDraftAmount(player, amount);
            }
            menu.reopenCreationEditor(player);
            return;
        }

        Long price = parsePrice(raw);
        long min = menu.minimumPricePerItem();
        long max = menu.maximumPricePerItem();
        if (price == null || price < min || price > max) {
            menu.sendMessage(player, "input-price-invalid",
                    "<red>Gib einen gültigen Stückpreis zwischen %minimum% und %maximum% Coins ein, "
                            + "zum Beispiel 25, 2.5k oder 1m.</red>",
                    Map.of(
                            "%minimum%", Long.toString(min),
                            "%maximum%", Long.toString(max)
                    ));
        } else {
            menu.setDraftPrice(player, price);
        }
        menu.reopenCreationEditor(player);
    }

    private int minimumAmount() {
        return menu.minimumAmount();
    }

    private int maximumAmount() {
        return menu.maximumAmount();
    }

    private List<String> configStrings(String path, List<String> fallback) {
        List<String> configured = plugin.configs().orders().getStringList("order-ui." + path);
        return configured.isEmpty() ? fallback : configured;
    }

    private Integer parseAmount(String raw) {
        if (raw == null) return null;
        String text = raw.trim().replace(" ", "").replace("_", "");
        try {
            int value = Integer.parseInt(text);
            return value >= minimumAmount() && value <= maximumAmount() ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long parsePrice(String raw) {
        if (raw == null) return null;
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        if (text.isEmpty()) return null;
        double multiplier = 1D;
        if (text.endsWith("k")) { multiplier = 1_000D; text = text.substring(0, text.length() - 1); }
        else if (text.endsWith("m")) { multiplier = 1_000_000D; text = text.substring(0, text.length() - 1); }
        else if (text.endsWith("b")) { multiplier = 1_000_000_000D; text = text.substring(0, text.length() - 1); }
        text = text.replace(',', '.');
        try {
            double value = Double.parseDouble(text) * multiplier;
            if (!Double.isFinite(value) || value <= 0 || value > Long.MAX_VALUE) return null;
            return Math.round(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String enteredText(List<Component> lines) {
        // Menge bzw. Preis werden direkt aus Zeile 1 gelesen.
        if (!lines.isEmpty()) {
            return plain.serialize(lines.get(0)).trim();
        }
        return "";
    }

    private void close(UUID id, boolean unblock) {
        Session session = sessions.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (session != null && player != null && player.isOnline()) restore(player, session.location());
        if (unblock && player != null) navigator.setExternalInput(player, false);
    }

    private void restore(Player player, Location location) {
        Block block = location.getBlock();
        player.sendBlockChange(location, block.getBlockData());
    }

    private enum Step { AMOUNT, PRICE, SEARCH }
    private record Session(Location location, Step step) {}
}
