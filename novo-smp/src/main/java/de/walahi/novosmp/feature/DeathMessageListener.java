package de.walahi.novosmp.feature;
import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.SMPCorePlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameRule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Projectile;

import java.util.Locale;
import java.util.UUID;

/**
 * Replaces vanilla death messages with German Adventure components.
 * Player names retain their configured rank color. Weapons are inserted as
 * real item components with their vanilla rarity color and complete hover card.
 */
public final class DeathMessageListener implements Listener {

    private static final Component SKULL = Component.text("☠ ", NamedTextColor.DARK_RED)
            .decoration(TextDecoration.BOLD, false)
            .decoration(TextDecoration.ITALIC, false);
    private static final NamedTextColor MESSAGE_COLOR = NamedTextColor.RED;

    private final SMPCorePlugin plugin;

    public DeathMessageListener(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.configs().messages().getBoolean("death-messages.enabled", true)) {
            return;
        }

        Player victim = event.getEntity();
        if (victim.hasMetadata("novosmp-combat-dummy")
                || victim.hasMetadata("novosmp-finalized-combat-death")) {
            event.deathMessage(null);
            return;
        }
        if (plugin instanceof NovoSMPPlugin smp && smp.isPlayerInDuel(victim.getUniqueId())) {
            event.deathMessage(null);
            return;
        }
        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        Entity directDamager = lastDamage instanceof EntityDamageByEntityEvent byEntity
                ? byEntity.getDamager()
                : null;
        LivingEntity attacker = resolveAttacker(directDamager);

        NovoSMPPlugin smp = plugin instanceof NovoSMPPlugin novo ? novo : null;
        Player killer = attacker instanceof Player player && !player.getUniqueId().equals(victim.getUniqueId())
                ? player
                : null;
        boolean victimInvisible = victim.hasPotionEffect(PotionEffectType.INVISIBILITY);
        boolean killerInvisible = killer != null && killer.hasPotionEffect(PotionEffectType.INVISIBILITY);
        boolean viewerSpecific = smp != null && smp.invisibilityAnonymity() != null
                && (victimInvisible || killerInvisible);

        if (!viewerSpecific) {
            Component sentence = buildSentence(victim, lastDamage, directDamager, attacker, null, false, false);
            event.deathMessage(SKULL.append(sentence));
            return;
        }

