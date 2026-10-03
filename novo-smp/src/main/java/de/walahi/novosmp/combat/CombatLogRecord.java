package de.walahi.novosmp.combat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Authoritative item and life snapshot while an offline player's dummy exists. */
final class CombatLogRecord {
    enum Phase { ACTIVE, SURVIVED, KILLED, FINALIZED }

    record EffectState(String type, int amplifier, long expiresAt, boolean ambient,
                       boolean particles, boolean icon) {
        static EffectState capture(PotionEffect effect, long now) {
            long expires = effect.isInfinite() ? -1L : now + Math.max(0, effect.getDuration()) * 50L;
            return new EffectState(effect.getType().getKey().toString(), effect.getAmplifier(), expires,
                    effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
        }

        PotionEffect toEffect(long now) {
            PotionEffectType effectType = PotionEffectType.getByKey(NamespacedKey.fromString(type));
            if (effectType == null) return null;
            int duration = expiresAt < 0L ? PotionEffect.INFINITE_DURATION
                    : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (expiresAt - now + 49L) / 50L));
            return duration == 0 ? null : new PotionEffect(
                    effectType, duration, amplifier, ambient, particles, icon);
        }
    }

    final UUID playerId;
    final String playerName;
    final long createdAt;
    long expiresAt;
    Phase phase;
    String worldName;
    double x;
    double y;
    double z;
    float yaw;
    float pitch;
    double health;
    double maxHealth;
    double absorption;
    int food;
    float saturation;
    float exhaustion;
    int fireTicks;
    int level;
    float exp;
    int totalExperience;
    int heldSlot;
    ItemStack[] storage;
    ItemStack[] armor;
    ItemStack[] extra;
    ItemStack cursor;
    List<EffectState> effects;

    CombatLogRecord(UUID playerId, String playerName, long createdAt, long expiresAt, Phase phase,
                    String worldName, double x, double y, double z, float yaw, float pitch,
                    double health, double maxHealth, double absorption, int food, float saturation,
                    float exhaustion, int fireTicks, int level, float exp, int totalExperience, int heldSlot,
                    ItemStack[] storage, ItemStack[] armor, ItemStack[] extra, ItemStack cursor,
                    List<EffectState> effects) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.phase = phase;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.health = health;
        this.maxHealth = maxHealth;
        this.absorption = absorption;
        this.food = food;
        this.saturation = saturation;
        this.exhaustion = exhaustion;
        this.fireTicks = fireTicks;
        this.level = level;
        this.exp = exp;
        this.totalExperience = totalExperience;
        this.heldSlot = heldSlot;
        this.storage = cloneItems(storage);
        this.armor = cloneItems(armor);
        this.extra = cloneItems(extra);
        this.cursor = cloneItem(cursor);
        this.effects = List.copyOf(effects == null ? List.of() : effects);
    }

    static CombatLogRecord capture(Player player, long now, long expiresAt) {
        Location location = player.getLocation();
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20D : maxHealthAttribute.getValue();
        List<EffectState> effects = player.getActivePotionEffects().stream()
                .map(effect -> EffectState.capture(effect, now)).toList();
        return new CombatLogRecord(player.getUniqueId(), player.getName(), now, expiresAt, Phase.ACTIVE,
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch(), player.getHealth(), maxHealth,
                player.getAbsorptionAmount(), player.getFoodLevel(), player.getSaturation(),
                player.getExhaustion(), player.getFireTicks(), player.getLevel(), player.getExp(),
                player.getTotalExperience(), player.getInventory().getHeldItemSlot(), player.getInventory().getStorageContents(),
                player.getInventory().getArmorContents(), player.getInventory().getExtraContents(),
                player.getItemOnCursor(), effects);
    }

    Location location() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    void refreshFromDummy(LivingEntity dummy) {
        refreshFromDummy(dummy, true);
    }

    void refreshFromDummy(LivingEntity dummy, boolean captureEquipment) {
        if (dummy == null) return;
        Location current = dummy.getLocation();
        if (current.getWorld() != null) {
            worldName = current.getWorld().getName();
            x = current.getX(); y = current.getY(); z = current.getZ();
            yaw = current.getYaw(); pitch = current.getPitch();
        }
        if (!dummy.isDead()) health = Math.max(0D, dummy.getHealth());
        absorption = Math.max(0D, dummy.getAbsorptionAmount());
        long now = System.currentTimeMillis();
        effects = dummy.getActivePotionEffects().stream()
                .map(effect -> EffectState.capture(effect, now)).toList();
        EntityEquipment equipment = captureEquipment ? dummy.getEquipment() : null;
        if (equipment != null) {
            armor = cloneItems(equipment.getArmorContents());
            if (extra.length == 0) extra = new ItemStack[1];
            extra[0] = cloneItem(equipment.getItemInOffHand());
            // Main hand is storage slot selected at logout. Reflect durability/consumption changes.
            ItemStack mainHand = equipment.getItemInMainHand();
            if (heldSlot >= 0 && heldSlot < storage.length) storage[heldSlot] = cloneItem(mainHand);
        }
    }

    void applyToDummy(LivingEntity dummy) {
        AttributeInstance maxHealthAttribute = dummy.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttribute != null) maxHealthAttribute.setBaseValue(Math.max(1D, maxHealth));
        dummy.setHealth(Math.max(0.5D, Math.min(health, maxHealthAttribute == null ? 20D : maxHealthAttribute.getValue())));
        dummy.setAbsorptionAmount(Math.max(0D, absorption));
        dummy.setFireTicks(Math.max(0, fireTicks));
        dummy.setCanPickupItems(false);
        EntityEquipment equipment = dummy.getEquipment();
        if (equipment != null) {
            equipment.setArmorContents(cloneItems(armor));
            equipment.setItemInMainHand(selectedMainHand());
            equipment.setItemInOffHand(extra.length == 0 ? null : cloneItem(extra[0]));
        }
        long now = System.currentTimeMillis();
        for (EffectState effect : effects) {
            PotionEffect active = effect.toEffect(now);
            if (active != null) dummy.addPotionEffect(active);
        }
    }

    void restore(Player player) {
        clearOwnedState(player);
        Location target = location();
        if (target != null) player.teleport(target);
        player.getInventory().setStorageContents(fit(storage, player.getInventory().getStorageContents().length));
        player.getInventory().setArmorContents(fit(armor, player.getInventory().getArmorContents().length));
        player.getInventory().setExtraContents(fit(extra, player.getInventory().getExtraContents().length));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, heldSlot)));
        player.setItemOnCursor(cloneItem(cursor));
        player.setFoodLevel(Math.max(0, Math.min(20, food)));
        player.setSaturation(Math.max(0F, saturation));
        player.setExhaustion(Math.max(0F, exhaustion));
        player.setFireTicks(Math.max(0, fireTicks));
        player.setTotalExperience(Math.max(0, totalExperience));
        player.setLevel(Math.max(0, level));
        player.setExp(Math.max(0F, Math.min(0.999999F, exp)));
        long now = System.currentTimeMillis();
        for (EffectState effect : effects) {
            PotionEffect active = effect.toEffect(now);
            if (active != null) player.addPotionEffect(active);
        }
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double currentMax = maxHealthAttribute == null ? 20D : maxHealthAttribute.getValue();
        player.setHealth(Math.max(0.5D, Math.min(health, currentMax)));
        player.setAbsorptionAmount(Math.max(0D, absorption));
        player.updateInventory();
    }

    void prepareKilledPlayer(Player player) {
        clearOwnedState(player);
        Location target = location();
        if (target != null) player.teleport(target);
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(Math.max(1D, max == null ? 20D : max.getValue()));
        player.setInvulnerable(true);
    }

    void dropItems(Location at) {
        if (at == null || at.getWorld() == null) return;
        List<ItemStack> drops = new ArrayList<>();
        addItems(drops, storage);
        addItems(drops, armor);
        addItems(drops, extra);
        if (cursor != null && !cursor.getType().isAir()) drops.add(cursor.clone());
        for (ItemStack item : drops) at.getWorld().dropItemNaturally(at, item);
    }

    int droppedExperience() {
        return Math.max(0, Math.min(100, level * 7));
    }

    static void clearOwnedState(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[player.getInventory().getArmorContents().length]);
        player.getInventory().setExtraContents(new ItemStack[player.getInventory().getExtraContents().length]);
        player.setItemOnCursor(null);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setFireTicks(0);
        player.setAbsorptionAmount(0D);
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0F);
        player.updateInventory();
    }

    private ItemStack selectedMainHand() {
        return heldSlot >= 0 && heldSlot < storage.length ? cloneItem(storage[heldSlot]) : null;
    }

    private static void addItems(Collection<ItemStack> target, ItemStack[] source) {
        if (source == null) return;
        for (ItemStack item : source) {
            if (item != null && !item.getType().isAir()) target.add(item.clone());
        }
    }

    static ItemStack[] cloneItems(ItemStack[] source) {
        if (source == null) return new ItemStack[0];
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = cloneItem(source[i]);
        return result;
    }

    private static ItemStack[] fit(ItemStack[] source, int size) {
        ItemStack[] result = new ItemStack[size];
        if (source == null) return result;
        for (int i = 0; i < Math.min(size, source.length); i++) result[i] = cloneItem(source[i]);
        return result;
    }

    static ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }
}
