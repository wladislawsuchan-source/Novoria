package de.walahi.novosmp.duel;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class DuelPlayerSnapshot {
    private final DuelLoadout inventory;
    private final int level;
    private final float exp;
    private final int totalExperience;
    private final GameMode gameMode;
    private final boolean allowFlight;
    private final boolean flying;
    private final List<PotionEffect> effects;
    private final int heldItemSlot;

    private DuelPlayerSnapshot(Player player) {
        this.inventory = DuelLoadout.capture(player);
        this.level = player.getLevel();
        this.exp = player.getExp();
        this.totalExperience = player.getTotalExperience();
        this.gameMode = player.getGameMode();
        this.allowFlight = player.getAllowFlight();
        this.flying = player.isFlying();
        this.effects = new ArrayList<>(player.getActivePotionEffects());
        this.heldItemSlot = player.getInventory().getHeldItemSlot();
    }

    public static DuelPlayerSnapshot capture(Player player) { return new DuelPlayerSnapshot(player); }

    public DuelLoadout inventory() { return inventory.copy(); }

    public void restoreKitMatch(Player player) {
        inventory.apply(player);
        restoreCommon(player);
    }

    public void restoreCommon(Player player) {
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, heldItemSlot)));
        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.setLevel(level);
        player.setExp(exp);
        player.setTotalExperience(totalExperience);
        clearEffects(player);
        for (PotionEffect effect : effects) player.addPotionEffect(effect);
        player.updateInventory();
    }

    public static void prepareForDuel(Player player) {
        clearEffects(player);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0F);
        player.setAbsorptionAmount(0D);
        double max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? 20D : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.max(1D, max));
        player.setFoodLevel(20);
        player.setSaturation(20F);
        player.setExhaustion(0F);
        player.setRemainingAir(player.getMaximumAir());
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        clearCurrentItemCooldowns(player);
    }

    public static void prepareAfterDuel(Player player) {
        clearEffects(player);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0F);
        player.setAbsorptionAmount(0D);
        double max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? 20D : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        player.setHealth(Math.max(1D, max));
        player.setFoodLevel(20);
        player.setSaturation(20F);
        player.setExhaustion(0F);
        clearCurrentItemCooldowns(player);
    }

    private static void clearCurrentItemCooldowns(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) continue;
            player.setCooldown(item.getType(), 0);
        }
    }

    private static void clearEffects(Player player) {
        Collection<PotionEffect> current = new ArrayList<>(player.getActivePotionEffects());
        for (PotionEffect effect : current) player.removePotionEffect(effect.getType());
    }
}
