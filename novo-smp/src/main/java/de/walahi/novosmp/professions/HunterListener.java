package de.walahi.novosmp.professions;

import de.walahi.novosmp.items.CustomItemManager;
import de.walahi.smpcore.SMPCorePlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Player-caused kill tracking, Hunter XP and the Hetzjägerspeer streak effect. */
public final class HunterListener implements Listener {
    private static final String HUNTER_SPEAR_ID = "jaeger_hetzjaegerspeer";

    private final SMPCorePlugin plugin;
    private final ProfessionManager manager;
    private final ProfessionConfig config;
    private final CustomItemManager customItems;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, StreakState> streaks = new HashMap<>();
    private final Set<Material> rareDropExclusions;

    public HunterListener(SMPCorePlugin plugin, ProfessionManager manager,
                          ProfessionConfig config, CustomItemManager customItems) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
        this.customItems = customItems;
        this.rareDropExclusions = loadRareDropExclusions();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        streaks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.hasMetadata("novosmp-combat-dummy")
                || victim.hasMetadata("novosmp-finalized-combat-death")) return;
        Player killer = victim.getKiller();
        if (killer == null || killer.getGameMode() != GameMode.SURVIVAL) return;

        boolean baby = isBaby(victim);
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        boolean hunterSpear = isActiveHunterSpear(weapon);
        StreakBonus streakBonus = hunterSpear ? advanceStreak(killer) : resetStreak(killer);

        if (hunterSpear && streakBonus.lootBonus() > 0D && !(victim instanceof Player)) {
            addNormalLootBonus(event, streakBonus.lootBonus());
        }

        if (baby) return; // Babys dürfen Köpfe haben, aber nie Jäger-XP/Jagdauftrag zählen.

        int vanillaXp = Math.max(0, event.getDroppedExp());
        String entityType = victim.getType().name();
        double baseXp;
        if (victim instanceof Player) {
            baseXp = config.plugin().configs().professions().getDouble("professions.jaeger.xp-mobs.PLAYER", 25D);
        } else if (vanillaXp > 0) {
            baseXp = config.hunterXp(entityType, vanillaXp);
        } else {
            baseXp = 0D;
        }

        if (hunterSpear && streakBonus.xpBonus() > 0D) {
            baseXp *= 1D + streakBonus.xpBonus();
        }
        manager.recordHunterKill(killer, entityType, baseXp);
    }

    /** Reduziert die von Lunge erzeugte Erschöpfung direkt vor der Anwendung. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpearEnchantmentExhaustion(EntityExhaustionEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getExhaustionReason() != EntityExhaustionEvent.ExhaustionReason.ENCHANTMENT_EFFECT) return;
        if (!isActiveHunterSpear(player.getInventory().getItemInMainHand())) return;

        double reductionPercent = Math.max(0D, Math.min(100D, plugin.configs().items()
                .getDouble("items." + HUNTER_SPEAR_ID + ".lunge-hunger-reduction-percent", 75D)));
        event.setExhaustion(event.getExhaustion() * (float) (1D - reductionPercent / 100D));
    }

    private boolean isActiveHunterSpear(ItemStack item) {
        return HUNTER_SPEAR_ID.equals(customItems.identify(item))
                && manager.tools().validateForAbility(item);
    }

    private StreakBonus advanceStreak(Player player) {
        long now = System.currentTimeMillis();
        long streakWindowMillis = Math.max(1L, config.plugin().configs().professions()
                .getLong("professions.jaeger.spear-streak.window-seconds", 8L)) * 1_000L;
        StreakState previous = streaks.get(player.getUniqueId());
        int count = previous != null && now - previous.lastKillMillis() <= streakWindowMillis
                ? previous.count() + 1 : 1;
        streaks.put(player.getUniqueId(), new StreakState(count, now));
        StreakBonus bonus = bonus(count);
        if (count >= 2) {
            String raw = config.string("messages.hunter-streak",
                    "<red>⚔ Jagdserie x%streak%</red> <dark_gray>•</dark_gray> <yellow>+%xp%% XP</yellow> <dark_gray>•</dark_gray> <gold>+%loot%% Beute</gold>")
                    .replace("%streak%", Integer.toString(count))
                    .replace("%xp%", Integer.toString((int) Math.round(bonus.xpBonus() * 100D)))
                    .replace("%loot%", Integer.toString((int) Math.round(bonus.lootBonus() * 100D)));
            player.sendActionBar(miniMessage.deserialize(raw));
        }
        return bonus;
    }

    private StreakBonus resetStreak(Player player) {
        streaks.remove(player.getUniqueId());
        return StreakBonus.NONE;
    }

    private StreakBonus bonus(int count) {
        if (count >= 5) return new StreakBonus(0.20D, 0.12D);
        if (count == 4) return new StreakBonus(0.15D, 0.09D);
        if (count == 3) return new StreakBonus(0.10D, 0.06D);
        if (count == 2) return new StreakBonus(0.05D, 0.03D);
        return StreakBonus.NONE;
    }

    private void addNormalLootBonus(EntityDeathEvent event, double multiplier) {
        if (multiplier <= 0D || event.getDrops().isEmpty()) return;
        var extras = new java.util.ArrayList<ItemStack>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (ItemStack original : event.getDrops()) {
            if (original == null || original.getType().isAir() || original.getMaxStackSize() <= 1
                    || rareDropExclusions.contains(original.getType()) || isHeadOrSkull(original.getType())) continue;
            double exact = original.getAmount() * multiplier;
            int amount = (int) Math.floor(exact);
            if (random.nextDouble() < exact - amount) amount++;
            while (amount > 0) {
                ItemStack extra = original.clone();
                int chunk = Math.min(amount, extra.getMaxStackSize());
                extra.setAmount(chunk);
                extras.add(extra);
                amount -= chunk;
            }
        }
        event.getDrops().addAll(extras);
    }

    private boolean isHeadOrSkull(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_HEAD") || name.endsWith("_SKULL");
    }

    private Set<Material> loadRareDropExclusions() {
        Set<Material> result = new HashSet<>();
        for (String name : config.stringList("professions.jaeger.spear-streak.rare-drop-exclusions")) {
            Material material = Material.matchMaterial(name);
            if (material != null) result.add(material);
        }
        if (!result.isEmpty()) return Set.copyOf(result);
        for (String name : Set.of(
                "PLAYER_HEAD", "WITHER_SKELETON_SKULL", "TOTEM_OF_UNDYING", "NETHER_STAR",
                "HEAVY_CORE", "TRIDENT", "ELYTRA", "DRAGON_EGG", "OMINOUS_TRIAL_KEY", "OMINOUS_BOTTLE")) {
            Material material = Material.matchMaterial(name);
            if (material != null) result.add(material);
        }
        return Set.copyOf(result);
    }

    /** Reflection also covers newer baby-capable mobs without hard-linking every API interface. */
    public static boolean isBaby(LivingEntity entity) {
        if (entity == null) return false;
        try {
            Method method = entity.getClass().getMethod("isBaby");
            Object value = method.invoke(entity);
            if (value instanceof Boolean bool) return bool;
        } catch (ReflectiveOperationException ignored) { }
        try {
            Method method = entity.getClass().getMethod("isAdult");
            Object value = method.invoke(entity);
            if (value instanceof Boolean bool) return !bool;
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }

    private record StreakState(int count, long lastKillMillis) { }
    private record StreakBonus(double xpBonus, double lootBonus) {
        private static final StreakBonus NONE = new StreakBonus(0D, 0D);
    }
}
