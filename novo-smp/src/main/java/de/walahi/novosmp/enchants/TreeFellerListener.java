package de.walahi.novosmp.enchants;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Baumfäller für die Verzauberung Holzschlag.
 *
 * <p>Beim Abbau eines Holzblocks mit einer verzauberten Axt werden die
 * zusammenhängenden Holzblöcke des Baumes mit entfernt. Umfang, Cooldown und
 * Abbauverhalten kommen je Stufe aus der {@link HolzschlagConfig}.</p>
 *
 * <p><b>Regionsschutz:</b> Für jeden zusätzlichen Block wird ein eigenes
 * {@link BlockBreakEvent} ausgelöst. Nur dadurch kommen WorldGuard und vergleichbare
 * Plugins zum Zug. Ein Wiedereintritts-Schutz verhindert, dass sich der Baumfäller
 * dabei selbst auslöst.</p>
 */
public final class TreeFellerListener implements Listener {
    private final CustomEnchantmentService enchantments;
    private final HolzschlagConfig config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Predicate<ItemStack> toolValidator;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> toolBreakConfirmations = new HashMap<>();

    /** Verhindert, dass die selbst ausgelösten Events erneut einen Baumfäller starten. */
    private boolean felling;

    public TreeFellerListener(CustomEnchantmentService enchantments, HolzschlagConfig config) {
        this(enchantments, config, item -> true);
    }

    public TreeFellerListener(CustomEnchantmentService enchantments, HolzschlagConfig config,
                              Predicate<ItemStack> toolValidator) {
        this.enchantments = enchantments;
        this.config = config;
        this.toolValidator = toolValidator == null ? item -> true : toolValidator;
    }

