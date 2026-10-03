package de.walahi.novosmp.enchants;

import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Laufzeitlogik für 3x3-Werkzeuge, Veinminer, Schmelzer und TNT.
 *
 * <p>3x3 und Veinminer schließen sich aus. Beide dürfen jeweils mit Schmelzer
 * kombiniert werden. TNT ist exklusiv und läuft niemals zusammen mit einer anderen
 * Mining-Fähigkeit.</p>
 */
public final class MiningEnchantListener implements Listener {
    private final SMPCorePlugin plugin;
    private final CustomEnchantmentService enchantments;
    private final MiningEnchantConfig config;
    private final MiningBlockRules blockRules;
    private final MiningDropService drops;
    private final MiningDurabilityGuard durabilityGuard;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final NamespacedKey toolInstanceKey;
    private final NamespacedKey tntOwnerKey;

    private final Map<UUID, Long> tntCooldowns = new HashMap<>();
    private final Set<UUID> pendingTnt = new HashSet<>();
    private final Set<String> loggedInvalidCombinations = new HashSet<>();
    private final Map<UUID, EfficiencySuppression> obsidianEfficiency = new HashMap<>();
    private boolean processingAdditionalBlocks;

    public MiningEnchantListener(SMPCorePlugin plugin,
                                 CustomEnchantmentService enchantments,
                                 MiningEnchantConfig config) {
        this.plugin = plugin;
        this.enchantments = enchantments;
        this.config = config;
        this.blockRules = new MiningBlockRules(config);
        this.drops = new MiningDropService(config, enchantments);
        this.durabilityGuard = new MiningDurabilityGuard(config);
        this.toolInstanceKey = new NamespacedKey(plugin, "mining_tool_instance");
        this.tntOwnerKey = new NamespacedKey(plugin, "tnt_pickaxe_owner");
    }


