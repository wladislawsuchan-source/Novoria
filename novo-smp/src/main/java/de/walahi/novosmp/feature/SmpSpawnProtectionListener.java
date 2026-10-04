package de.walahi.novosmp.feature;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.novosmp.sit.SitManager;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Eigener, standardmäßig vollständig lautloser Weltschutz für Hub und SMP-Spawn.
 *
 * Die Listener laufen bewusst auf LOWEST. Dadurch werden verbotene Aktionen bereits
 * vor WorldGuard abgebrochen. WorldGuard bekommt ein gecanceltes Event und sendet
 * für diese von SMPCore geschützten Welten normalerweise keine Standard-Deny-Nachricht mehr.
 */
public final class SmpSpawnProtectionListener implements Listener {

    private final SMPCorePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Long> feedbackCooldown = new HashMap<>();

    public SmpSpawnProtectionListener(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (bypass(event.getPlayer())) return;
        if (deny(event.getBlock().getWorld(), "block-break")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (bypass(event.getPlayer())) return;
        if (deny(event.getBlock().getWorld(), "block-place")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (bypass(event.getPlayer())) return;
        if (deny(event.getBlock().getWorld(), "block-place")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (bypass(event.getPlayer())) return;
        if (deny(event.getBlock().getWorld(), "block-break")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolvePlayer(event.getDamager());
        if (attacker == null || bypass(attacker)) return;

        // Spieler-Windladungen dürfen im SMP-Spawn ihren Rückstoß auslösen,
        // verursachen dort aber keinen Schaden. Das Event darf dafür nicht
        // gecancelt werden, da Minecraft sonst auch den Windstoß entfernt.
        if (isWindCharge(event.getDamager()) && protectionProfile(event.getEntity().getWorld()) != null) {
            event.setDamage(0.0D);
            return;
        }

        if (event.getEntity() instanceof Player && deny(event.getEntity().getWorld(), "pvp")) {
            event.setCancelled(true);
            feedback(attacker);
            return;
        }

        if (event.getEntity() instanceof Animals && deny(event.getEntity().getWorld(), "damage-animals")) {
            event.setCancelled(true);
            feedback(attacker);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (bypass(event.getPlayer())) return;

        Block block = event.getClickedBlock();
        World world = block != null ? block.getWorld() : event.getPlayer().getWorld();

        // Physische Crates müssen auch im vollständig geschützten SMP-Spawn
        // anklickbar bleiben. Der CrateListener übernimmt die Aktion anschließend.
        if (block != null && plugin.crates() != null
                && plugin.crates().crateAt(block.getLocation()).isPresent()) return;

        // Endertruhen bleiben im geschützten Spawn benutzbar.
        if (block != null && block.getType() == Material.ENDER_CHEST) return;

        boolean windCharge = event.getItem() != null
                && event.getItem().getType().name().equals("WIND_CHARGE");

        // Rechtsklick in die Luft darf die Windladung weiterhin normal starten.
        // Bei einem angeklickten Block wird weiter unten nur die Blocknutzung gesperrt.
        if (windCharge && block == null) return;

        if (block != null && block.getType() == Material.RESPAWN_ANCHOR && deny(world, "respawn-anchors")) {
            denyInteraction(event, windCharge);
            feedback(event.getPlayer());
            return;
        }

        if (deny(world, "interact")) {
            denyInteraction(event, windCharge);
            feedback(event.getPlayer());
        }
    }

    /** Sperrt die Blockaktion, ohne die davon getrennte Windladungs-Nutzung zu verlieren. */
    private void denyInteraction(PlayerInteractEvent event, boolean windCharge) {
        if (windCharge && event.getClickedBlock() != null) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.ALLOW);
            return;
        }
        event.setCancelled(true);
    }


    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (bypass(event.getPlayer()) || isCitizensNpc(event.getRightClicked())) return;
        if (deny(event.getRightClicked().getWorld(), "interact")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (bypass(event.getPlayer()) || isCitizensNpc(event.getRightClicked())) return;
        if (deny(event.getRightClicked().getWorld(), "interact")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (bypass(player)) return;

        // Persönliche Endertruhe darf auch am SMP-Spawn geöffnet werden.
        if (event.getInventory().getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) return;
        if (!(event.getInventory().getHolder() instanceof BlockInventoryHolder)) return;

        if (deny(player.getWorld(), "chest-access")) {
            event.setCancelled(true);
            feedback(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (bypass(event.getPlayer())) return;
        if (deny(event.getBed().getWorld(), "sleep")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (bypass(event.getPlayer())) return;
        if (deny(event.getRightClicked().getWorld(), "interact")) {
            event.setCancelled(true);
            feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        // Im Baumodus werden Entity-Platzierungen (insbesondere Armor Stands)
        // nicht durch den Spawn-Schutz blockiert.
        if (player != null && bypass(player)) return;
        if (deny(event.getEntity().getWorld(), "block-place")) {
            event.setCancelled(true);
            if (player != null) feedback(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && bypass(player)) return;
        if (deny(event.getEntity().getWorld(), "block-place")) {
            event.setCancelled(true);
            if (player != null) feedback(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        Player player = resolvePlayer(event.getRemover());
        if (player != null && bypass(player)) return;
        if (deny(event.getEntity().getWorld(), "block-break")) {
            event.setCancelled(true);
            if (player != null) feedback(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        // Windladungen behalten ihren Stoß-/Partikeleffekt, dürfen am Spawn
        // aber niemals Blöcke beschädigen.
        if (isWindCharge(event.getEntity()) && protectionProfile(event.getLocation().getWorld()) != null) {
            event.blockList().clear();
            event.setYield(0.0F);
            return;
        }

        String setting = event.getEntity() instanceof TNTPrimed ? "tnt" : "other-explosions";
        if (deny(event.getLocation().getWorld(), setting)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null && bypass(event.getPlayer())) return;
        if (deny(event.getBlock().getWorld(), "fire-spread")) {
            event.setCancelled(true);
            if (event.getPlayer() != null) feedback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (deny(event.getBlock().getWorld(), "fire-spread")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() == Material.FIRE && deny(event.getBlock().getWorld(), "fire-spread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (deny(event.getBlock().getWorld(), "entity-block-change")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // /sit creates only its own marked, non-persistent ArmorStand. Do not relax
        // spawning for any other entity in the protected spawn world.
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                && SitManager.isTechnicalSeat(plugin, event.getEntity())) return;
        if (deny(event.getLocation().getWorld(), "mob-spawning")) event.setCancelled(true);
    }

    /** Citizens setzt auf gespawnte NPC-Entities die Metadata "NPC".
     * Diese Klicks müssen auch im geschützten SMP-Spawn bis Citizens durchgelassen werden. */
    private boolean isCitizensNpc(Entity entity) {
        return entity != null && entity.hasMetadata("NPC");
    }

    private boolean isWindCharge(Entity entity) {
        if (entity == null) return false;
        String type = entity.getType().name();
        return type.equals("WIND_CHARGE") || type.equals("BREEZE_WIND_CHARGE");
    }

    private boolean bypass(Player player) {
        return plugin.getBuildModeManager() != null && plugin.getBuildModeManager().isActive(player);
    }

    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private boolean deny(World world, String setting) {
        String profile = protectionProfile(world);
        if (profile == null) return false;

        String base = "world-protection." + profile;
        if (!plugin.configs().main().getBoolean(base + ".enabled", true)) return false;
        return !plugin.configs().main().getBoolean(base + "." + setting, false);
    }

    private String protectionProfile(World world) {
        if (world == null) return null;
        String configuredWorld = plugin.configs().server().getString("spawn-world", "smp_spawn");
        return world.getName().equalsIgnoreCase(configuredWorld) ? "smp-spawn" : null;
    }

    private void feedback(Player player) {
        String mode = plugin.configs().main().getString("world-protection.feedback.mode", "SILENT");
        if (mode == null || mode.equalsIgnoreCase("SILENT")) return;

        long cooldownMs = Math.max(0L, plugin.configs().main().getLong("world-protection.feedback.cooldown-milliseconds", 1000L));
        long now = System.currentTimeMillis();
        long last = feedbackCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMs) return;
        feedbackCooldown.put(player.getUniqueId(), now);

        String message = plugin.configs().main().getString("world-protection.feedback.message", "<gray>Das kannst du hier nicht machen.</gray>");
        if (message != null && !message.isBlank()) {
            if (mode.equalsIgnoreCase("ACTIONBAR")) player.sendActionBar(miniMessage.deserialize(message));
            else if (mode.equalsIgnoreCase("CHAT")) player.sendMessage(miniMessage.deserialize(message));
        }

        if (plugin.configs().main().getBoolean("world-protection.feedback.sound.enabled", false)) {
            try {
                Sound sound = Sound.valueOf(plugin.configs().main().getString("world-protection.feedback.sound.name", "BLOCK_NOTE_BLOCK_BASS").toUpperCase(Locale.ROOT));
                float volume = (float) plugin.configs().main().getDouble("world-protection.feedback.sound.volume", 0.5D);
                float pitch = (float) plugin.configs().main().getDouble("world-protection.feedback.sound.pitch", 1.0D);
                player.playSound(player.getLocation(), sound, volume, pitch);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ungültiger Sound unter world-protection.feedback.sound.name");
            }
        }
    }
}