    /**
     * Bewusst {@code HIGHEST}: Schutz-Plugins arbeiten üblicherweise auf LOWEST bis
     * NORMAL. Liefe der Baumfäller früher, hätte er den Baum bereits gefällt, bevor
     * WorldGuard den ursprünglichen Abbau überhaupt ablehnen konnte.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (felling || !config.enabled()) return;

        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        int level = enchantments.level(tool, HolzschlagConfig.ENCHANTMENT_ID);
        if (level <= 0) return;
        if (!toolValidator.test(tool)) return;

        // Schleichen deaktiviert Holzschlag vollständig. Der Spieler baut dann nur
        // den normalen einzelnen Block ab; ein laufender Holzschlag-Cooldown darf
        // diesen Vanilla-Abbau deshalb weder blockieren noch verlängern.
        if (config.disableWhileSneaking() && player.isSneaking()) return;

        // Außerhalb des Schleichens ist während des Holzschlag-Cooldowns mit der
        // verzauberten Axt kein Blockabbau möglich. So lässt sich der Cooldown nicht
        // umgehen, indem der Spieler den Ausgangsblock normal abbaut.
        long remainingCooldown = remainingCooldown(player);
        if (remainingCooldown > 0) {
            event.setCancelled(true);
            sendCooldown(player, remainingCooldown);
            return;
        }

        Block origin = event.getBlock();
        if (!Tag.LOGS.isTagged(origin.getType())) return;

        HolzschlagLevel settings = config.level(level);
        if (settings.maxBlocks() <= 1) return;

        List<Block> logs = collectLogs(origin, settings);
        if (config.durabilitySafetyEnabled()
                && wouldBreakTool(tool, logs.size(), settings.durabilityPerBlock())
                && !consumeToolBreakConfirmation(player)) {
            event.setCancelled(true);
            warnToolWouldBreak(player);
            return;
        }

        int broken;
        felling = true;
        try {
            broken = fell(player, tool, origin, logs, settings);
        } finally {
            felling = false;
        }

        // Nur wenn tatsächlich etwas zusätzlich gefallen ist. Ein einzelner Holzblock
        // ohne Baum drumherum soll keinen Cooldown auslösen.
        if (broken > 0 && settings.cooldownSeconds() > 0) {
            cooldowns.put(player.getUniqueId(),
                    System.currentTimeMillis() + settings.cooldownSeconds() * 1000L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        cooldowns.remove(playerId);
        toolBreakConfirmations.remove(playerId);
    }

    // ------------------------------------------------------------------
    // Fällen
    // ------------------------------------------------------------------

    /** @return Anzahl der zusätzlich entfernten Blöcke */
    private int fell(Player player, ItemStack tool, Block origin,
                     List<Block> logs, HolzschlagLevel settings) {
        // Die Baumkrone muss vor dem Fällen analysiert werden. Nach dem Entfernen der
        // Stämme verändert Vanilla die distance-Werte der Leaves und die eindeutige
        // Zuordnung zu diesem Baum wäre nicht mehr zuverlässig möglich.
        LeafPlan leafPlan = settings.breakLeaves() && settings.maxLeaves() > 0
                ? planLeaves(origin, logs, settings.maxLeaves())
                : LeafPlan.EMPTY;

        ItemStack activeTool = tool;
        List<Block> brokenLogs = new ArrayList<>(logs.size());
        for (Block log : logs) {
            if (!breakBlock(player, activeTool, log)) continue;
            brokenLogs.add(log);
            if (settings.durabilityPerBlock() > 0 && !damageTool(player, activeTool, settings.durabilityPerBlock())) {
                // Die Axt ist zerbrochen — der Rest des Baumes bleibt stehen.
                activeTool = null;
                break;
            }
        }

        int broken = brokenLogs.size();
        if (!leafPlan.isEmpty()) {
            Set<Block> removedTreeLogs = new HashSet<>(brokenLogs);
            // Der Ausgangsblock wird nach diesem Listener durch das ursprüngliche
            // BlockBreakEvent entfernt und gehört daher ebenfalls zum gefällten Baum.
            removedTreeLogs.add(origin);

            // Blätter kosten bewusst keine Haltbarkeit und werden absichtlich OHNE
            // Werkzeug gebrochen. Dadurch entstehen normale Vanilla-Blattdrops,
            // Fortune/Silk Touch der Axt wirken aber nicht auf den Auto-Abbau.
            broken += breakLeaves(player, leafPlan, removedTreeLogs, settings.maxLeaves());
        }
        return broken;
    }