    /**
     * Netherite-3x3 darf Obsidian abbauen, Efficiency soll diesen Spezialabbau aber
     * ausdrücklich nicht beschleunigen. Während der Spieler den Obsidian anschlägt,
     * wird deshalb nur Efficiency temporär vom konkreten Werkzeug entfernt. Sobald
     * der Abbau endet, wird exakt die vorherige Stufe wiederhergestellt.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!config.enabled() || !config.netheriteObsidianThreeByThree()) return;
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (event.getBlock().getType() != Material.OBSIDIAN
                || tool == null || tool.getType() != Material.NETHERITE_PICKAXE
                || enchantments.level(tool, MiningEnchantConfig.THREE_BY_THREE_ID) <= 0) {
            restoreObsidianEfficiency(player);
            return;
        }

        int level = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (level <= 0 || obsidianEfficiency.containsKey(player.getUniqueId())) return;
        String instance = ensureToolInstance(tool);
        ItemMeta meta = tool.getItemMeta();
        if (meta == null || !meta.removeEnchant(Enchantment.EFFICIENCY)) return;
        tool.setItemMeta(meta);
        obsidianEfficiency.put(player.getUniqueId(), new EfficiencySuppression(instance, level));
        player.updateInventory();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        restoreObsidianEfficiency(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processingAdditionalBlocks || !config.enabled()) return;

        Player player = event.getPlayer();
        restoreObsidianEfficiency(player);
        if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        int threeByThree = enchantments.level(tool, MiningEnchantConfig.THREE_BY_THREE_ID);
        int veinminer = enchantments.level(tool, MiningEnchantConfig.VEINMINER_ID);
        int smelter = enchantments.level(tool, MiningEnchantConfig.SMELTER_ID);
        int tnt = enchantments.level(tool, MiningEnchantConfig.TNT_ID);
        if (threeByThree <= 0 && veinminer <= 0 && smelter <= 0 && tnt <= 0) return;

        if (tnt > 0) {
            if (threeByThree > 0 || veinminer > 0 || smelter > 0) {
                warnInvalidCombination("tnt+andere", "TNT wurde zusammen mit einer anderen Mining-Verzauberung gefunden. Nur TNT wird ausgeführt.");
            }
            handleTnt(event, player, tool);
            return;
        }

        if (threeByThree > 0 && veinminer > 0) {
            warnInvalidCombination("3x3+veinminer",
                    "3x3 und Veinminer wurden auf demselben Item gefunden. Nur 3x3 wird ausgeführt.");
            veinminer = 0;
        }

        Block origin = event.getBlock();
        List<ExpectedBlock> additional = List.of();
        boolean areaActive = threeByThree > 0
                && blockRules.canMineWithThreeByThreeTool(origin, tool, player);
        boolean veinActive = false;

        if (areaActive) {
            additional = collectThreeByThree(player, origin, tool);
        } else if (veinminer > 0) {
            String family = config.oreFamily(origin.getType());
            veinActive = family != null && blockRules.canMineWithPickaxe(origin, tool, player);
            if (veinActive) additional = collectVein(origin, tool, player, family);
        }

        MiningDropService.SmeltedDrops originSmelted = smelter > 0
                ? drops.prepareSmelter(origin, tool, player)
                : null;
        if (additional.isEmpty() && originSmelted == null) return;

        String toolInstance = ensureToolInstance(tool);
        // Die Eisen-Veinminer-Version ist bewusst langlebiger: Eine komplette
        // Erzader kostet insgesamt nur den einen Vanilla-Haltbarkeitspunkt des
        // angeschlagenen Ursprungsblocks. Höherwertige Veinminer und 3x3 bleiben
        // weiterhin bei einem Haltbarkeitspunkt pro tatsächlich abgebautem Block.
        boolean ironVeinSingleDurability = veinActive && tool.getType() == Material.IRON_PICKAXE;
        long requiredDurability = ironVeinSingleDurability ? 1L : 1L + additional.size();
        if (!durabilityGuard.allow(player, tool, toolInstance, requiredDurability)) {
            event.setCancelled(true);
            return;
        }

        if (originSmelted != null) drops.suppressOriginDrops(event);
        MiningOperation operation = new MiningOperation(
                player.getUniqueId(),
                tool.getType(),
                toolInstance,
                ExpectedBlock.of(origin),
                originSmelted,
                threeByThree > 0,
                veinActive,
                smelter > 0,
                additional
        );
        Bukkit.getScheduler().runTask(plugin, () -> execute(operation));
    }

    private void execute(MiningOperation operation) {
        // Falls ein später laufendes Schutzplugin den ursprünglichen BlockBreakEvent
        // abgebrochen hat, steht der angeschlagene Block noch unverändert. Dann darf
        // auch kein zusätzlicher Block entfernt werden.
        World originWorld = Bukkit.getWorld(operation.origin().worldId());
        if (originWorld == null) return;
        Block currentOrigin = originWorld.getBlockAt(
                operation.origin().x(), operation.origin().y(), operation.origin().z());
        if (currentOrigin.getType() == operation.origin().material()) return;

        Player player = Bukkit.getPlayer(operation.playerId());
        if (player == null || !player.isOnline()) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isExpectedTool(tool, operation)) return;
        if (operation.originSmelted() != null) {
            drops.deliver(player, tool, currentOrigin.getLocation(), operation.originSmelted());
        }

        if (operation.blocks().isEmpty()) return;
        // Zusätzliche 3x3- und Veinminer-Blöcke werden mit Vanilla-Physik entfernt.
        // Ohne diese Neighbor-Updates bleiben z. B. Wasser/Lava neben einer per
        // Veinminer entfernten Ader sichtbar stehen, bis später irgendein anderer
        // Block ein Update auslöst.
        boolean additionalBlockPhysics = operation.threeByThree() || operation.veinminer();
        boolean ironVeinSingleDurability = operation.veinminer()
                && operation.toolMaterial() == Material.IRON_PICKAXE;

        processingAdditionalBlocks = true;
        try {
            for (ExpectedBlock expected : operation.blocks()) {
                World world = Bukkit.getWorld(expected.worldId());
                if (world == null) continue;
                Block block = world.getBlockAt(expected.x(), expected.y(), expected.z());
                if (block.getType() != expected.material()) continue;
                boolean mineable = operation.threeByThree()
                        ? blockRules.canMineWithThreeByThreeTool(block, tool, player)
                        : blockRules.canMineWithPickaxe(block, tool, player);
                if (!mineable) continue;

                // Zusatzblöcke werden mit Physikupdates entfernt. Dadurch fließen
                // Flüssigkeiten nach und Sand/Gravel bzw. aufliegende Blöcke reagieren
                // wie bei normalem Vanilla-Abbau.
                if (!drops.breakAdditional(player, tool, block, operation.smelter(), additionalBlockPhysics)) continue;
                if (!ironVeinSingleDurability && !MiningToolDurability.damage(player, tool, 1)) break;
                tool = player.getInventory().getItemInMainHand();
                if (!isExpectedTool(tool, operation)) break;
            }
        } finally {
            processingAdditionalBlocks = false;
        }
    }

    private List<ExpectedBlock> collectThreeByThree(Player player, Block origin, ItemStack tool) {
        BlockFace face = player.getTargetBlockFace(6);
        if (face == null || face == BlockFace.SELF) face = fallbackFace(player);

        // Obsidian ist eine ausdrückliche Netherite-3x3-Sonderregel und darf nicht
        // beiläufig verschwinden, wenn der Spieler nur einen benachbarten Stein abbaut.
        boolean obsidianMode = origin.getType() == Material.OBSIDIAN
                && tool.getType() == Material.NETHERITE_PICKAXE
                && config.netheriteObsidianThreeByThree();

        List<ExpectedBlock> result = new ArrayList<>(8);
        for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
                if (first == 0 && second == 0) continue;
                Block candidate = switch (face) {
                    case UP, DOWN -> origin.getRelative(first, 0, second);
                    case EAST, WEST -> origin.getRelative(0, second, first);
                    default -> origin.getRelative(first, second, 0);
                };
                if (candidate.getType() == Material.OBSIDIAN && !obsidianMode) continue;
                if (!blockRules.canMineWithThreeByThreeTool(candidate, tool, player)) continue;
                result.add(ExpectedBlock.of(candidate));
            }
        }
        return List.copyOf(result);
    }

    private BlockFace fallbackFace(Player player) {
        Vector direction = player.getEyeLocation().getDirection();
        double x = Math.abs(direction.getX());
        double y = Math.abs(direction.getY());
        double z = Math.abs(direction.getZ());
        if (y >= x && y >= z) return direction.getY() >= 0 ? BlockFace.DOWN : BlockFace.UP;
        if (x >= z) return direction.getX() >= 0 ? BlockFace.WEST : BlockFace.EAST;
        return direction.getZ() >= 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private List<ExpectedBlock> collectVein(Block origin, ItemStack tool, Player player, String family) {
        int maximum = config.veinMaxBlocks();
        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        List<ExpectedBlock> result = new ArrayList<>(Math.max(0, maximum - 1));

        visited.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && result.size() + 1 < maximum) {
            Block current = queue.removeFirst();
            for (Block neighbour : neighbours(current, config.veinIncludeDiagonals())) {
                if (result.size() + 1 >= maximum) break;
                if (!visited.add(neighbour)) continue;
                if (!blockRules.sameOreFamily(neighbour, family)) continue;
                if (!blockRules.canMineWithPickaxe(neighbour, tool, player)) continue;
                result.add(ExpectedBlock.of(neighbour));
                queue.addLast(neighbour);
            }
        }
        return List.copyOf(result);
    }

    private List<Block> neighbours(Block block, boolean diagonals) {
        List<Block> result = new ArrayList<>(diagonals ? 26 : 6);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    if (!diagonals && Math.abs(x) + Math.abs(y) + Math.abs(z) != 1) continue;
                    result.add(block.getRelative(x, y, z));
                }
            }
        }
        return result;
    }

    private void handleTnt(BlockBreakEvent event, Player player, ItemStack tool) {
        UUID playerId = player.getUniqueId();
        String toolInstance = ensureToolInstance(tool);
        long remaining = remainingTntCooldown(playerId);
        if (remaining > 0) {
            if (!durabilityGuard.allow(player, tool, toolInstance, 1)) {
                event.setCancelled(true);
                return;
            }
            sendTntCooldown(player, remaining);
            return; // Der einzelne Block wird während des Cooldowns normal abgebaut.
        }
        if (pendingTnt.contains(playerId)) {
            if (!durabilityGuard.allow(player, tool, toolInstance, 1)) event.setCancelled(true);
            return;
        }
        if (!durabilityGuard.allow(player, tool, toolInstance, 1)) {
            event.setCancelled(true);
            return;
        }

        Block origin = event.getBlock();
        ExpectedBlock expected = ExpectedBlock.of(origin);
        pendingTnt.add(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingTnt.remove(playerId);
            World world = Bukkit.getWorld(expected.worldId());
            if (world == null) return;
            Block current = world.getBlockAt(expected.x(), expected.y(), expected.z());
            // Ist der gleiche Block noch vorhanden, wurde der ursprüngliche Abbau später doch verhindert.
            if (current.getType() == expected.material()) return;

            Location spawn = new Location(world,
                    expected.x() + 0.5D,
                    expected.y() + 0.5D,
                    expected.z() + 0.5D);
            Player source = Bukkit.getPlayer(playerId);
            world.spawn(spawn, TNTPrimed.class, primed -> {
                primed.setFuseTicks(config.tntFuseTicks());
                primed.setYield(config.tntPower());
                primed.setIsIncendiary(config.tntIncendiary());
                if (source != null && source.isOnline()) primed.setSource(source);
                primed.getPersistentDataContainer().set(
                        tntOwnerKey,
                        PersistentDataType.STRING,
                        playerId.toString()
                );
            });
            if (config.tntCooldownSeconds() > 0) {
                tntCooldowns.put(playerId,
                        System.currentTimeMillis() + config.tntCooldownSeconds() * 1000L);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTntDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getDamager() instanceof TNTPrimed primed)) return;
        String owner = primed.getPersistentDataContainer().get(tntOwnerKey, PersistentDataType.STRING);
        if (owner != null && owner.equals(player.getUniqueId().toString())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restoreObsidianEfficiency(event.getPlayer());
        UUID playerId = event.getPlayer().getUniqueId();
        tntCooldowns.remove(playerId);
        pendingTnt.remove(playerId);
        durabilityGuard.clear(event.getPlayer());
    }

    private boolean isExpectedTool(ItemStack tool, MiningOperation operation) {
        if (tool == null || tool.getType().isAir() || tool.getType() != operation.toolMaterial()) return false;
        if (operation.toolInstance() == null
                || !operation.toolInstance().equals(readToolInstance(tool))) return false;
        if (operation.threeByThree()
                && enchantments.level(tool, MiningEnchantConfig.THREE_BY_THREE_ID) <= 0) return false;
        if (operation.veinminer()
                && enchantments.level(tool, MiningEnchantConfig.VEINMINER_ID) <= 0) return false;
        return !operation.smelter()
                || enchantments.level(tool, MiningEnchantConfig.SMELTER_ID) > 0;
    }

    private String ensureToolInstance(ItemStack tool) {
        String existing = readToolInstance(tool);
        if (existing != null) return existing;
        ItemMeta meta = tool.getItemMeta();
        String generated = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(toolInstanceKey, PersistentDataType.STRING, generated);
        tool.setItemMeta(meta);
        return generated;
    }

    private String readToolInstance(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta()) return null;
        return tool.getItemMeta().getPersistentDataContainer()
                .get(toolInstanceKey, PersistentDataType.STRING);
    }


    private void restoreObsidianEfficiency(Player player) {
        if (player == null) return;
        EfficiencySuppression suppression = obsidianEfficiency.remove(player.getUniqueId());
        if (suppression == null) return;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            if (!suppression.toolInstance().equals(readToolInstance(item))) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) break;
            if (meta.getEnchantLevel(Enchantment.EFFICIENCY) < suppression.level()) {
                meta.addEnchant(Enchantment.EFFICIENCY, suppression.level(), true);
                item.setItemMeta(meta);
            }
            player.updateInventory();
            break;
        }
    }

    private long remainingTntCooldown(UUID playerId) {
        Long until = tntCooldowns.get(playerId);
        if (until == null) return 0L;
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0L) {
            tntCooldowns.remove(playerId);
            return 0L;
        }
        return remaining;
    }

    private void sendTntCooldown(Player player, long remainingMillis) {
        String raw = config.tntCooldownMessage();
        if (raw == null || raw.isBlank()) return;
        long seconds = Math.max(1L, (long) Math.ceil(remainingMillis / 1000.0D));
        var message = miniMessage.deserialize(raw.replace("%seconds%", String.valueOf(seconds)));
        if (config.tntCooldownActionbar()) player.sendActionBar(message);
        else player.sendMessage(message);
    }

    private void warnInvalidCombination(String key, String detail) {
        if (!loggedInvalidCombinations.add(key)) return;
        plugin.getLogger().warning("Ungültige Custom-Enchantment-Kombination: " + detail);
    }

    public void clearRuntimeState() {
        tntCooldowns.clear();
        pendingTnt.clear();
        loggedInvalidCombinations.clear();
        obsidianEfficiency.clear();
        durabilityGuard.clearAll();
    }

    private record EfficiencySuppression(String toolInstance, int level) {}

    private record MiningOperation(
            UUID playerId,
            Material toolMaterial,
            String toolInstance,
            ExpectedBlock origin,
            MiningDropService.SmeltedDrops originSmelted,
            boolean threeByThree,
            boolean veinminer,
            boolean smelter,
            List<ExpectedBlock> blocks
    ) {}

    private record ExpectedBlock(UUID worldId, int x, int y, int z, Material material) {
        static ExpectedBlock of(Block block) {
            return new ExpectedBlock(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    block.getType()
            );
        }
    }
}