        // Paper's death message component is global. Invisibility identity is viewer-specific
        // (friends and water-reveal viewers may know the real name), so suppress the global
        // message and deliver the same death sentence separately to each viewer.
        event.deathMessage(null);
        if (Boolean.FALSE.equals(victim.getWorld().getGameRuleValue(GameRule.SHOW_DEATH_MESSAGES))) return;
        for (Player viewer : org.bukkit.Bukkit.getOnlinePlayers()) {
            Component sentence = buildSentence(victim, lastDamage, directDamager, attacker, viewer,
                    victimInvisible, killerInvisible);
            viewer.sendMessage(SKULL.append(sentence));
        }
    }

    /** Broadcasts the normal Novoria PvP sentence at the authoritative dummy-death moment. */
    public void broadcastCombatDummyDeath(UUID victimId, String victimName, UUID killerId,
                                          String killerName, Player killer, LivingEntity dummy) {
        if (!plugin.configs().messages().getBoolean("death-messages.enabled", true)) return;
        if (dummy == null || Boolean.FALSE.equals(dummy.getWorld().getGameRuleValue(GameRule.SHOW_DEATH_MESSAGES))) {
            return;
        }

        EntityDamageEvent damage = dummy.getLastDamageCause();
        Entity directDamager = damage instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        Component victimComponent = coloredName(victimId, victimName);
        if (killer == null) {
            Component sentence = red().append(victimComponent).append(red(" wurde von "))
                    .append(coloredName(killerId, killerName)).append(red(" getötet."));
            Bukkit.getOnlinePlayers().forEach(viewer -> viewer.sendMessage(SKULL.append(sentence)));
            return;
        }

        boolean killerInvisible = killer.hasPotionEffect(PotionEffectType.INVISIBILITY);
        if (!killerInvisible || !(plugin instanceof NovoSMPPlugin smp)
                || smp.invisibilityAnonymity() == null) {
            Component sentence = playerKill(victimComponent, killer, damage, directDamager,
                    null, false);
            Bukkit.getOnlinePlayers().forEach(viewer -> viewer.sendMessage(SKULL.append(sentence)));
            return;
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Component sentence = playerKill(victimComponent, killer, damage, directDamager,
                    viewer, true);
            viewer.sendMessage(SKULL.append(sentence));
        }
    }

    private Component buildSentence(Player victim, EntityDamageEvent damage, Entity directDamager,
                                    LivingEntity attacker, Player viewer, boolean victimInvisible,
                                    boolean killerInvisible) {
        Component victimName = identityName(viewer, victim, victimInvisible);

        if (attacker instanceof Player killer && !killer.getUniqueId().equals(victim.getUniqueId())) {
            return playerKill(victimName, killer, damage, directDamager, viewer, killerInvisible);
        }

        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            return mobKill(victimName, attacker, damage, directDamager);
        }

        EntityDamageEvent.DamageCause cause = damage == null
                ? EntityDamageEvent.DamageCause.CUSTOM
                : damage.getCause();

        return switch (cause) {
            case FALL -> red().append(victimName).append(red(" ist zu Tode gestürzt."));
            case VOID -> red().append(victimName).append(red(" fiel in die Leere."));
            case LAVA -> red().append(victimName).append(red(" versuchte, in Lava zu schwimmen."));
            case FIRE -> red().append(victimName).append(red(" ging in Flammen auf."));
            case FIRE_TICK -> red().append(victimName).append(red(" verbrannte."));
            case DROWNING -> red().append(victimName).append(red(" ist ertrunken."));
            case SUFFOCATION -> red().append(victimName).append(red(" ist in einer Wand erstickt."));
            case STARVATION -> red().append(victimName).append(red(" ist verhungert."));
            case LIGHTNING -> red().append(victimName).append(red(" wurde vom Blitz getroffen."));
            case CONTACT -> red().append(victimName).append(red(" wurde zu Tode gestochen."));
            case HOT_FLOOR -> red().append(victimName).append(red(" entdeckte, dass der Boden aus Lava besteht."));
            case FREEZE -> red().append(victimName).append(red(" ist erfroren."));
            case POISON -> red().append(victimName).append(red(" wurde vergiftet."));
            case WITHER -> red().append(victimName).append(red(" ist dahingewelkt."));
            case MAGIC -> red().append(victimName).append(red(" wurde von Magie getötet."));
            case DRAGON_BREATH -> red().append(victimName).append(red(" wurde vom Drachenatem verschlungen."));
            case FLY_INTO_WALL -> red().append(victimName).append(red(" prallte mit zu viel Schwung gegen eine Wand."));
            case CRAMMING -> red().append(victimName).append(red(" wurde zerquetscht."));
            case FALLING_BLOCK -> red().append(victimName).append(red(" wurde von einem herabfallenden Block zerquetscht."));
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> red().append(victimName).append(red(" ist explodiert."));
            case SONIC_BOOM -> red().append(victimName).append(red(" wurde von einer Schallwelle zerfetzt."));
            case WORLD_BORDER -> red().append(victimName).append(red(" verließ die sichere Welt."));
            case KILL -> red().append(victimName).append(red(" ist gestorben."));
            default -> red().append(victimName).append(red(" ist gestorben."));
        };
    }

    private Component playerKill(Component victimName, Player killer, EntityDamageEvent damage,
                                 Entity directDamager, Player viewer, boolean killerInvisible) {
        Component killerName = identityName(viewer, killer, killerInvisible);
        Component base;

        if (directDamager instanceof Projectile) {
            base = red().append(victimName).append(red(" wurde von ")).append(killerName)
                    .append(red(" erschossen"));
        } else if (damage != null && (damage.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || damage.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) {
            base = red().append(victimName).append(red(" wurde von ")).append(killerName)
                    .append(red(" in die Luft gesprengt"));
        } else {
            base = red().append(victimName).append(red(" wurde von ")).append(killerName)
                    .append(red(" getötet"));
        }

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        Component weaponComponent = itemComponent(weapon);
        if (weaponComponent != null) {
            base = base.append(red(" mit ")).append(weaponComponent);
        }
        return base.append(red("."));
    }

    private Component mobKill(Component victimName, LivingEntity attacker, EntityDamageEvent damage, Entity directDamager) {
        String attackerPhrase = germanEntityPhrase(attacker);

        if (isArrowLike(directDamager)) {
            return red().append(victimName).append(red(" wurde von ")).append(red(attackerPhrase))
                    .append(red(" erschossen."));
        }
        if (directDamager != null && directDamager.getType().name().equals("TRIDENT")) {
            return red().append(victimName).append(red(" wurde von ")).append(red(attackerPhrase))
                    .append(red(" mit einem Dreizack durchbohrt."));
        }
        if (directDamager instanceof Projectile) {
            return red().append(victimName).append(red(" wurde von ")).append(red(attackerPhrase))
                    .append(red(" getroffen und getötet."));
        }
        if (damage != null && (damage.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || damage.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) {
            return red().append(victimName).append(red(" wurde von ")).append(red(attackerPhrase))
                    .append(red(" in die Luft gesprengt."));
        }
        return red().append(victimName).append(red(" wurde von ")).append(red(attackerPhrase))
                .append(red(" getötet."));
    }

    private boolean isArrowLike(Entity directDamager) {
        if (directDamager == null) return false;
        String type = directDamager.getType().name();
        return type.equals("ARROW") || type.equals("SPECTRAL_ARROW");
    }

    private LivingEntity resolveAttacker(Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private Component itemComponent(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;

        ItemMeta meta = item.getItemMeta();
        Component name;

        // Eigene Itemnamen behalten ihre bewusst gesetzte Farbe und Formatierung.
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            name = meta.displayName();
        } else {
            // Normale Items werden ohne die klobigen Vanilla-Klammern angezeigt.
            // Gewöhnliche Items sind absichtlich grau, damit sie die Todesnachricht
            // nicht überstrahlen. Höherwertige Itemgruppen behalten einen dezenten
            // Seltenheitsakzent.
            name = Component.translatable(item.translationKey())
                    .color(defaultItemColor(item.getType()));
        }

        return name
                .decoration(TextDecoration.ITALIC, false)
                .hoverEvent(item.asHoverEvent());
    }

    private NamedTextColor defaultItemColor(Material material) {
        String name = material.name();

        // Netherite soll wie gewünscht den blauen Seltenheitsakzent behalten.
        if (name.startsWith("NETHERITE_")) {
            return NamedTextColor.AQUA;
        }

        // Seltene Vanilla-Gegenstände dürfen leicht hervorstechen, ohne grell zu wirken.
        return switch (material) {
            case ELYTRA, TRIDENT, HEART_OF_THE_SEA, NAUTILUS_SHELL,
                    NETHER_STAR, TOTEM_OF_UNDYING, ENCHANTED_GOLDEN_APPLE -> NamedTextColor.AQUA;
            case DRAGON_EGG, DRAGON_HEAD, END_CRYSTAL -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.GRAY;
        };
    }


    private Component identityName(Player viewer, Player target, boolean targetInvisible) {
        if (viewer != null && plugin instanceof NovoSMPPlugin smp
                && smp.invisibilityAnonymity() != null
                && smp.invisibilityAnonymity().shouldAnonymize(viewer, target, targetInvisible)) {
            return smp.invisibilityAnonymity().anonymousName();
        }
        return coloredName(target);
    }

    private Component coloredName(Player player) {
        if (plugin.getRankManager() != null) {
            return plugin.getRankManager().coloredName(player);
        }
        return Component.text(player.getName(), NamedTextColor.WHITE);
    }

    private Component coloredName(UUID playerId, String playerName) {
        if (plugin.getRankManager() != null) {
            return plugin.getRankManager().coloredName(playerId, playerName);
        }
        return Component.text(playerName, NamedTextColor.WHITE);
    }

    private Component red() {
        return Component.empty().color(MESSAGE_COLOR)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component red(String text) {
        return Component.text(text, MESSAGE_COLOR)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Returns the complete German dative phrase used after "von".
     * Keeping the article here prevents broken messages such as
     * "von einem Spinne" or English enum fallbacks such as "polar bear".
     */
    private String germanEntityPhrase(LivingEntity entity) {
        if (entity.customName() != null) {
            String customName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(entity.customName());
            if (!customName.isBlank()) {
                return "„" + customName + "“";
            }
        }

        return switch (entity.getType().name()) {
            // Feindliche und neutrale Mobs
            case "BLAZE" -> "einer Lohe";
            case "BOGGED" -> "einem Sumpfskelett";
            case "BREEZE" -> "einer Böe";
            case "CAVE_SPIDER" -> "einer Höhlenspinne";
            case "CREAKING" -> "einem Knarzling";
            case "CREEPER" -> "einem Creeper";
            case "DROWNED" -> "einem Ertrunkenen";
            case "ELDER_GUARDIAN" -> "einem Großen Wächter";
            case "ENDER_DRAGON" -> "dem Enderdrachen";
            case "ENDERMAN" -> "einem Enderman";
            case "ENDERMITE" -> "einer Endermilbe";
            case "EVOKER" -> "einem Magier";
            case "GHAST" -> "einem Ghast";
            case "GIANT" -> "einem Riesen";
            case "GUARDIAN" -> "einem Wächter";
            case "HOGLIN" -> "einem Hoglin";
            case "HUSK" -> "einem Wüstenzombie";
            case "ILLUSIONER" -> "einem Illusionisten";
            case "MAGMA_CUBE" -> "einem Magmaschleim";
            case "PHANTOM" -> "einem Phantom";
            case "PIGLIN" -> "einem Piglin";
            case "PIGLIN_BRUTE" -> "einem Piglin-Barbaren";
            case "PILLAGER" -> "einem Plünderer";
            case "RAVAGER" -> "einem Verwüster";
            case "SHULKER" -> "einem Shulker";
            case "SILVERFISH" -> "einem Silberfischchen";
            case "SKELETON" -> "einem Skelett";
            case "SLIME" -> "einem Schleim";
            case "SPIDER" -> "einer Spinne";
            case "STRAY" -> "einem Eiswanderer";
            case "VEX" -> "einem Plagegeist";
            case "VINDICATOR" -> "einem Diener";
            case "WARDEN" -> "einem Wärter";
            case "WITCH" -> "einer Hexe";
            case "WITHER" -> "dem Wither";
            case "WITHER_SKELETON" -> "einem Witherskelett";
            case "ZOGLIN" -> "einem Zoglin";
            case "ZOMBIE" -> "einem Zombie";
            case "ZOMBIE_VILLAGER" -> "einem Zombiedorfbewohner";
            case "ZOMBIFIED_PIGLIN" -> "einem zombifizierten Piglin";

            // Tiere und friedliche Mobs, die durch Provokation oder Spezialfälle töten können
            case "ALLAY" -> "einem Hilfsgeist";
            case "ARMADILLO" -> "einem Gürteltier";
            case "AXOLOTL" -> "einem Axolotl";
            case "BAT" -> "einer Fledermaus";
            case "BEE" -> "einer Biene";
            case "CAMEL" -> "einem Kamel";
            case "CAT" -> "einer Katze";
            case "CHICKEN" -> "einem Huhn";
            case "COD" -> "einem Kabeljau";
            case "COPPER_GOLEM" -> "einem Kupfergolem";
            case "COW" -> "einer Kuh";
            case "DOLPHIN" -> "einem Delfin";
            case "DONKEY" -> "einem Esel";
            case "FOX" -> "einem Fuchs";
            case "FROG" -> "einem Frosch";
            case "GLOW_SQUID" -> "einem Leuchttintenfisch";
            case "GOAT" -> "einer Ziege";
            case "HAPPY_GHAST" -> "einem Glücklichen Ghast";
            case "HORSE" -> "einem Pferd";
            case "IRON_GOLEM" -> "einem Eisengolem";
            case "LLAMA" -> "einem Lama";
            case "MOOSHROOM" -> "einer Mooshroom";
            case "MULE" -> "einem Maultier";
            case "OCELOT" -> "einem Ozelot";
            case "PANDA" -> "einem Panda";
            case "PARROT" -> "einem Papagei";
            case "PIG" -> "einem Schwein";
            case "POLAR_BEAR" -> "einem Eisbären";
            case "PUFFERFISH" -> "einem Kugelfisch";
            case "RABBIT" -> "einem Kaninchen";
            case "SALMON" -> "einem Lachs";
            case "SHEEP" -> "einem Schaf";
            case "SKELETON_HORSE" -> "einem Skelettpferd";
            case "SNIFFER" -> "einem Schnüffler";
            case "SNOW_GOLEM" -> "einem Schneegolem";
            case "SQUID" -> "einem Tintenfisch";
            case "STRIDER" -> "einem Schreiter";
            case "TADPOLE" -> "einer Kaulquappe";
            case "TRADER_LLAMA" -> "einem Händlerlama";
            case "TROPICAL_FISH" -> "einem Tropenfisch";
            case "TURTLE" -> "einer Schildkröte";
            case "VILLAGER" -> "einem Dorfbewohner";
            case "WANDERING_TRADER" -> "einem Fahrenden Händler";
            case "WOLF" -> "einem Wolf";
            case "ZOMBIE_HORSE" -> "einem Zombiepferd";

            // Sicherer deutscher Fallback für zukünftige/ungewöhnliche Entity-Typen.
            default -> "einer unbekannten Kreatur";
        };
    }

}