    /**
     * Sammelt die zusammenhängenden Holzblöcke ab dem abgebauten Block.
     * Der Ausgangsblock selbst zählt zur Obergrenze, wird aber nicht zurückgegeben —
     * um ihn kümmert sich bereits das ursprüngliche Event.
     */
    private List<Block> collectLogs(Block origin, HolzschlagLevel settings) {
        Material species = origin.getType();
        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        List<Block> result = new ArrayList<>();

        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && result.size() + 1 < settings.maxBlocks()) {
            Block current = queue.poll();
            for (Block neighbour : neighbours(current, settings.includeDiagonals())) {
                if (result.size() + 1 >= settings.maxBlocks()) break;
                if (!visited.add(neighbour)) continue;
                if (!Tag.LOGS.isTagged(neighbour.getType())) continue;
                if (settings.sameMaterialOnly() && neighbour.getType() != species) continue;
                result.add(neighbour);
                queue.add(neighbour);
            }
        }
        return result;
    }

    /**
     * Erstellt vor dem Fällen einen Snapshot der natürlichen Baumkrone.
     *
     * <p>Anders als die alte Radius-Logik wird nur entlang der echten Vanilla-
     * Blattverbindungen gesucht. Die {@link Leaves#getDistance()}-Werte werden
     * gespeichert, solange die Stämme noch vorhanden sind. Dadurch können später
     * Blätter eines direkt angrenzenden zweiten Baumes konservativ ausgeschlossen
     * werden.</p>
     */
    private LeafPlan planLeaves(Block origin, List<Block> logs, int maxLeaves) {
        Set<Material> compatibleLeaves = compatibleLeaves(origin.getType());
        if (compatibleLeaves.isEmpty()) return LeafPlan.EMPTY;

        List<Block> treeLogs = new ArrayList<>(logs.size() + 1);
        treeLogs.add(origin);
        treeLogs.addAll(logs);

        Map<Block, Integer> searchDistance = new HashMap<>();
        Map<Block, Integer> vanillaDistance = new HashMap<>();
        Deque<Block> queue = new ArrayDeque<>();

        for (Block log : treeLogs) {
            for (Block neighbour : faceNeighbours(log)) {
                addLeafCandidate(neighbour, 1, compatibleLeaves, searchDistance, vanillaDistance, queue);
            }
        }

        // Wir entfernen maximal maxLeaves, analysieren aber bewusst deutlich mehr
        // Kandidaten. So kann die Besitzzuordnung auch bei großen/überlappenden
        // Kronen korrekt entscheiden, ohne ein riesiges natürliches Blätternetz
        // unbeschränkt zu traversieren.
        int candidateLimit = Math.max(2048, maxLeaves * 16);
        while (!queue.isEmpty() && vanillaDistance.size() < candidateLimit) {
            Block current = queue.poll();
            int distance = searchDistance.getOrDefault(current, Integer.MAX_VALUE);
            if (!(current.getBlockData() instanceof Leaves leaves)) continue;
            if (distance >= leaves.getMaximumDistance()) continue;

            int nextDistance = distance + 1;
            for (Block neighbour : faceNeighbours(current)) {
                addLeafCandidate(neighbour, nextDistance, compatibleLeaves,
                        searchDistance, vanillaDistance, queue);
                if (vanillaDistance.size() >= candidateLimit) break;
            }
        }

        return vanillaDistance.isEmpty()
                ? LeafPlan.EMPTY
                : new LeafPlan(vanillaDistance);
    }

    private void addLeafCandidate(Block block, int searchDepth, Set<Material> compatibleLeaves,
                                  Map<Block, Integer> searchDistance,
                                  Map<Block, Integer> vanillaDistance, Deque<Block> queue) {
        if (!compatibleLeaves.contains(block.getType())) return;
        if (!(block.getBlockData() instanceof Leaves leaves)) return;
        if (config.ignorePlayerPlacedLeaves() && leaves.isPersistent()) return;
        if (searchDepth > leaves.getMaximumDistance()) return;

        Integer oldDistance = searchDistance.get(block);
        if (oldDistance != null && oldDistance <= searchDepth) return;

        searchDistance.put(block, searchDepth);
        vanillaDistance.put(block, leaves.getDistance());
        queue.add(block);
    }

    /** @return Anzahl der entfernten Blätter */
    private int breakLeaves(Player player, LeafPlan plan, Set<Block> removedTreeLogs, int maxLeaves) {
        if (maxLeaves <= 0 || plan.isEmpty() || removedTreeLogs.isEmpty()) return 0;

        Set<Block> candidates = plan.vanillaDistance().keySet();
        Map<Block, Integer> targetDistance = distanceFromTreeLogs(candidates, removedTreeLogs);
        Map<Block, Integer> otherTreeDistance = distanceFromOtherLogs(candidates, removedTreeLogs);

        List<Block> ownedLeaves = new ArrayList<>();
        for (Map.Entry<Block, Integer> entry : plan.vanillaDistance().entrySet()) {
            Block leaf = entry.getKey();
            int originalVanillaDistance = entry.getValue();
            int ownDistance = targetDistance.getOrDefault(leaf, Integer.MAX_VALUE);
            int foreignDistance = otherTreeDistance.getOrDefault(leaf, Integer.MAX_VALUE);

            // Nur Blätter entfernen, deren nächster/alleiniger tragender Stamm aus
            // dem tatsächlich gefällten Baum stammt. Bei Gleichstand mit einem
            // angrenzenden Baum bleibt das Blatt absichtlich stehen. Das ist die
            // sichere Variante für ineinander gewachsene Kronen.
            if (ownDistance == Integer.MAX_VALUE) continue;
            if (ownDistance > originalVanillaDistance) continue;
            if (foreignDistance <= ownDistance) continue;
            ownedLeaves.add(leaf);
        }

        // Von außen nach innen abbauen. Falls bei einem künstlich gigantischen Baum
        // das 512er-Limit greift, bleiben so eher die zentralen Blätter beim noch
        // stehenden Restholz statt weit außen schwebende Inseln übrig.
        ownedLeaves.sort((a, b) -> Integer.compare(
                targetDistance.getOrDefault(b, 0), targetDistance.getOrDefault(a, 0)));

        int removed = 0;
        for (Block leaf : ownedLeaves) {
            if (removed >= maxLeaves) break;
            if (!Tag.LEAVES.isTagged(leaf.getType())) continue;
            if (leaf.getBlockData() instanceof Leaves leaves
                    && config.ignorePlayerPlacedLeaves() && leaves.isPersistent()) {
                continue;
            }
            // null = normale Blattdrops ohne Fortune/Silk Touch der Axt.
            if (breakBlock(player, null, leaf)) removed++;
        }
        return removed;
    }

    /**
     * Kürzeste Blattentfernung zu den Stämmen, die dieser Holzschlag tatsächlich
     * entfernt. Die Stämme dürfen zu diesem Zeitpunkt bereits AIR sein; ihre
     * Positionen dienen deshalb als virtuelle Quellen.
     */
    private Map<Block, Integer> distanceFromTreeLogs(Set<Block> candidates, Set<Block> treeLogs) {
        Map<Block, Integer> distance = new HashMap<>();
        Deque<Block> queue = new ArrayDeque<>();
        for (Block log : treeLogs) {
            for (Block neighbour : faceNeighbours(log)) {
                if (!candidates.contains(neighbour)) continue;
                if (distance.putIfAbsent(neighbour, 1) == null) queue.add(neighbour);
            }
        }
        spreadLeafDistances(candidates, distance, queue);
        return distance;
    }

    /**
     * Kürzeste Blattentfernung zu jedem anderen noch vorhandenen Holzblock.
     * Dadurch schützt eine zweite, direkt angrenzende Baumkrone ihre eigenen
     * Blätter vor dem Holzschlag des Nachbarbaumes.
     */
    private Map<Block, Integer> distanceFromOtherLogs(Set<Block> candidates, Set<Block> removedTreeLogs) {
        Map<Block, Integer> distance = new HashMap<>();
        Deque<Block> queue = new ArrayDeque<>();

        for (Block leaf : candidates) {
            for (Block neighbour : faceNeighbours(leaf)) {
                if (!Tag.LOGS.isTagged(neighbour.getType())) continue;
                if (removedTreeLogs.contains(neighbour)) continue;
                if (distance.putIfAbsent(leaf, 1) == null) queue.add(leaf);
                break;
            }
        }
        spreadLeafDistances(candidates, distance, queue);
        return distance;
    }

    private void spreadLeafDistances(Set<Block> candidates, Map<Block, Integer> distance, Deque<Block> queue) {
        while (!queue.isEmpty()) {
            Block current = queue.poll();
            int currentDistance = distance.get(current);
            if (!(current.getBlockData() instanceof Leaves leaves)) continue;
            if (currentDistance >= leaves.getMaximumDistance()) continue;

            int nextDistance = currentDistance + 1;
            for (Block neighbour : faceNeighbours(current)) {
                if (!candidates.contains(neighbour)) continue;
                Integer old = distance.get(neighbour);
                if (old != null && old <= nextDistance) continue;
                distance.put(neighbour, nextDistance);
                queue.add(neighbour);
            }
        }
    }

    /**
     * Blattarten, die zu einem normalen Overworld-Baum dieser Holzart gehören.
     * Azalea-Bäume verwenden Vanilla-Eichenholz und werden deshalb bei OAK bewusst
     * mit berücksichtigt. Crimson/Warped besitzen keine normalen Leaves und werden
     * hier absichtlich nicht unterstützt.
     */
    private Set<Material> compatibleLeaves(Material logMaterial) {
        String name = logMaterial.name();
        if (name.startsWith("STRIPPED_")) name = name.substring("STRIPPED_".length());

        if (name.startsWith("DARK_OAK_")) return Set.of(Material.DARK_OAK_LEAVES);
        if (name.startsWith("PALE_OAK_")) return Set.of(Material.PALE_OAK_LEAVES);
        if (name.startsWith("OAK_")) {
            return Set.of(Material.OAK_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES);
        }
        if (name.startsWith("SPRUCE_")) return Set.of(Material.SPRUCE_LEAVES);
        if (name.startsWith("BIRCH_")) return Set.of(Material.BIRCH_LEAVES);
        if (name.startsWith("JUNGLE_")) return Set.of(Material.JUNGLE_LEAVES);
        if (name.startsWith("ACACIA_")) return Set.of(Material.ACACIA_LEAVES);
        if (name.startsWith("MANGROVE_")) return Set.of(Material.MANGROVE_LEAVES);
        if (name.startsWith("CHERRY_")) return Set.of(Material.CHERRY_LEAVES);
        return Set.of();
    }

    private List<Block> faceNeighbours(Block block) {
        return List.of(
                block.getRelative(1, 0, 0), block.getRelative(-1, 0, 0),
                block.getRelative(0, 1, 0), block.getRelative(0, -1, 0),
                block.getRelative(0, 0, 1), block.getRelative(0, 0, -1)
        );
    }

    private record LeafPlan(Map<Block, Integer> vanillaDistance) {
        private static final LeafPlan EMPTY = new LeafPlan(Map.of());
        private boolean isEmpty() {
            return vanillaDistance.isEmpty();
        }
    }

    /**
     * Bricht einen Block im Namen des Spielers.
     *
     * <p>Löst zuvor ein eigenes {@link BlockBreakEvent} aus, damit Schutz-Plugins den
     * Abbau verhindern können. Wird das Event abgebrochen, bleibt der Block stehen.</p>
     *
     * @return {@code true}, wenn der Block tatsächlich entfernt wurde
     */
    private boolean breakBlock(Player player, ItemStack tool, Block block) {
        if (block.getType().isAir()) return false;

        boolean dropItems = true;
        if (config.respectProtection()) {
            BlockBreakEvent event = new BlockBreakEvent(block, player);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            dropItems = event.isDropItems();
        }

        if (dropItems && tool != null && enchantments.level(tool, MagnetListener.ID) > 0) {
            List<ItemStack> drops = new ArrayList<>(block.getDrops(tool, player));
            org.bukkit.Location origin = block.getLocation().add(.5, .5, .5);
            block.setType(Material.AIR, false);
            player.getInventory().addItem(drops.toArray(ItemStack[]::new)).values()
                    .forEach(item -> origin.getWorld().dropItemNaturally(origin, item));
        } else if (dropItems && tool != null) block.breakNaturally(tool);
        else if (dropItems) block.breakNaturally();
        else block.setType(Material.AIR, false);
        return true;
    }

    // ------------------------------------------------------------------
    // Haltbarkeit
    // ------------------------------------------------------------------

    /**
     * Verbraucht Haltbarkeit und berücksichtigt dabei Haltbarkeit/Unbreaking wie Vanilla.
     *
     * @return {@code false}, wenn das Werkzeug dabei zerbrochen ist
     */
    private boolean damageTool(Player player, ItemStack tool, int amount) {
        if (tool == null || tool.getType().isAir() || amount <= 0) return true;
        short maxDurability = tool.getType().getMaxDurability();
        if (maxDurability <= 0) return true;

        ItemMeta meta = tool.getItemMeta();
        if (meta == null || meta.isUnbreakable() || !(meta instanceof Damageable damageable)) return true;

        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;
        for (int i = 0; i < amount; i++) {
            // Vanilla-Regel: mit Haltbarkeit greift der Schaden nur mit 1/(Stufe+1).
            if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) continue;
            applied++;
        }
        if (applied == 0) return true;

        int newDamage = damageable.getDamage() + applied;
        if (newDamage >= maxDurability) {
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
            return false;
        }
        damageable.setDamage(newDamage);
        tool.setItemMeta(meta);
        return true;
    }

    /**
     * Prüft konservativ, ob der anstehende Holzschlag die Axt zerstören kann.
     * Unbreaking wird absichtlich nicht als garantierte Rettung eingerechnet: Die
     * Warnung soll einen versehentlichen Verlust zuverlässig verhindern.
     */
    private boolean wouldBreakTool(ItemStack tool, int additionalLogs, int durabilityPerLog) {
        if (tool == null || tool.getType().isAir()) return false;
        short maxDurability = tool.getType().getMaxDurability();
        if (maxDurability <= 0) return false;

        ItemMeta meta = tool.getItemMeta();
        if (meta == null || meta.isUnbreakable() || !(meta instanceof Damageable damageable)) {
            return false;
        }

        int remainingDurability = maxDurability - damageable.getDamage();
        // Der Ausgangsblock kostet Vanilla einen Haltbarkeitspunkt. Jeder weitere
        // Stamm verbraucht den konfigurierten Wert.
        long requiredDurability = 1L + (long) Math.max(0, additionalLogs)
                * Math.max(0, durabilityPerLog);
        return requiredDurability >= remainingDurability;
    }

    /** Verbraucht genau eine noch gültige Bestätigung für den riskanten Schlag. */
    private boolean consumeToolBreakConfirmation(Player player) {
        UUID playerId = player.getUniqueId();
        Long expiresAt = toolBreakConfirmations.remove(playerId);
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    private void warnToolWouldBreak(Player player) {
        int seconds = config.durabilityConfirmationSeconds();
        toolBreakConfirmations.put(player.getUniqueId(),
                System.currentTimeMillis() + seconds * 1000L);

        String message = config.toolWouldBreakMessage();
        if (message == null || message.isBlank()) return;
        player.sendMessage(miniMessage.deserialize(
                message.replace("%seconds%", String.valueOf(seconds))));
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    private List<Block> neighbours(Block block, boolean includeDiagonals) {
        List<Block> result = new ArrayList<>(includeDiagonals ? 26 : 6);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    if (!includeDiagonals && Math.abs(x) + Math.abs(y) + Math.abs(z) != 1) continue;
                    result.add(block.getRelative(x, y, z));
                }
            }
        }
        return result;
    }

    private long remainingCooldown(Player player) {
        Long until = cooldowns.get(player.getUniqueId());
        if (until == null) return 0L;
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            cooldowns.remove(player.getUniqueId());
            return 0L;
        }
        return remaining;
    }

    private void sendCooldown(Player player, long remainingMillis) {
        String message = config.cooldownMessage();
        if (message == null || message.isBlank()) return;
        long seconds = Math.max(1L, Math.round(remainingMillis / 1000.0D));
        var component = miniMessage.deserialize(message.replace("%seconds%", String.valueOf(seconds)));
        if (config.cooldownActionbar()) player.sendActionBar(component);
        else player.sendMessage(component);
    }

    /** Löscht laufende Cooldowns, z. B. nach einem Reload der Konfiguration. */
    public void clearCooldowns() {
        cooldowns.clear();
        toolBreakConfirmations.clear();
    }
}
