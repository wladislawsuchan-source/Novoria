package de.walahi.novosmp.daily;

import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import de.walahi.novosmp.lumi.LumiRepository;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.SlotLayout;
import de.walahi.smpcore.services.EconomyService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DailyMenu {
    private static final List<Integer> DEFAULT_DAY_SLOTS = SlotLayout.of(10, 11, 12, 13, 14, 15, 16);
    private static final List<Integer> DEFAULT_PREMIUM_SLOTS = SlotLayout.of(1, 2, 3, 4, 5, 6, 7);
    private static final List<Integer> DEFAULT_PREMIUM_PLUS_SLOTS = SlotLayout.of(19, 20, 21, 22, 23, 24, 25);

    private final SMPCorePlugin plugin;
    private final DailyManager manager;
    private final EconomyService economy;
    private final LumiRepository lumis;
    private final CrateManager crates;
    private final MiniMessageItems items = new MiniMessageItems();
    private final Set<UUID> activeClaims = ConcurrentHashMap.newKeySet();

    public DailyMenu(SMPCorePlugin plugin, DailyManager manager, EconomyService economy,
                     LumiRepository lumis, CrateManager crates) {
        this.plugin = plugin;
        this.manager = manager;
        this.economy = economy;
        this.lumis = lumis;
        this.crates = crates;
    }

    public void open(Player player) {
        DailyProgress progress;
        try {
            progress = manager.progress(player.getUniqueId());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Daily-Fortschritt für " + player.getName()
                    + " konnte nicht geladen werden: " + exception.getMessage());
            configuredMessage(player, "daily.messages.storage-error",
                    "<red>Deine Daily-Daten konnten nicht geladen werden.</red>");
            return;
        }

        var config = plugin.configs().daily();
        int rows = Math.max(3, Math.min(6, config.getInt("daily.menu.rows", 3)));
        int inventorySize = rows * 9;
        Map<DailyRewardType, List<Integer>> tierSlots = configuredTierSlots(inventorySize);

        Gui gui = new Gui(rows, items.component(config.getString(
                "daily.menu.title", "<gold>Tägliche Belohnungen</gold>")));
        Material filler = MaterialResolver.resolve(config.getString(
                "daily.menu.filler.material"), Material.BLACK_STAINED_GLASS_PANE);
        gui.filler(items.item(filler,
                config.getString("daily.menu.filler.name", " "),
                config.getStringList("daily.menu.filler.lore")));

        int currentDay = progress.currentDay();
        for (DailyRewardType type : List.of(
                DailyRewardType.PREMIUM, DailyRewardType.STANDARD, DailyRewardType.PREMIUM_PLUS)) {
            boolean eligible = eligible(player, type);
            List<Integer> slots = tierSlots.get(type);
            for (int day = 1; day <= 7; day++) {
                DailyReward reward = manager.reward(day);
                DayState state = state(day, currentDay, progress, type, eligible);
                int slot = slots.get(day - 1);
                if (state == DayState.AVAILABLE) {
                    gui.button(slot, GuiButton.of(icon(reward, type, state), event -> claim(player, type)));
                } else {
                    gui.item(slot, icon(reward, type, state));
                }
            }
        }
        gui.open(player);
    }

    private Map<DailyRewardType, List<Integer>> configuredTierSlots(int inventorySize) {
        var config = plugin.configs().daily();
        List<Integer> standardFallbacks = SlotLayout.configured(
                config, "daily.menu.day-slots", inventorySize, DEFAULT_DAY_SLOTS);
        Map<DailyRewardType, List<Integer>> result = new LinkedHashMap<>();
        Set<Integer> occupied = new HashSet<>();
        for (DailyRewardType type : List.of(
                DailyRewardType.PREMIUM, DailyRewardType.STANDARD, DailyRewardType.PREMIUM_PLUS)) {
            List<Integer> slots = new ArrayList<>(7);
            List<Integer> defaults = switch (type) {
                case PREMIUM -> DEFAULT_PREMIUM_SLOTS;
                case STANDARD -> standardFallbacks;
                case PREMIUM_PLUS -> DEFAULT_PREMIUM_PLUS_SLOTS;
            };
            for (int day = 1; day <= 7; day++) {
                int fallback = defaults.get(day - 1);
                String path = "days." + day + (type == DailyRewardType.STANDARD
                        ? ".slot" : "." + type.configKey() + ".slot");
                int requested = config.getInt(path, fallback);
                int slot = requested >= 0 && requested < inventorySize && !occupied.contains(requested)
                        ? requested : fallback;
                if (slot < 0 || slot >= inventorySize || occupied.contains(slot)) {
                    slot = firstFreeSlot(inventorySize, occupied);
                }
                occupied.add(slot);
                slots.add(slot);
            }
            result.put(type, slots);
        }
        return result;
    }

    private int firstFreeSlot(int inventorySize, Set<Integer> occupied) {
        for (int slot = 0; slot < inventorySize; slot++) {
            if (!occupied.contains(slot)) return slot;
        }
        return 0;
    }

    private DayState state(int day, int currentDay, DailyProgress progress,
                           DailyRewardType type, boolean eligible) {
        if (progress.claimedOn(manager.today())) {
            if (day == previousDay(currentDay)) {
                if (progress.tierClaimedOn(manager.today(), type)) return DayState.CLAIMED;
                return eligible ? DayState.LOCKED : DayState.RANK_LOCKED;
            }
            return DayState.LOCKED;
        }
        if (day != currentDay) return DayState.LOCKED;
        if (progress.tierClaimedOn(manager.today(), type)) return DayState.CLAIMED;
        return eligible ? DayState.AVAILABLE : DayState.RANK_LOCKED;
    }

    private int previousDay(int currentDay) {
        return currentDay == 1 ? 7 : currentDay - 1;
    }

    private ItemStack icon(DailyReward reward, DailyRewardType type, DayState state) {
        var config = plugin.configs().daily();
        String base = "daily.menu.states." + state.name().toLowerCase(java.util.Locale.ROOT);
        Material fallback = switch (state) {
            case CLAIMED -> Material.LIME_CONCRETE;
            case LOCKED -> Material.RED_STAINED_GLASS_PANE;
            case RANK_LOCKED -> Material.GRAY_STAINED_GLASS_PANE;
            case AVAILABLE -> tierMaterial(reward, type);
        };
        Material material = MaterialResolver.resolve(config.getString(base + ".material"), fallback);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%day%", Integer.toString(reward.day()));
        placeholders.put("%coins%", MenuFormat.integer(reward.base().coins()));
        placeholders.put("%lumis%", MenuFormat.integer(reward.base().lumis()));
        placeholders.put("%premium-coins%", MenuFormat.integer(reward.premium().coins()));
        placeholders.put("%premium-lumis%", MenuFormat.integer(reward.premium().lumis()));
        placeholders.put("%premiumplus-coins%", MenuFormat.integer(reward.premiumPlus().coins()));
        placeholders.put("%premiumplus-lumis%", MenuFormat.integer(reward.premiumPlus().lumis()));
        placeholders.put("%tier%", tierDisplay(type));
        placeholders.put("%rank%", requiredRank(type));

        String fallbackName = switch (state) {
            case CLAIMED -> "<green><bold>✔ %tier% · Tag %day%</bold></green>";
            case AVAILABLE -> "<yellow><bold>★ %tier% · Tag %day%</bold></yellow>";
            case LOCKED -> "<red><bold>✖ %tier% · Tag %day%</bold></red>";
            case RANK_LOCKED -> "<gray><bold>🔒 %tier% · Tag %day%</bold></gray>";
        };
        boolean dynamicRewards = config.getBoolean("daily.menu.show-rewards-for-all-days", true);
        List<String> configuredLore = config.getStringList(base + ".lore");
        List<Component> lore = dynamicRewards || configuredLore.isEmpty()
                ? defaultLore(reward, type, state)
                : items.lore(configuredLore, placeholders);

        ItemBuilder builder = ItemBuilder.of(material)
                .name(items.component(config.getString(base + ".name", fallbackName), placeholders))
                .lore(lore);
        if (state == DayState.AVAILABLE) {
            builder.glint(config.getBoolean(base + ".glint", true))
                    .flags(ItemFlag.HIDE_ENCHANTS);
        }
        return builder.build();
    }

    private List<Component> defaultLore(DailyReward reward, DailyRewardType type, DayState state) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        switch (state) {
            case CLAIMED -> lore.add(items.component("<green>Heute bereits abgeholt</green>"));
            case LOCKED -> lore.add(items.component("<gray>Belohnung für diesen Tag:</gray>"));
            case AVAILABLE -> lore.add(items.component("<yellow>Heute verfügbar:</yellow>"));
            case RANK_LOCKED -> lore.add(items.component("<red>Benötigt: " + requiredRank(type) + "</red>"));
        }
        appendTier(lore, "<" + tierColor(type) + ">" + tierDisplay(type) + "</" + tierColor(type) + ">",
                tier(reward, type));
        lore.add(Component.empty());
        if (state == DayState.AVAILABLE) {
            lore.add(items.component("<green><bold>Klicke zum Abholen!</bold></green>"));
        } else if (state == DayState.CLAIMED) {
            lore.add(items.component("<dark_gray>Die nächste Belohnung wartet morgen.</dark_gray>"));
        } else {
            lore.add(items.component("<dark_gray>Noch nicht verfügbar.</dark_gray>"));
        }
        return lore;
    }

    private DailyRewardTier tier(DailyReward reward, DailyRewardType type) {
        return switch (type) {
            case STANDARD -> reward.base();
            case PREMIUM -> reward.premium();
            case PREMIUM_PLUS -> reward.premiumPlus();
        };
    }

    private Material tierMaterial(DailyReward reward, DailyRewardType type) {
        if (type == DailyRewardType.STANDARD) return reward.material();
        String path = "days." + reward.day() + "." + type.configKey() + ".material";
        return MaterialResolver.resolve(plugin.configs().daily().getString(path), reward.material());
    }

    private String tierDisplay(DailyRewardType type) {
        return switch (type) {
            case STANDARD -> "Standard";
            case PREMIUM -> "Premium";
            case PREMIUM_PLUS -> "Premium+";
        };
    }

    private String tierColor(DailyRewardType type) {
        return switch (type) {
            case STANDARD -> "white";
            case PREMIUM -> "green";
            case PREMIUM_PLUS -> "dark_green";
        };
    }

    private String requiredRank(DailyRewardType type) {
        return switch (type) {
            case STANDARD -> "Spieler";
            case PREMIUM -> "Premium oder Premium+";
            case PREMIUM_PLUS -> "Premium+";
        };
    }

    private boolean eligible(Player player, DailyRewardType type) {
        return switch (type) {
            case STANDARD -> true;
            case PREMIUM -> plugin.getRankManager().hasPremium(player)
                    || plugin.getRankManager().hasPremiumPlus(player);
            case PREMIUM_PLUS -> plugin.getRankManager().hasPremiumPlus(player);
        };
    }

    private void appendTier(List<Component> lore, String heading, DailyRewardTier tier) {
        lore.add(items.component(heading + ":"));
        if (tier.empty()) {
            lore.add(items.component("<dark_gray>  • keine Zusatzbelohnung</dark_gray>"));
            return;
        }
        if (tier.coins() > 0) {
            lore.add(items.component("<dark_gray>  • <gold>" + MenuFormat.integer(tier.coins()) + " Coins</gold>"));
        }
        if (tier.lumis() > 0) {
            lore.add(items.component("<dark_gray>  • <aqua>" + MenuFormat.integer(tier.lumis()) + " Lumis</aqua>"));
        }
        tier.keys().forEach((crateId, amount) -> lore.add(items.component(
                "<dark_gray>  • <light_purple>" + amount + "× " + keyDisplayName(crateId) + "</light_purple>")));
        if (!tier.commands().isEmpty()) {
            lore.add(items.component("<dark_gray>  • <gray>weitere konfigurierte Belohnung</gray>"));
        }
    }

    private String keyDisplayName(String crateId) {
        return crates.find(crateId).map(crates.keys()::keyDisplayName).orElse(crateId + "-Key");
    }

    private void claim(Player player, DailyRewardType type) {
        UUID uuid = player.getUniqueId();
        if (!activeClaims.add(uuid)) return;
        try {
            DailyProgress progress;
            try {
                progress = manager.progress(uuid);
            } catch (RuntimeException exception) {
                configuredMessage(player, "daily.messages.storage-error",
                        "<red>Deine Daily-Daten konnten nicht geladen werden.</red>");
                return;
            }
            if (progress.claimedOn(manager.today()) || progress.tierClaimedOn(manager.today(), type)) {
                configuredMessage(player, "daily.messages.already-claimed",
                        "<red>Du hast deine Daily-Belohnung heute bereits abgeholt.</red>");
                open(player);
                return;
            }
            if (!eligible(player, type)) {
                configuredMessage(player, "daily.messages.rank-required",
                        "<red>Für diese Belohnung benötigst du den passenden Rang.</red>");
                open(player);
                return;
            }

            DailyReward reward = manager.reward(progress.currentDay());
            boolean premiumPlus = plugin.getRankManager().hasPremiumPlus(player);
            boolean premium = plugin.getRankManager().hasPremium(player);
            DailyRewardTier payout = tier(reward, type);
            Map<CrateDefinition, Integer> requestedKeys = resolveKeys(player, payout);
            if (requestedKeys == null) return;
            if (!crates.keys().canFit(player, requestedKeys)) {
                configuredMessage(player, "daily.messages.inventory-full",
                        "<red>Dein Inventar ist zu voll für die Daily-Keys. Schaffe zuerst Platz.</red>");
                return;
            }

            int requiredMask = DailyRewardType.STANDARD.bit();
            if (premium || premiumPlus) requiredMask |= DailyRewardType.PREMIUM.bit();
            if (premiumPlus) requiredMask |= DailyRewardType.PREMIUM_PLUS.bit();
            DailyClaimResult result = manager.markClaimed(uuid, type, requiredMask);
            if (result == DailyClaimResult.ALREADY_CLAIMED) {
                configuredMessage(player, "daily.messages.already-claimed",
                        "<red>Du hast deine Daily-Belohnung heute bereits abgeholt.</red>");
                open(player);
                return;
            }
            if (result != DailyClaimResult.SUCCESS) {
                configuredMessage(player, "daily.messages.storage-error",
                        "<red>Die Daily-Belohnung konnte nicht gespeichert werden.</red>");
                return;
            }

            boolean coinsPaid = false;
            boolean lumisPaid = false;
            boolean keysPaid = false;
            try {
                if (payout.coins() > 0) {
                    EconomyOperationResult economyResult = economy.deposit(uuid, payout.coins(),
                            "DAILY-DAY-" + reward.day(), ActionContext.system(uuid));
                    if (economyResult != EconomyOperationResult.SUCCESS) {
                        throw new IllegalStateException("Coins konnten nicht ausgezahlt werden: " + economyResult);
                    }
                    coinsPaid = true;
                }
                if (payout.lumis() > 0) {
                    if (!lumis.add(uuid, payout.lumis())) {
                        throw new IllegalStateException("Lumis konnten nicht ausgezahlt werden");
                    }
                    lumisPaid = true;
                }
                if (!crates.keys().addWithoutDrop(player, requestedKeys)) {
                    throw new IllegalStateException("Key-Inventarprüfung ist während der Auszahlung fehlgeschlagen");
                }
                keysPaid = true;
            } catch (RuntimeException payoutFailure) {
                rollbackPayout(player, progress, reward.day(), payout, coinsPaid, lumisPaid, keysPaid, payoutFailure);
                return;
            }

            // Arbitrary legacy commands run last. A failure never restores the claim after keys
            // were issued, which prevents a command error from becoming a repeatable key payout.
            for (String command : payout.commands()) {
                String parsed = command.replace("%player%", player.getName()).trim();
                if (parsed.startsWith("/")) parsed = parsed.substring(1);
                boolean dispatched;
                try {
                    dispatched = !parsed.isBlank()
                            && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                } catch (RuntimeException commandFailure) {
                    dispatched = false;
                    plugin.getLogger().severe("Zusätzlicher Daily-Befehl für " + player.getName()
                            + " warf einen Fehler (Tag " + reward.day() + "): " + commandFailure.getMessage());
                }
                if (!dispatched) {
                    plugin.getLogger().severe("Zusätzlicher Daily-Befehl für " + player.getName()
                            + " fehlgeschlagen (Tag " + reward.day() + "): " + parsed);
                    configuredMessage(player, "daily.messages.command-warning",
                            "<yellow>Daily abgeholt, aber eine Zusatzbelohnung muss ein Admin prüfen.</yellow>");
                }
            }

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.2F);
            sendRewardMessage(player, payout);
            open(player);
        } finally {
            activeClaims.remove(uuid);
        }
    }

    private Map<CrateDefinition, Integer> resolveKeys(Player player, DailyRewardTier payout) {
        Map<CrateDefinition, Integer> requested = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : payout.keys().entrySet()) {
            CrateDefinition crate = crates.find(entry.getKey()).orElse(null);
            if (crate == null) {
                plugin.getLogger().severe("Daily-Auszahlung verweigert: unbekannte Crate '" + entry.getKey() + "'.");
                configuredMessage(player, "daily.messages.payout-error",
                        "<red>Deine Belohnung ist fehlerhaft konfiguriert. Bitte informiere einen Admin.</red>");
                return null;
            }
            requested.merge(crate, entry.getValue(), Integer::sum);
        }
        return requested;
    }

    private void rollbackPayout(Player player, DailyProgress progress, int day, DailyRewardTier payout,
                                boolean coinsPaid, boolean lumisPaid, boolean keysPaid,
                                RuntimeException failure) {
        plugin.getLogger().severe("Daily-Auszahlung für " + player.getName() + " fehlgeschlagen: " + failure.getMessage());
        if (keysPaid) {
            plugin.getLogger().severe("Daily-Fortschritt wird nicht zurückgesetzt, weil Keys bereits vergeben wurden.");
            configuredMessage(player, "daily.messages.payout-error",
                    "<red>Die Auszahlung war unvollständig. Ein Admin muss sie prüfen.</red>");
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean payoutReverted = true;
        if (lumisPaid) {
            try {
                if (!lumis.withdraw(uuid, payout.lumis())) {
                    payoutReverted = false;
                    plugin.getLogger().severe("Daily-Rollback der Lumis für " + player.getName() + " ist fehlgeschlagen.");
                }
            } catch (RuntimeException rollbackFailure) {
                payoutReverted = false;
                plugin.getLogger().severe("Daily-Rollback der Lumis für " + player.getName()
                        + " warf einen Fehler: " + rollbackFailure.getMessage());
            }
        }
        if (coinsPaid) {
            try {
                EconomyOperationResult rollback = economy.withdraw(uuid, payout.coins(),
                        "DAILY-ROLLBACK-DAY-" + day, ActionContext.system(uuid));
                if (rollback != EconomyOperationResult.SUCCESS) {
                    payoutReverted = false;
                    plugin.getLogger().severe("Daily-Rollback der Coins für " + player.getName()
                            + " ist fehlgeschlagen: " + rollback);
                }
            } catch (RuntimeException rollbackFailure) {
                payoutReverted = false;
                plugin.getLogger().severe("Daily-Rollback der Coins für " + player.getName()
                        + " warf einen Fehler: " + rollbackFailure.getMessage());
            }
        }

        // Der Fortschritt darf nur zurückgesetzt werden, wenn wirklich jede bereits gezahlte
        // Teilbelohnung entfernt wurde. Sonst würde ein erneuter Klick die Zahlung duplizieren.
        if (!payoutReverted) {
            plugin.getLogger().severe("Daily-Fortschritt wird zum Dupe-Schutz nicht zurückgesetzt. "
                    + "Die Auszahlung muss für " + player.getName() + " manuell geprüft werden.");
            configuredMessage(player, "daily.messages.payout-error",
                    "<red>Die Auszahlung war unvollständig. Ein Admin muss sie prüfen.</red>");
            return;
        }
        try {
            manager.restore(progress);
        } catch (Exception restoreFailure) {
            plugin.getLogger().severe("Daily-Fortschritt von " + player.getName()
                    + " konnte nicht wiederhergestellt werden: " + restoreFailure.getMessage());
            configuredMessage(player, "daily.messages.payout-error",
                    "<red>Die Auszahlung war unvollständig. Ein Admin muss sie prüfen.</red>");
            return;
        }
        configuredMessage(player, "daily.messages.payout-error",
                "<red>Deine Belohnung konnte nicht vollständig ausgezahlt werden. Bitte versuche es erneut.</red>");
    }

    private void sendRewardMessage(Player player, DailyRewardTier payout) {
        List<String> parts = new ArrayList<>();
        if (payout.coins() > 0) parts.add("<gold>" + MenuFormat.integer(payout.coins()) + " Coins</gold>");
        if (payout.lumis() > 0) parts.add("<aqua>" + MenuFormat.integer(payout.lumis()) + " Lumis</aqua>");
        payout.keys().forEach((crateId, amount) -> parts.add(
                "<light_purple>" + amount + "× " + keyDisplayName(crateId) + "</light_purple>"));
        if (!payout.commands().isEmpty()) parts.add("<gray>eine Zusatzbelohnung</gray>");
        String rewardText = parts.isEmpty()
                ? "<gray>keine konfigurierte Belohnung</gray>"
                : String.join("<gray>, </gray>", parts);
        plugin.messages().sendConfiguredAuto(player, plugin.configs().daily(), "daily.messages.claimed",
                "<green><bold>✔ Daily abgeholt!</bold></green> <gray>Du hast %reward%<gray> erhalten.</gray>",
                "%reward%", rewardText);
    }

    private void configuredMessage(Player player, String path, String fallback) {
        plugin.messages().sendConfiguredAuto(player, plugin.configs().daily(), path, fallback);
    }

    private enum DayState {
        CLAIMED,
        AVAILABLE,
        LOCKED,
        RANK_LOCKED
    }
}
