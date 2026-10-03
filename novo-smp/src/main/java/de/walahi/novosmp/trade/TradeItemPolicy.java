package de.walahi.novosmp.trade;

import de.walahi.novosmp.angler.FishRegistry;
import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.block.ShulkerBox;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Zentrale Regeln für handelbare Items. Alle Handelsfeatures verwenden dieselbe
 * Material-Basis und können nur noch kanalspezifische Einschränkungen ergänzen.
 */
public final class TradeItemPolicy {
    public enum Channel {
        ORDERS("orders"),
        AUCTION_HOUSE("auction-house"),
        SELL("sell"),
        WORTH("worth");

        private final String configKey;

        Channel(String configKey) {
            this.configKey = configKey;
        }
    }

    private final SMPCorePlugin plugin;
    private FishRegistry fishRegistry;
    private final Set<Material> globallyBlocked = EnumSet.noneOf(Material.class);
    private final Map<Channel, Set<Material>> channelBlocked = new EnumMap<>(Channel.class);

    private List<String> blockedExactNames = List.of();
    private List<String> blockedSuffixes = List.of();
    private List<String> blockedFragments = List.of();

    private boolean orderBlockCustomModelData;
    private boolean orderBlockPersistentData;
    private boolean auctionBlockCustomModelData;
    private boolean auctionBlockPersistentData;
    private boolean sellBlockCustomModelData;
    private boolean sellBlockPersistentData;
    private boolean sellBlockFilledBundles;
    private boolean sellBlockFilledShulkers;
    private boolean sellBlockOwnedHeads;
    private boolean sellBlockFilledMaps;
    private boolean sellBlockWrittenBooks;

    public TradeItemPolicy(SMPCorePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        for (Channel channel : Channel.values()) {
            channelBlocked.put(channel, EnumSet.noneOf(Material.class));
        }
        reload();
    }

    /** Only registered fish bypass the Sell channel's generic custom/PDC guards. */
    public void fishRegistry(FishRegistry registry) { this.fishRegistry = registry; }

    public void reload() {
        FileConfiguration config = plugin.configs().trade();
        globallyBlocked.clear();
        readMaterials(config.getStringList("trade.blocked-materials"), globallyBlocked, "trade.blocked-materials");

        blockedExactNames = normalize(config.getStringList("trade.blocked-name-exact"));
        blockedSuffixes = normalize(config.getStringList("trade.blocked-name-suffixes"));
        blockedFragments = normalize(config.getStringList("trade.blocked-name-contains"));

        for (Channel channel : Channel.values()) {
            Set<Material> target = channelBlocked.get(channel);
            target.clear();
            readMaterials(config.getStringList("trade.channels." + channel.configKey + ".blocked-materials"),
                    target, "trade.channels." + channel.configKey + ".blocked-materials");
        }

        // Alte Server dürfen ihre bisherige Sell-Sperrliste ohne Dateimigration weiterverwenden.
        readMaterials(plugin.configs().main().getStringList("economy.sell.blocked-materials"),
                channelBlocked.get(Channel.SELL), "economy.sell.blocked-materials");

        orderBlockCustomModelData = config.getBoolean("trade.channels.orders.block-custom-model-data", true);
        orderBlockPersistentData = config.getBoolean("trade.channels.orders.block-persistent-data", true);
        auctionBlockCustomModelData = config.getBoolean("trade.channels.auction-house.block-custom-model-data", false);
        auctionBlockPersistentData = config.getBoolean("trade.channels.auction-house.block-persistent-data", false);

        sellBlockCustomModelData = config.getBoolean("trade.channels.sell.block-custom-model-data", true);
        sellBlockPersistentData = config.getBoolean("trade.channels.sell.block-persistent-data", true);
        sellBlockFilledBundles = config.getBoolean("trade.channels.sell.block-filled-bundles", true);
        sellBlockFilledShulkers = config.getBoolean("trade.channels.sell.block-filled-shulkers", true);
        sellBlockOwnedHeads = config.getBoolean("trade.channels.sell.block-owned-player-heads", true);
        sellBlockFilledMaps = config.getBoolean("trade.channels.sell.block-filled-maps", true);
        sellBlockWrittenBooks = config.getBoolean("trade.channels.sell.block-written-books", true);
    }

    public boolean isOrderMaterialAllowed(Material material) {
        return isMaterialAllowed(material, Channel.ORDERS);
    }

    public boolean isOrderItemAllowed(ItemStack stack) {
        if (!isStackAllowed(stack, Channel.ORDERS)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return true;
        if (orderBlockCustomModelData && meta.hasCustomModelData()) return false;
        return !orderBlockPersistentData || meta.getPersistentDataContainer().getKeys().isEmpty();
    }

    public boolean isAuctionItemAllowed(ItemStack stack) {
        if (!isStackAllowed(stack, Channel.AUCTION_HOUSE)) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return true;
        if (auctionBlockCustomModelData && meta.hasCustomModelData()) return false;
        return !auctionBlockPersistentData || meta.getPersistentDataContainer().getKeys().isEmpty();
    }

    public boolean isWorthMaterialAllowed(Material material) {
        return isMaterialAllowed(material, Channel.WORTH);
    }

    public boolean isSellItemAllowed(ItemStack stack) {
        if (!isStackAllowed(stack, Channel.SELL)) return false;
        if (fishRegistry != null && fishRegistry.identify(stack) != null) return true;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return true;
        if (sellBlockCustomModelData && meta.hasCustomModelData()) return false;
        if (sellBlockPersistentData && !meta.getPersistentDataContainer().getKeys().isEmpty()) return false;
        if (sellBlockFilledBundles && meta instanceof BundleMeta bundle && !bundle.getItems().isEmpty()) return false;
        if (sellBlockFilledShulkers && meta instanceof BlockStateMeta blockState
                && blockState.getBlockState() instanceof ShulkerBox shulker
                && !shulker.getInventory().isEmpty()) return false;
        if (sellBlockOwnedHeads && meta instanceof SkullMeta skull && skull.hasOwner()) return false;
        // Vanilla maps legitimately carry a MapView/location/color. Only the generic custom-item
        // guards above (PDC/custom model data) make them unsellable.
        return !sellBlockWrittenBooks || !(meta instanceof BookMeta book)
                || (!book.hasAuthor() && !book.hasTitle() && book.getPageCount() == 0);
    }

    public boolean isMaterialAllowed(Material material, Channel channel) {
        if (material == null || !material.isItem() || material.isAir() || material.isLegacy()) return false;
        if (globallyBlocked.contains(material) || channelBlocked.get(channel).contains(material)) return false;

        String name = material.name().toUpperCase(Locale.ROOT);
        if (blockedExactNames.contains(name)) return false;
        for (String suffix : blockedSuffixes) if (name.endsWith(suffix)) return false;
        for (String fragment : blockedFragments) if (name.contains(fragment)) return false;
        return true;
    }

    private boolean isStackAllowed(ItemStack stack, Channel channel) {
        return stack != null && !stack.getType().isAir() && isMaterialAllowed(stack.getType(), channel);
    }

    private void readMaterials(List<String> configured, Set<Material> target, String path) {
        for (String value : configured) {
            Material material = Material.matchMaterial(value);
            if (material == null) {
                plugin.getLogger().warning("Ungültiges Material in " + path + ": " + value);
            } else {
                target.add(material);
            }
        }
    }

    private List<String> normalize(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
