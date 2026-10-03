package de.walahi.novosmp.enderchest;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.SlotLayout;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EnderChestUpgradeMenu {
    private static final String DEFAULT_CHECK_TEXTURE =
            "https://textures.minecraft.net/texture/4312ca4632def5ffaf2eb0d9d7cc7b55a50c4e3920d90372aab140781f5dfbc4";

    private final SMPCorePlugin plugin;
    private final ExpandableEnderChestManager manager;
    private final MiniMessageItems items = new MiniMessageItems();

    public EnderChestUpgradeMenu(SMPCorePlugin plugin, ExpandableEnderChestManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void open(Player player) {
        ConfigurationSection root = plugin.configs().main().getConfigurationSection("enderchest-upgrades");
        int rows = Math.max(3, Math.min(6, root == null ? 3 : root.getInt("rows", 3)));
        int inventorySize = rows * 9;
        Gui gui = new Gui(rows, items.component(value(root, "title",
                "<dark_gray>Enderchest-Erweiterungen</dark_gray>")));
        Material filler = MaterialResolver.resolve(value(root, "filler", null),
                Material.PURPLE_STAINED_GLASS_PANE);
        gui.filler(items.item(filler, value(root, "filler-name", " "), List.of()));

        int current = manager.level(player);
        ConfigurationSection upgrades = root == null ? null : root.getConfigurationSection("upgrades");
        if (upgrades != null) {
            for (String id : upgrades.getKeys(false)) {
                ConfigurationSection upgrade = upgrades.getConfigurationSection(id);
                if (upgrade == null || !upgrade.getBoolean("enabled", true)) continue;
                int level = upgrade.getInt("level", 1);
                int slot = SlotLayout.valid(upgrade.getInt("slot", 13), inventorySize, 13);
                long price = Math.max(0L, upgrade.getLong("price", 50_000L));
                boolean owned = current >= level;
                boolean unlocked = current == level - 1;
                ItemStack icon = upgradeItem(root, upgrade, owned, unlocked, level, price);
                gui.button(slot, GuiButton.of(icon, event -> purchase(player, level, price)));
            }
        }
        gui.open(player);
    }

    private void purchase(Player player, int level, long price) {
        int current = manager.level(player);
        if (current >= level) {
            plugin.messages().sendConfiguredAuto(player, "enderchest-upgrades.messages.already-owned",
                    "<green>Diese Erweiterung besitzt du bereits.</green>");
            return;
        }
        if (current != level - 1) {
            plugin.messages().sendConfiguredAuto(player, "enderchest-upgrades.messages.previous-required",
                    "<red>Du musst zuerst die vorherige Erweiterung kaufen.</red>");
            return;
        }
        EconomyOperationResult result = plugin.services().economy().withdraw(player.getUniqueId(), price,
                "EC-UPGRADE-" + level,
                ActionContext.player(ActionSource.GUI, player.getUniqueId()));
        if (result == EconomyOperationResult.INSUFFICIENT_FUNDS) {
            plugin.messages().sendConfiguredAuto(player, "enderchest-upgrades.messages.not-enough-coins",
                    "<red>Du hast nicht genug Coins.</red>");
            return;
        }
        if (result != EconomyOperationResult.SUCCESS) {
            plugin.messages().sendConfiguredAuto(player, "enderchest-upgrades.messages.failed",
                    "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            return;
        }
        if (!manager.upgrade(player, level)) {
            plugin.services().economy().deposit(player.getUniqueId(), price,
                    "EC-UPGRADE-ROLLBACK-" + level, ActionContext.system(player.getUniqueId()));
            plugin.messages().sendConfiguredAuto(player, "enderchest-upgrades.messages.failed",
                    "<red>Der Kauf konnte nicht abgeschlossen werden.</red>");
            return;
        }
        plugin.messages().sendConfiguredAuto(player, "enderchest-upgrades.messages.success",
                "<green>Enderchest auf <yellow>%rows%</yellow> Reihen erweitert.</green>",
                "%rows%", Integer.toString(3 + level));
        open(player);
    }

    private ItemStack upgradeItem(ConfigurationSection root, ConfigurationSection section,
                                  boolean owned, boolean unlocked, int level, long price) {
        Material fallback = owned ? Material.PLAYER_HEAD : Material.ENDER_CHEST;
        Material type = MaterialResolver.resolve(
                section.getString(owned ? "owned-material" : "material"), fallback);
        String fallbackName = owned
                ? "<green>Bereits freigeschaltet</green>"
                : "<light_purple>Erweiterung " + level + "</light_purple>";
        Map<String, String> placeholders = Map.of(
                "%price%", MenuFormat.integer(price),
                "%level%", Integer.toString(level),
                "%rows%", Integer.toString(3 + level),
                "%previous_level%", Integer.toString(Math.max(0, level - 1))
        );

        List<String> configuredLore = section.getStringList(owned ? "owned-lore" : "lore");
        if (!owned && !unlocked) {
            configuredLore = root == null ? List.of() : root.getStringList("locked-lore");
            if (configuredLore.isEmpty()) {
                configuredLore = List.of(
                        "<gray>Benötigt zum Kauf:</gray>",
                        "<red>Erweiterung %previous_level%</red>"
                );
            }
        }

        ItemBuilder builder = items.builder(type, 1,
                section.getString(owned ? "owned-name" : "name", fallbackName),
                configuredLore, placeholders);
        if (owned && type == Material.PLAYER_HEAD) {
            String textureUrl = value(root, "owned-texture-url", DEFAULT_CHECK_TEXTURE);
            String profileName = value(root, "owned-profile-name", "ECGreenCheck");
            builder.editMeta(SkullMeta.class, meta -> applyOwnedHead(meta, textureUrl, profileName));
        }
        return builder.build();
    }

    private void applyOwnedHead(SkullMeta meta, String textureUrl, String profileName) {
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), profileName);
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(URI.create(textureUrl).toURL());
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
        } catch (Exception exception) {
            plugin.getLogger().warning("Der EC-Haken-Kopf konnte nicht geladen werden: " + exception.getMessage());
        }
    }

    private String value(ConfigurationSection section, String path, String fallback) {
        return section == null ? fallback : section.getString(path, fallback);
    }
}
