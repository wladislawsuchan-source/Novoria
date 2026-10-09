package de.walahi.novosmp.professions;

import de.walahi.novosmp.angler.FishDefinition;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.gui.ConfirmGui;
import de.walahi.smpcore.gui.Gui;
import de.walahi.smpcore.gui.GuiButton;
import de.walahi.smpcore.gui.MaterialResolver;
import de.walahi.smpcore.gui.MenuFormat;
import de.walahi.smpcore.gui.MiniMessageItems;
import de.walahi.smpcore.gui.SlotLayout;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Slot-oriented profession menus. */
public final class ProfessionMenu {
    private static final List<Integer> CENTERED_PROFESSION_SLOTS = List.of(11, 12, 13, 14, 15);
    private static final List<Integer> LEGACY_PROFESSION_SLOTS = List.of(10, 12, 14, 16, 18);
    private static final String FARMER = "farmer";

    private final SMPCorePlugin plugin;
    private final ProfessionManager manager;
    private final ProfessionConfig config;
    private final MiniMessageItems items = new MiniMessageItems();

    public ProfessionMenu(SMPCorePlugin plugin, ProfessionManager manager, ProfessionConfig config) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
    }

    public void openMain(Player player) {
        int rows = rows("menus.main.rows", 5);
        int size = rows * 9;
        Gui gui = withSounds(new Gui(rows, items.component(config.string("menus.main.title", "<dark_gray>Berufe</dark_gray>"))));
        gui.filler(MaterialResolver.resolve(config.string("menus.main.filler", "GRAY_STAINED_GLASS_PANE"),
                Material.GRAY_STAINED_GLASS_PANE));

        PlayerProfessionState state = manager.state(player.getUniqueId());
        List<Integer> activeSlots = SlotLayout.configured(plugin.configs().professions(),
                "menus.main.active-slot-list", size, CENTERED_PROFESSION_SLOTS);
        if (activeSlots.equals(LEGACY_PROFESSION_SLOTS)) activeSlots = CENTERED_PROFESSION_SLOTS;
        int visibleSlots = Math.min(config.maxActiveProfessionSlots(), activeSlots.size());
        for (int index = 0; index < visibleSlots; index++) {
            addProfessionSlot(gui, player, state, index, activeSlots.get(index));
        }

        gui.item(slot("menus.main.info-slot", size, 22), overviewItem(state));
        gui.item(slot("menus.main.booster-slot", size, 40), boosterStatus(player));
        gui.open(player);
    }

    private void addProfessionSlot(Gui gui, Player player, PlayerProfessionState state,
                                   int slotIndex, int inventorySlot) {
        gui.button(inventorySlot, GuiButton.of(activeSlotItem(state, slotIndex), event -> {
            if (slotIndex >= state.slotLimit()) {
                player.sendRichMessage(config.string("messages.slot-locked",
                        "<red>Dieser Berufsslot ist noch nicht freigeschaltet.</red>"));
                return;
            }
            String active = professionAt(state, slotIndex);
            if (active == null) {
                openSelection(player, slotIndex);
                return;
            }
            if (event.isRightClick()) {
                openSelection(player, slotIndex);
                return;
            }
            openProfessionDetails(player, active);
        }));
    }

    private void openSelection(Player player, int slotIndex) {
        PlayerProfessionState state = manager.state(player.getUniqueId());
        if (slotIndex < 0 || slotIndex >= state.slotLimit()) {
            player.sendRichMessage(config.string("messages.slot-locked",
                    "<red>Dieser Berufsslot ist noch nicht freigeschaltet.</red>"));
            openMain(player);
            return;
        }

        int rows = rows("menus.selection.rows", 5);
        int size = rows * 9;
        String title = config.string("menus.selection.title", "<dark_gray>Beruf für Slot %slot% wählen</dark_gray>")
                .replace("%slot%", Integer.toString(slotIndex + 1));
        Gui gui = withSounds(new Gui(rows, items.component(title)));
        gui.filler(MaterialResolver.resolve(config.string("menus.selection.filler", "PURPLE_STAINED_GLASS_PANE"),
                Material.PURPLE_STAINED_GLASS_PANE));

        gui.item(slot("menus.selection.slot-info", size, 4), selectionSlotInfo(player, state, slotIndex));
        addSelectionButton(gui, player, state, slotIndex, ProfessionManager.LUMBERJACK,
                slot("menus.selection.professions.holzfaeller", size, 20));
        addSelectionButton(gui, player, state, slotIndex, ProfessionManager.MINER,
                slot("menus.selection.professions.bergarbeiter", size, 22));
        addSelectionButton(gui, player, state, slotIndex, ProfessionManager.HUNTER,
                slot("menus.selection.professions.jaeger", size, 24));

        gui.item(slot("menus.selection.professions.farmer", size, 30),
                professionSelectionItem(player, slotIndex, FARMER, false));
        addSelectionButton(gui, player, state, slotIndex, ProfessionManager.ANGLER,
                slot("menus.selection.professions.fischer", size, 32));

        gui.button(slot("menus.selection.back-slot", size, 36), GuiButton.of(
                items.item(Material.ARROW, "<yellow>Zurück</yellow>", List.of("<gray>Zur Berufsslot-Übersicht</gray>")),
                event -> openMain(player)));
        gui.open(player);
    }

    private void addSelectionButton(Gui gui, Player player, PlayerProfessionState state,
                                    int slotIndex, String professionId, int menuSlot) {
        gui.button(menuSlot, GuiButton.of(professionSelectionItem(player, slotIndex, professionId, true), event -> {
            if (state.isActive(professionId)) {
                player.sendRichMessage("<yellow>" + professionName(professionId)
                        + " ist bereits in einem Berufsslot aktiv.</yellow>");
                return;
            }
            openProfessionConfirmation(player, slotIndex, professionId);
        }));
    }

    private void openProfessionConfirmation(Player player, int slotIndex, String professionId) {
        if (!isImplemented(professionId)) return;
        PlayerProfessionState state = manager.state(player.getUniqueId());
        String previous = professionAt(state, slotIndex);
        long cost = manager.selectionCost(player, slotIndex);
        boolean freeSwitch = manager.usesFreeSwitch(player, slotIndex);

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Berufsslot: <white>" + (slotIndex + 1) + "</white></gray>");
        if (previous == null) lore.add("<gray>Bisher: <dark_gray>freier Slot</dark_gray></gray>");
        else lore.add("<gray>Wird ersetzt: <red>" + professionName(previous) + "</red></gray>");
        lore.add("<gray>Neuer Beruf: <green>" + professionName(professionId) + "</green></gray>");
        lore.add("");
        if (previous == null) lore.add("<green>Kostenlos, da der Slot frei ist.</green>");
        else if (freeSwitch) lore.add("<green>Einmaliger kostenloser Wechsel wird verwendet.</green>");
        else lore.add("<gray>Kosten: <gold>" + MenuFormat.integer(cost) + " Coins</gold></gray>");
        lore.add("");
        lore.add("<aqua>Dein Fortschritt im bisherigen Beruf bleibt erhalten.</aqua>");
        lore.add("<green>Klicken zum Bestätigen</green>");

        ItemStack confirm = items.item(Material.LIME_DYE,
                "<green>" + professionName(professionId) + " auswählen</green>", lore);
        ItemStack cancel = items.item(Material.RED_DYE, "<red>Abbrechen</red>",
                List.of("<gray>Es wird nichts verändert.</gray>"));
        ConfirmGui.open(player,
                items.component(config.string("menus.selection.confirm-title", "<dark_gray>Beruf auswählen?</dark_gray>")),
                confirm, cancel,
                () -> {
                    manager.activateProfession(player, slotIndex, professionId);
                    openMain(player);
                }, () -> openSelection(player, slotIndex));
    }

    public void openLumberjack(Player player) {
        openProfession(player, ProfessionManager.LUMBERJACK);
    }

    public void openMiner(Player player) {
        openProfession(player, ProfessionManager.MINER);
    }

    public void openHunter(Player player) {
        openProfession(player, ProfessionManager.HUNTER);
    }

    public void openAngler(Player player) { openProfession(player, ProfessionManager.ANGLER); }

    private void openProfession(Player player, String professionId) {
        String menuId = menuId(professionId);
        int rows = rows("menus." + menuId + ".rows", 6);
        int size = rows * 9;
        String fallbackTitle = ProfessionManager.MINER.equals(professionId)
                ? "<dark_aqua>Bergarbeiter</dark_aqua>"
                : ProfessionManager.HUNTER.equals(professionId) ? "<dark_red>Jäger</dark_red>"
                : ProfessionManager.ANGLER.equals(professionId) ? "<dark_aqua>Angler</dark_aqua>"
                : "<dark_green>Holzfäller</dark_green>";
        Gui gui = withSounds(new Gui(rows, items.component(config.string("menus." + menuId + ".title", fallbackTitle))));
        Material fallbackFiller = ProfessionManager.MINER.equals(professionId)
                ? Material.CYAN_STAINED_GLASS_PANE
                : ProfessionManager.HUNTER.equals(professionId) ? Material.RED_STAINED_GLASS_PANE
                : ProfessionManager.ANGLER.equals(professionId) ? Material.BLUE_STAINED_GLASS_PANE
                : Material.BROWN_STAINED_GLASS_PANE;
        gui.filler(MaterialResolver.resolve(config.string("menus." + menuId + ".filler", fallbackFiller.name()),
                fallbackFiller));

        ProfessionProgress progress = manager.progress(player.getUniqueId(), professionId);
        gui.item(slot("menus." + menuId + ".status-slot", size, 4), statusItem(player, professionId, progress));
        gui.item(slot("menus." + menuId + ".progress-slot", size, 13), progressItem(professionId, progress));
        addProgressBar(gui, professionId, progress, menuId, size);

        int pending = manager.pendingMilestone(progress);
        List<Integer> milestoneSlots = SlotLayout.configured(plugin.configs().professions(),
                "menus." + menuId + ".milestone-slots", size, List.of(28, 29, 30, 31));
        List<Integer> milestones = config.milestones();
        for (int index = 0; index < Math.min(milestoneSlots.size(), milestones.size()); index++) {
            int milestone = milestones.get(index);
            ItemStack milestoneIcon = milestoneItem(professionId, progress, milestone, pending);
            if (milestone == pending) {
                gui.button(milestoneSlots.get(index), GuiButton.of(milestoneIcon,
                        event -> openContribution(player, professionId, milestone)));
            } else gui.item(milestoneSlots.get(index), milestoneIcon);
        }

        gui.button(slot("menus." + menuId + ".contribution-slot", size, 33), GuiButton.of(
                contributionOverviewItem(professionId, progress, pending), event -> {
                    if (pending > 0) openContribution(player, professionId, pending);
                }));

        int rewards = manager.availableRewardCount(player, professionId);
        gui.button(slot("menus." + menuId + ".rewards-slot", size, 34), GuiButton.of(
                rewardsOverviewItem(rewards), event -> openRewards(player, professionId)));

        gui.button(slot("menus." + menuId + ".prestige-slot", size, 35), GuiButton.of(
                prestigeItem(player, professionId, progress), event -> openPrestigeConfirmation(player, professionId, progress)));

        if (ProfessionManager.LUMBERJACK.equals(professionId)) {
            gui.button(slot("menus." + menuId + ".replacement-slot", size, 49), GuiButton.of(
                    replacementItem(progress), event -> openReplacementConfirmation(player, progress)));
        } else if (ProfessionManager.MINER.equals(professionId)) {
            gui.button(slot("menus." + menuId + ".replacement-slot", size, 49), GuiButton.of(
                    minerToolInfo(progress), event -> openMinerToolCollection(player)));
        } else if (ProfessionManager.HUNTER.equals(professionId)) {
            gui.button(slot("menus." + menuId + ".replacement-slot", size, 49), GuiButton.of(
                    hunterRewardInfo(progress), event -> openHunterRewardCollection(player)));
        } else if (ProfessionManager.ANGLER.equals(professionId) && manager.anglerFeature() != null) {
            var anglerConfig = plugin.configs().angler();
            gui.button(slotAngler("gui.profession.storage-slot", size, 48), GuiButton.of(
                    items.item(MaterialResolver.resolve(anglerConfig.getString("gui.profession.storage-material", "BARREL"), Material.BARREL),
                            anglerConfig.getString("gui.profession.storage-name", "<aqua>Fanglager</aqua>"),
                            anglerConfig.getStringList("gui.profession.storage-lore")),
                    event -> manager.anglerFeature().openStorage(player)));
            gui.button(slotAngler("gui.profession.fish-slot", size, 50), GuiButton.of(
                    items.item(MaterialResolver.resolve(anglerConfig.getString("gui.profession.fish-material", "COD"), Material.COD),
                            anglerConfig.getString("gui.profession.fish-name", "<aqua>Fische</aqua>"),
                            anglerConfig.getStringList("gui.profession.fish-lore")),
                    event -> manager.anglerFeature().openFish(player)));
        }

        gui.button(slot("menus." + menuId + ".back-slot", size, 45), GuiButton.of(
                items.item(Material.ARROW, "<yellow>Zurück</yellow>", List.of("<gray>Zur Berufsslot-Übersicht</gray>")),
                event -> openMain(player)));
        gui.open(player);
    }

    void openContribution(Player player, int milestone) {
        openContribution(player, ProfessionManager.LUMBERJACK, milestone);
    }

    void openContribution(Player player, String professionId, int milestone) {
        int rows = rows("menus.contribution.rows", 6);
        int size = rows * 9;
        String title = config.string("menus.contribution.title", "<dark_gray>%profession%-Abgabe</dark_gray>")
                .replace("%profession%", professionName(professionId));
        Gui gui = withSounds(new Gui(rows, items.component(title)));
        Material filler = ProfessionManager.MINER.equals(professionId)
                ? Material.CYAN_STAINED_GLASS_PANE
                : ProfessionManager.HUNTER.equals(professionId) ? Material.RED_STAINED_GLASS_PANE
                : ProfessionManager.ANGLER.equals(professionId) ? Material.BLUE_STAINED_GLASS_PANE
                : Material.BROWN_STAINED_GLASS_PANE;
        gui.filler(filler);

        ProfessionProgress progress = manager.progress(player.getUniqueId(), professionId);
        if (!manager.isContributionMilestone(player, professionId, milestone)) {
            openProfession(player, professionId);
            return;
        }
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        Map<String, Long> contributed = manager.contributions(player, professionId, milestone);
        int requirementCount = requirement.materials().size() + requirement.growths().size()
                + requirement.mined().size() + requirement.hunts().size() + requirement.fish().size()
                + requirement.skills().size();
        List<Integer> requirementSlots = expandedRequirementSlots(
                SlotLayout.configured(plugin.configs().professions(),
                        "menus.contribution.requirement-slots", size,
                        List.of(9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35)),
                size, requirementCount);

        int index = 0;
        for (Map.Entry<String, Long> entry : requirement.materials().entrySet()) {
            if (index >= requirementSlots.size()) break;
            MaterialGroup group = config.materialGroup(entry.getKey());
            if (group == null) continue;
            long current = contributed.getOrDefault(entry.getKey(), 0L);
            long required = entry.getValue();
            boolean complete = current >= required;
            List<String> lore = new ArrayList<>();
            lore.add(progressLine(current, required));
            lore.add(progressBarLine(current, required, 12));
            lore.add("<gray>Fehlt: <yellow>" + MenuFormat.integer(Math.max(0L, required - current)) + "</yellow></gray>");
            if (!complete) {
                lore.add("<gray>Im Inventar: <white>" + MenuFormat.integer(manager.inventoryAmount(player, group.materials()))
                        + " passende Items</white></gray>");
            }
            if (!group.description().isEmpty()) { lore.add(""); lore.addAll(group.description()); }
            lore.add("");
            lore.add(complete ? "<green>✓ Abgeschlossen</green>" : "<yellow>Klicken: passende Items einzahlen</yellow>");
            ItemStack item = items.item(complete ? Material.BLACK_STAINED_GLASS_PANE : group.icon(),
                    complete ? "<green>" + group.displayName() + "</green>" : "<yellow>" + group.displayName() + "</yellow>", lore);
            String groupId = entry.getKey();
            int targetSlot = requirementSlots.get(index++);
            if (complete) {
                gui.item(targetSlot, item);
            } else {
                gui.button(targetSlot, GuiButton.of(item, event -> {
                    manager.contribute(player, professionId, milestone, groupId);
                    openContribution(player, professionId, milestone);
                }));
            }
        }

        for (Map.Entry<String, Long> entry : requirement.fish().entrySet()) {
            if (index >= requirementSlots.size()) break;
            FishDefinition fish = manager.fishRegistry().find(entry.getKey());
            if (fish == null) continue;
            long current = contributed.getOrDefault(manager.fishContributionKey(entry.getKey()), 0L);
            long required = entry.getValue();
            boolean complete = current >= required;
            List<String> lore = new ArrayList<>();
            lore.add(progressLine(current, required));
            lore.add(progressBarLine(current, required, 12));
            lore.add("<gray>Fehlt: <yellow>" + MenuFormat.integer(Math.max(0L, required - current)) + "</yellow></gray>");
            if (!complete) lore.add("<gray>Im Inventar: <white>"
                    + MenuFormat.integer(manager.inventoryFishAmount(player, fish.id())) + " passende Fische</white></gray>");
            lore.add(complete ? "<green>✓ Abgeschlossen</green>" : "<yellow>Klicken: passende Fische einzahlen</yellow>");
            ItemStack item = items.item(complete ? Material.BLACK_STAINED_GLASS_PANE : fish.material(),
                    (complete ? "<green>" : fish.color()) + fish.displayName(), lore);
            String fishId = fish.id();
            int targetSlot = requirementSlots.get(index++);
            if (complete) gui.item(targetSlot, item);
            else gui.button(targetSlot, GuiButton.of(item, event -> {
                manager.contribute(player, professionId, milestone, fishId);
                openContribution(player, professionId, milestone);
            }));
        }

        for (Map.Entry<String, Long> entry : requirement.growths().entrySet()) {
            if (index >= requirementSlots.size()) break;
            GrowthGroup group = config.growthGroup(entry.getKey());
            if (group == null) continue;
            long current = contributed.getOrDefault(manager.growthContributionKey(entry.getKey()), 0L);
            long required = entry.getValue();
            boolean complete = current >= required;
            List<String> lore = new ArrayList<>();
            lore.add(progressLine(current, required));
            lore.add(progressBarLine(current, required, 12));
            if (!group.description().isEmpty()) { lore.add(""); lore.addAll(group.description()); }
            lore.add("");
            lore.add(complete ? "<green>✓ Aufforstung abgeschlossen</green>" : "<gray>Pflanze den passenden Setzling.</gray>");
            if (!complete) lore.add("<yellow>Er zählt erst, wenn daraus wirklich ein Baum wächst.</yellow>");
            gui.item(requirementSlots.get(index++), items.item(
                    complete ? Material.BLACK_STAINED_GLASS_PANE : group.icon(),
                    complete ? "<green>" + group.displayName() + "</green>" : "<aqua>" + group.displayName() + "</aqua>", lore));
        }

        for (Map.Entry<String, Long> entry : requirement.mined().entrySet()) {
            if (index >= requirementSlots.size()) break;
            MinedGroup group = config.minedGroup(entry.getKey());
            if (group == null) continue;
            long current = contributed.getOrDefault(manager.minedContributionKey(entry.getKey()), 0L);
            long required = entry.getValue();
            boolean complete = current >= required;
            List<String> lore = new ArrayList<>();
            lore.add(progressLine(current, required));
            lore.add(progressBarLine(current, required, 12));
            lore.add("<gray>Fehlt: <yellow>" + MenuFormat.integer(Math.max(0L, required - current)) + "</yellow></gray>");
            lore.add("");
            lore.addAll(group.description());
            if (!group.description().isEmpty()) lore.add("");
            lore.add(complete ? "<green>✓ Selbstabbau abgeschlossen</green>"
                    : "<aqua>Baue diese Blöcke selbst natürlich ab.</aqua>");
            gui.item(requirementSlots.get(index++), items.item(
                    complete ? Material.BLACK_STAINED_GLASS_PANE : group.icon(),
                    complete ? "<green>Selbst abbauen: " + group.displayName() + "</green>"
                            : "<aqua>Selbst abbauen: " + group.displayName() + "</aqua>", lore));
        }

        for (Map.Entry<String, Long> entry : requirement.hunts().entrySet()) {
            if (index >= requirementSlots.size()) break;
            HuntGroup group = config.huntGroup(entry.getKey());
            if (group == null) continue;
            long current = contributed.getOrDefault(manager.huntContributionKey(entry.getKey()), 0L);
            long required = entry.getValue();
            boolean complete = current >= required;
            List<String> lore = new ArrayList<>();
            lore.add(progressLine(current, required));
            lore.add(progressBarLine(current, required, 12));
            lore.add("<gray>Fehlt: <yellow>" + MenuFormat.integer(Math.max(0L, required - current)) + "</yellow></gray>");
            if (!group.description().isEmpty()) { lore.add(""); lore.addAll(group.description()); }
            lore.add("");
            lore.add(complete ? "<green>✓ Jagdauftrag abgeschlossen</green>"
                    : "<red>Töte diese Mobs selbst.</red>");
            gui.item(requirementSlots.get(index++), items.item(
                    complete ? Material.BLACK_STAINED_GLASS_PANE : group.icon(),
                    complete ? "<green>Jagen: " + group.displayName() + "</green>"
                            : "<red>Jagen: " + group.displayName() + "</red>", lore));
        }

        for (Map.Entry<String, Long> entry : requirement.skills().entrySet()) {
            if (index >= requirementSlots.size()) break;
            long current = contributed.getOrDefault(entry.getKey(), 0L);
            long required = entry.getValue();
            boolean complete = current >= required;
            String name = plugin.configs().angler().getString("gui.skills." + entry.getKey() + ".name",
                    entry.getKey());
            Material icon = MaterialResolver.resolve(plugin.configs().angler().getString(
                    "gui.skills." + entry.getKey() + ".material", "LIME_DYE"), Material.LIME_DYE);
            List<String> lore = List.of(progressLine(current, required), progressBarLine(current, required, 12),
                    "<gray>Fehlt: <yellow>" + MenuFormat.integer(Math.max(0L, required - current)) + "</yellow></gray>",
                    complete ? "<green>✓ Ziel erreicht</green>" : "<aqua>Durch aktives Angeln erfüllen.</aqua>");
            gui.item(requirementSlots.get(index++), items.item(
                    complete ? Material.BLACK_STAINED_GLASS_PANE : icon,
                    complete ? "<green>" + name + "</green>" : "<aqua>" + name + "</aqua>", lore));
        }

        gui.button(slot("menus.contribution.deposit-all-slot", size, 47), GuiButton.of(
                items.item(Material.HOPPER, "<gold>Alles Passende einzahlen</gold>",
                        List.of("<gray>Sucht dein Inventar nach allen aktuell</gray>", "<gray>benötigten Abgabe-Items ab.</gray>")),
                event -> { manager.contributeAll(player, professionId, milestone); openContribution(player, professionId, milestone); }));

        gui.button(slot("menus.contribution.deposit-inventory-slot", size, 51), GuiButton.of(
                items.item(Material.CHEST, "<aqua>Einzahlungsinventar öffnen</aqua>",
                        List.of("<gray>Lege verschiedene Materialien gemeinsam hinein.</gray>",
                                "<gray>Falsche Items und Überschüsse erhältst du zurück.</gray>")),
                event -> manager.openContributionDeposit(player, professionId, milestone)));

        boolean materialsComplete = requirement.materials().entrySet().stream()
                .allMatch(entry -> contributed.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
        boolean fishComplete = requirement.fish().entrySet().stream()
                .allMatch(entry -> contributed.getOrDefault(manager.fishContributionKey(entry.getKey()), 0L) >= entry.getValue());
        boolean growthComplete = requirement.growths().entrySet().stream()
                .allMatch(entry -> contributed.getOrDefault(manager.growthContributionKey(entry.getKey()), 0L) >= entry.getValue());
        boolean minedComplete = requirement.mined().entrySet().stream()
                .allMatch(entry -> contributed.getOrDefault(manager.minedContributionKey(entry.getKey()), 0L) >= entry.getValue());
        boolean huntsComplete = requirement.hunts().entrySet().stream()
                .allMatch(entry -> contributed.getOrDefault(manager.huntContributionKey(entry.getKey()), 0L) >= entry.getValue());
        boolean skillsComplete = requirement.skills().entrySet().stream()
                .allMatch(entry -> contributed.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
        boolean requirementsComplete = materialsComplete && fishComplete && growthComplete && minedComplete
                && huntsComplete && skillsComplete;
        boolean levelReached = progress.level() >= milestone;
        long coinBalance = manager.coinBalance(player);
        boolean coinsReady = requirement.coinCost() <= 0L || coinBalance >= requirement.coinCost();
        boolean canComplete = requirementsComplete && levelReached && coinsReady;

        List<String> completeLore = new ArrayList<>();
        completeLore.add("<gray>Materialien: " + statusWord(materialsComplete) + "</gray>");
        if (!requirement.fish().isEmpty()) completeLore.add("<gray>Fische: " + statusWord(fishComplete) + "</gray>");
        if (!requirement.growths().isEmpty()) completeLore.add("<gray>Aufforstung: " + statusWord(growthComplete) + "</gray>");
        if (!requirement.mined().isEmpty()) completeLore.add("<gray>Selbst abgebaut: " + statusWord(minedComplete) + "</gray>");
        if (!requirement.hunts().isEmpty()) completeLore.add("<gray>Jagdauftrag: " + statusWord(huntsComplete) + "</gray>");
        if (!requirement.skills().isEmpty()) completeLore.add("<gray>Angler-Skillziele: " + statusWord(skillsComplete) + "</gray>");
        completeLore.add("<gray>Level " + milestone + ": " + (levelReached ? "<green>erreicht</green>" : "<yellow>noch nicht erreicht</yellow>") + "</gray>");
        if (requirement.coinCost() > 0L) {
            completeLore.add("<gray>Coins: <gold>" + MenuFormat.integer(coinBalance) + "/" + MenuFormat.integer(requirement.coinCost())
                    + "</gold> " + (coinsReady ? "<green>✓</green>" : "<red>✗</red>") + "</gray>");
        }
        completeLore.add("");
        if (canComplete) completeLore.add("<green>Klicken zum Abschließen</green>");
        else if (!requirementsComplete) completeLore.add("<dark_gray>Fortschritt kann bereits vor dem Ziellevel gesammelt werden.</dark_gray>");
        else if (!coinsReady) completeLore.add("<red>Dir fehlen noch " + MenuFormat.integer(requirement.coinCost() - coinBalance) + " Coins.</red>");
        else completeLore.add("<yellow>Alles gespeichert • Abschluss ab Level " + milestone + "</yellow>");

        ItemStack completeItem = items.item(canComplete ? Material.LIME_DYE : requirementsComplete && coinsReady ? Material.YELLOW_DYE : Material.RED_DYE,
                canComplete ? "<green>Meilenstein abschließen</green>" : requirementsComplete ? "<yellow>Voraussetzungen vollständig</yellow>" : "<red>Abgabe unvollständig</red>",
                completeLore);
        int completeSlot = slot("menus.contribution.complete-slot", size, 49);
        if (canComplete) {
            gui.button(completeSlot, GuiButton.of(completeItem, event -> {
                if (manager.completeMilestone(player, professionId, milestone)) openProfession(player, professionId);
                else openContribution(player, professionId, milestone);
            }));
        } else gui.item(completeSlot, completeItem);

        gui.button(slot("menus.contribution.back-slot", size, 45), GuiButton.of(
                items.item(Material.ARROW, "<yellow>Zurück</yellow>", List.of("<gray>Zur " + professionName(professionId) + "-Übersicht</gray>")),
                event -> openProfession(player, professionId)));
        gui.open(player);
    }

    void openRewards(Player player, String professionId) {
        int rows = rows("menus.rewards.rows", 6);
        int size = rows * 9;
        String title = config.string("menus.rewards.title", "<dark_gray>%profession%-Levelbelohnungen</dark_gray>")
                .replace("%profession%", professionName(professionId));
        Gui gui = withSounds(new Gui(rows, items.component(title)));
        gui.filler(MaterialResolver.resolve(config.string("menus.rewards.filler", "GREEN_STAINED_GLASS_PANE"),
                Material.GREEN_STAINED_GLASS_PANE));

        ProfessionProgress progress = manager.progress(player.getUniqueId(), professionId);
        Set<Integer> claimed = manager.claimedRewardLevels(player, professionId);
        List<Integer> rewardSlots = SlotLayout.configured(plugin.configs().professions(), "menus.rewards.reward-slots", size,
                List.of(10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32));
        List<LevelReward> rewards = config.rewards();
        for (int index = 0; index < Math.min(rewardSlots.size(), rewards.size()); index++) {
            LevelReward reward = rewards.get(index);
            boolean unlocked = progress.level() >= reward.level();
            boolean done = claimed.contains(reward.level());
            ItemStack item = rewardItem(reward, progress.prestige(), unlocked, done);
            if (unlocked && !done) {
                gui.button(rewardSlots.get(index), GuiButton.of(item, event -> {
                    manager.claimReward(player, professionId, reward.level());
                    openRewards(player, professionId);
                }));
            } else gui.item(rewardSlots.get(index), item);
        }

        int infoSlot = slot("menus.rewards.info-slot", size, 49);
        int available = manager.availableRewardCount(player, professionId);
        if (available > 0) {
            gui.button(infoSlot, GuiButton.of(items.item(Material.HOPPER, "<green>Alle Belohnungen abholen</green>",
                    List.of("<gray>Abholbereit: <yellow>" + available + "</yellow></gray>",
                            "<gray>Belohnungen sind pro Prestige erneut verfügbar.</gray>", "", "<yellow>Klicken: alles abholen</yellow>")),
                    event -> { manager.claimAvailableRewards(player, professionId); openRewards(player, professionId); }));
        } else {
            gui.item(infoSlot, items.item(Material.BOOK, "<aqua>Levelbelohnungen</aqua>",
                    List.of("<gray>Aktuell ist nichts abholbereit.</gray>", "<gray>Belohnungen sind pro Prestige erneut verfügbar.</gray>")));
        }
        gui.button(slot("menus.rewards.back-slot", size, 45), GuiButton.of(
                items.item(Material.ARROW, "<yellow>Zurück</yellow>", List.of("<gray>Zur " + professionName(professionId) + "-Übersicht</gray>")),
                event -> openProfession(player, professionId)));
        gui.open(player);
    }

    private void openPrestigeConfirmation(Player player, String professionId, ProfessionProgress progress) {
        if (!manager.canPerformPrestige(player, professionId)) {
            manager.performPrestige(player, professionId);
            return;
        }
        int next = progress.prestige() + 1;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Level wird auf <yellow>1</yellow> zurückgesetzt.</gray>");
        lore.add("<red>Alle " + professionName(professionId) + "-XP dieses Prestiges gehen verloren.</red>");
        if (ProfessionManager.LUMBERJACK.equals(professionId)) {
            lore.add(progress.prestige() > 0
                    ? "<gray>Die alte, vollständig reparierte Berufsaxt wird abgegeben.</gray>"
                    : "<gray>Du erhältst dein erstes Berufswerkzeug.</gray>");
        } else if (ProfessionManager.ANGLER.equals(professionId)) {
            lore.add("<gray>Dein Fanglager wächst je nach Prestige.</gray>");
            lore.add("<gray>Es gibt keine spezielle Angler-Rute.</gray>");
        } else {
            lore.add("<green>Deine bisherigen Bergarbeiter-Werkzeuge bleiben erhalten.</green>");
            lore.add("<gray>Du erhältst ein weiteres Mending-fähiges Prestige-Werkzeug.</gray>");
        }
        lore.add("<gray>Du erhältst einen einmaligen kostenlosen Berufswechsel.</gray>");
        lore.add("");
        lore.add("<green>Klicken zum Aufsteigen</green>");
        ItemStack confirm = items.item(Material.NETHER_STAR, "<gold>Prestige " + roman(next) + " bestätigen</gold>", lore);
        ItemStack cancel = items.item(Material.RED_DYE, "<red>Abbrechen</red>", List.of());
        ConfirmGui.open(player, items.component("<dark_gray>Prestige aufsteigen?</dark_gray>"), confirm, cancel,
                () -> { manager.performPrestige(player, professionId); openProfession(player, professionId); },
                () -> openProfession(player, professionId));
    }

    private void openReplacementConfirmation(Player player, ProfessionProgress progress) {
        if (progress.prestige() <= 0) {
            manager.replaceTool(player);
            return;
        }
        List<String> lore = new ArrayList<>(config.stringList("replacement-warning.lines"));
        lore.add("");
        lore.add("<gray>Preis: <gold>" + MenuFormat.integer(config.replacementPrice(progress.prestige())) + " Coins</gold></gray>");
        lore.add("<green>Klicken zum endgültigen Kauf</green>");
        ItemStack confirm = items.item(Material.LIME_DYE, "<green>Ersatzwerkzeug kaufen</green>", lore);
        ItemStack cancel = items.item(Material.RED_DYE, "<red>Abbrechen</red>", List.of("<gray>Die alte Axt bleibt aktiv.</gray>"));
        ConfirmGui.open(player, items.component(config.string("menus.replacement-confirm.title", "<dark_red>Ersatzwerkzeug kaufen?</dark_red>")),
                confirm, cancel,
                () -> { manager.replaceTool(player); openLumberjack(player); },
                () -> openLumberjack(player));
    }

    private void openMinerToolCollection(Player player) {
        ProfessionProgress progress = manager.miner(player.getUniqueId());
        int rows = rows("menus.miner-tools.rows", 3);
        int size = rows * 9;
        Gui gui = withSounds(new Gui(rows, items.component(config.string("menus.miner-tools.title",
                "<dark_aqua>Bergarbeiter-Werkzeuge</dark_aqua>"))));
        gui.filler(MaterialResolver.resolve(config.string("menus.miner-tools.filler", "CYAN_STAINED_GLASS_PANE"),
                Material.CYAN_STAINED_GLASS_PANE));
        List<Integer> slots = SlotLayout.configured(plugin.configs().professions(),
                "menus.miner-tools.tool-slots", size, List.of(11, 12, 13, 14, 15));
        for (int prestige = 1; prestige <= Math.min(config.maxPrestige(), slots.size()); prestige++) {
            final int toolPrestige = prestige;
            ItemStack icon = minerReplacementItem(progress, prestige);
            if (prestige <= progress.prestige()) {
                gui.button(slots.get(prestige - 1), GuiButton.of(icon,
                        event -> openMinerReplacementConfirmation(player, toolPrestige)));
            } else {
                gui.item(slots.get(prestige - 1), icon);
            }
        }
        gui.button(slot("menus.miner-tools.back-slot", size, 22), GuiButton.of(
                items.item(Material.ARROW, "<yellow>Zurück</yellow>", List.of("<gray>Zum Bergarbeiter-Beruf</gray>")),
                event -> openMiner(player)));
        gui.open(player);
    }

    private void openMinerReplacementConfirmation(Player player, int toolPrestige) {
        ProfessionProgress progress = manager.miner(player.getUniqueId());
        if (toolPrestige <= 0 || toolPrestige > progress.prestige()) {
            openMinerToolCollection(player);
            return;
        }
        long price = config.replacementPrice(ProfessionManager.MINER, toolPrestige);
        List<String> lore = new ArrayList<>(config.stringList("miner-replacement-warning.lines"));
        if (lore.isEmpty()) {
            lore.add("<red><bold>⚠ Achtung!</bold></red>");
            lore.add("<gray>Eine bereits registrierte Kopie dieser Werkzeugstufe</gray>");
            lore.add("<gray>wird dauerhaft deaktiviert.</gray>");
        }
        lore.add("");
        lore.add("<gray>Werkzeug: <yellow>" + minerToolName(toolPrestige) + "</yellow></gray>");
        lore.add("<gray>Preis: <gold>" + MenuFormat.integer(price) + " Coins</gold></gray>");
        lore.add("<green>Klicken zum endgültigen Kauf</green>");
        ItemStack confirm = items.item(Material.LIME_DYE, "<green>Ersatzwerkzeug kaufen</green>", lore);
        ItemStack cancel = items.item(Material.RED_DYE, "<red>Abbrechen</red>", List.of("<gray>Kein Werkzeug wird ersetzt.</gray>"));
        ConfirmGui.open(player, items.component(config.string("menus.miner-tools.confirm-title",
                        "<dark_red>Bergarbeiter-Ersatzwerkzeug?</dark_red>")),
                confirm, cancel,
                () -> { manager.replaceMinerTool(player, toolPrestige); openMinerToolCollection(player); },
                () -> openMinerToolCollection(player));
    }

    private void openHunterRewardCollection(Player player) {
        ProfessionProgress progress = manager.hunter(player.getUniqueId());
        int rows = rows("menus.hunter-rewards.rows", 3);
        int size = rows * 9;
        Gui gui = withSounds(new Gui(rows, items.component(config.string("menus.hunter-rewards.title",
                "<dark_red>Jäger-Arsenal</dark_red>"))));
        gui.filler(MaterialResolver.resolve(config.string("menus.hunter-rewards.filler", "RED_STAINED_GLASS_PANE"),
                Material.RED_STAINED_GLASS_PANE));
        List<Integer> slots = SlotLayout.configured(plugin.configs().professions(),
                "menus.hunter-rewards.reward-slots", size, List.of(11, 12, 13, 14, 15));
        for (int prestige = 1; prestige <= Math.min(config.maxPrestige(), slots.size()); prestige++) {
            final int rewardPrestige = prestige;
            ItemStack icon = hunterReplacementItem(progress, prestige);
            if (prestige <= progress.prestige()) {
                gui.button(slots.get(prestige - 1), GuiButton.of(icon,
                        event -> openHunterReplacementConfirmation(player, rewardPrestige)));
            } else gui.item(slots.get(prestige - 1), icon);
        }
        gui.button(slot("menus.hunter-rewards.back-slot", size, 22), GuiButton.of(
                items.item(Material.ARROW, "<yellow>Zurück</yellow>", List.of("<gray>Zum Jäger-Beruf</gray>")),
                event -> openHunter(player)));
        gui.open(player);
    }

    private void openHunterReplacementConfirmation(Player player, int rewardPrestige) {
        ProfessionProgress progress = manager.hunter(player.getUniqueId());
        if (rewardPrestige <= 0 || rewardPrestige > progress.prestige()) {
            openHunterRewardCollection(player);
            return;
        }
        long price = config.replacementPrice(ProfessionManager.HUNTER, rewardPrestige);
        List<String> lore = new ArrayList<>();
        lore.add("<red><bold>⚠ Achtung!</bold></red>");
        lore.add("<gray>Die bisher registrierte Kopie dieser Prestige-Belohnung</gray>");
        lore.add("<gray>wird dauerhaft deaktiviert und verliert ihre Spezialeffekte.</gray>");
        lore.add("");
        lore.add("<gray>Belohnung: <yellow>" + hunterRewardName(rewardPrestige) + "</yellow></gray>");
        lore.add("<gray>Preis: <gold>" + MenuFormat.integer(price) + " Coins</gold></gray>");
        lore.add("<green>Klicken zum endgültigen Kauf</green>");
        ItemStack confirm = items.item(Material.LIME_DYE, "<green>Ersatz kaufen</green>", lore);
        ItemStack cancel = items.item(Material.RED_DYE, "<red>Abbrechen</red>", List.of("<gray>Dein aktuelles Belohnungsitem bleibt aktiv.</gray>"));
        ConfirmGui.open(player, items.component(config.string("menus.hunter-rewards.confirm-title",
                        "<dark_red>Jäger-Ersatzbelohnung?</dark_red>")),
                confirm, cancel,
                () -> { manager.replaceHunterReward(player, rewardPrestige); openHunterRewardCollection(player); },
                () -> openHunterRewardCollection(player));
    }

    private void openProfessionDetails(Player player, String professionId) {
        if (isImplemented(professionId)) {
            openProfession(player, professionId);
            return;
        }
        player.sendRichMessage("<yellow>Dieser Beruf ist noch nicht verfügbar.</yellow>");
    }

    private ItemStack activeSlotItem(PlayerProfessionState state, int slotIndex) {
        if (slotIndex >= state.slotLimit()) {
            return items.item(Material.BARRIER, "<red>Berufsslot " + (slotIndex + 1) + " gesperrt</red>",
                    List.of("<gray>Erreiche Prestige III in einem weiteren Beruf,</gray>",
                            "<gray>um jeweils +1 Slot dauerhaft freizuschalten.</gray>"));
        }
        String id = professionAt(state, slotIndex);
        if (id == null) {
            return items.item(Material.LIME_STAINED_GLASS_PANE, "<green>Freier Berufsslot " + (slotIndex + 1) + "</green>",
                    List.of("<gray>Hier ist noch kein Beruf aktiv.</gray>", "", "<yellow>Klicken: Beruf auswählen</yellow>"));
        }
        ProfessionProgress progress = state.progress(id);
        return items.item(professionIcon(id), "<green><bold>Berufsslot " + (slotIndex + 1) + "</bold></green>",
                List.of("<gray>Beruf: <white>" + professionName(id) + "</white></gray>",
                        "<gray>Prestige: <gold>" + stars(progress.prestige()) + "</gold></gray>",
                        "<gray>Level: <yellow>" + progress.level() + "/" + config.maxLevel() + "</yellow></gray>", "",
                        "<green>Linksklick: Fortschritt öffnen</green>", "<yellow>Rechtsklick: Beruf wechseln</yellow>"));
    }

    private ItemStack overviewItem(PlayerProfessionState state) {
        return items.item(Material.WRITABLE_BOOK, "<light_purple><bold>Deine Berufe</bold></light_purple>",
                List.of("<gray>Aktive Slots: <white>" + state.activeProfessions().size() + "/" + state.slotLimit() + "</white></gray>",
                        "<gray>Freigeschaltet: <aqua>" + state.slotLimit() + "/" + config.maxActiveProfessionSlots() + " Berufsslots</aqua></gray>",
                        "<gray>Berufswechsel: <gold>" + MenuFormat.integer(config.switchCost()) + " Coins</gold></gray>",
                        "<gray>Kostenloser Wechsel: " + (state.freeSwitch() ? "<green>verfügbar</green>" : "<red>nicht verfügbar</red>") + "</gray>", "",
                        "<dark_gray>Jeder weitere Beruf auf Prestige III schaltet +1 Slot frei.</dark_gray>",
                        "<dark_gray>Nur aktive Berufe sammeln XP und erhalten Verkaufsboni.</dark_gray>"));
    }

    private ItemStack selectionSlotInfo(Player player, PlayerProfessionState state, int slotIndex) {
        String current = professionAt(state, slotIndex);
        long cost = manager.selectionCost(player, slotIndex);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Aktuell: " + (current == null ? "<dark_gray>frei</dark_gray>" : "<white>" + professionName(current) + "</white>") + "</gray>");
        if (current == null) lore.add("<green>Das erstmalige Belegen ist kostenlos.</green>");
        else if (manager.usesFreeSwitch(player, slotIndex)) lore.add("<green>Kostenloser Wechsel verfügbar.</green>");
        else lore.add("<gray>Wechselkosten: <gold>" + MenuFormat.integer(cost) + " Coins</gold></gray>");
        lore.add("<aqua>Gespeicherter Fortschritt geht beim Wechsel nie verloren.</aqua>");
        return items.item(Material.NAME_TAG, "<light_purple>Berufsslot " + (slotIndex + 1) + "</light_purple>", lore);
    }

    private ItemStack professionSelectionItem(Player player, int slotIndex, String professionId, boolean available) {
        PlayerProfessionState state = manager.state(player.getUniqueId());
        String color = available ? "<green>" : "<gray>";
        List<String> lore = new ArrayList<>();
        if (ProfessionManager.LUMBERJACK.equals(professionId)) {
            ProfessionProgress progress = manager.lumberjack(player.getUniqueId());
            lore.add("<gray>Fälle natürliche Holzblöcke und</gray>");
            lore.add("<gray>werte deine Holzschlag-Axt durch Prestige auf.</gray>");
            lore.add("");
            lore.add("<gray>Gespeichert: <gold>" + stars(progress.prestige()) + "</gold> • <yellow>Level " + progress.level() + "</yellow></gray>");
        } else if (ProfessionManager.MINER.equals(professionId)) {
            ProfessionProgress progress = manager.miner(player.getUniqueId());
            lore.add("<gray>Baue Gestein und Erze ab, erfülle</gray>");
            lore.add("<gray>Bergbau-Aufträge und sammle Prestige-Werkzeuge.</gray>");
            lore.add("");
            lore.add("<gray>Gespeichert: <gold>" + stars(progress.prestige()) + "</gold> • <yellow>Level " + progress.level() + "</yellow></gray>");
        } else if (ProfessionManager.HUNTER.equals(professionId)) {
            ProfessionProgress progress = manager.hunter(player.getUniqueId());
            lore.add("<gray>Jage Tiere, Monster und Bosse, erfülle</gray>");
            lore.add("<gray>Jagdaufträge und sammle besondere Kampfausrüstung.</gray>");
            lore.add("");
            lore.add("<gray>Gespeichert: <gold>" + stars(progress.prestige()) + "</gold> • <yellow>Level " + progress.level() + "</yellow></gray>");
        } else if (ProfessionManager.ANGLER.equals(professionId)) {
            ProfessionProgress progress = manager.angler(player.getUniqueId());
            lore.add("<gray>Fange Custom-Fische mit der normalen Angel.</gray>");
            lore.add("<gray>Meistere Fangzonen, Combo und Fanglager.</gray>");
            lore.add("");
            lore.add("<gray>Gespeichert: <gold>" + stars(progress.prestige()) + "</gold> • <yellow>Level " + progress.level() + "</yellow></gray>");
        } else if (FARMER.equals(professionId)) {
            lore.addAll(List.of("<gray>Landwirtschaft und Feldfrüchte.</gray>", "", "<dark_gray>Bald verfügbar</dark_gray>"));
        }
        if (available) {
            if (state.isActive(professionId)) lore.add("<red>Bereits in einem anderen Slot aktiv.</red>");
            else lore.add("<yellow>Klicken: Auswahl bestätigen</yellow>");
        }
        return items.item(professionIcon(professionId), color + "<bold>" + professionName(professionId) + "</bold>"
                + (available ? "</green>" : "</gray>"), lore);
    }

    private ItemStack statusItem(Player player, String professionId, ProfessionProgress progress) {
        double multiplier = config.sellMultiplier(professionId, progress.prestige());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Prestige: <gold>" + stars(progress.prestige()) + "</gold></gray>");
        lore.add("<gray>Level: <yellow>" + progress.level() + "/" + config.maxLevel() + "</yellow></gray>");
        lore.add("<gray>Verkaufsbonus: <green>×" + formatMultiplier(multiplier) + "</green></gray>");
        lore.add("<gray>Status: " + (manager.isActive(player.getUniqueId(), professionId) ? "<green>aktiv</green>" : "<red>nicht aktiv</red>") + "</gray>");
        if (ProfessionManager.MINER.equals(professionId)) {
            lore.add("");
            lore.add("<dark_gray>Nur natürliche Blöcke geben Berufs-XP.</dark_gray>");
            lore.add("<dark_gray>Obsidian aus Lava + Wasser zählt ebenfalls.</dark_gray>");
        } else if (ProfessionManager.HUNTER.equals(professionId)) {
            lore.add("");
            lore.add("<dark_gray>Spieler verursachte Mob-Kills geben Berufs-XP.</dark_gray>");
            lore.add("<dark_gray>Baby-Mobs geben keine Jäger-XP.</dark_gray>");
        } else if (ProfessionManager.ANGLER.equals(professionId)) {
            lore.add("");
            lore.add("<dark_gray>Erfolgreiche Minispiele geben Angler-XP.</dark_gray>");
        }
        return items.item(professionIcon(professionId), "<aqua>" + professionName(professionId) + "</aqua>", lore);
    }

    private ItemStack progressItem(String professionId, ProfessionProgress progress) {
        LevelNumbers numbers = levelNumbers(professionId, progress);
        return items.item(Material.EXPERIENCE_BOTTLE, "<yellow>Level-Fortschritt</yellow>",
                List.of("<gray>Level: <yellow>" + progress.level() + "/" + config.maxLevel() + "</yellow></gray>",
                        "<gray>XP im aktuellen Level: <aqua>" + MenuFormat.integer(numbers.current()) + "/" + MenuFormat.integer(numbers.needed()) + "</aqua></gray>",
                        "", "<dark_gray>Berufe-Booster erhöhen nur Berufs-XP.</dark_gray>"));
    }

    private void addProgressBar(Gui gui, String professionId, ProfessionProgress progress, String menuId, int size) {
        LevelNumbers numbers = levelNumbers(professionId, progress);
        List<Integer> slots = SlotLayout.configured(plugin.configs().professions(),
                "menus." + menuId + ".progress-bar-slots", size, List.of(19, 20, 21, 22, 23, 24, 25));
        double ratio = numbers.needed() <= 0 ? 1D : Math.min(1D, numbers.current() / (double) numbers.needed());
        int filled = (int) Math.round(ratio * slots.size());
        for (int i = 0; i < slots.size(); i++) {
            boolean active = i < filled;
            gui.item(slots.get(i), items.item(active ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    active ? "<green>Fortschritt</green>" : "<dark_gray>Fortschritt</dark_gray>", List.of()));
        }
    }

    private ItemStack contributionOverviewItem(String professionId, ProfessionProgress progress, int pending) {
        if (pending <= 0) {
            return items.item(Material.CHEST, "<green>Alle Abgaben abgeschlossen</green>",
                    List.of("<gray>Für dieses Prestige sind keine weiteren</gray>",
                            "<gray>Meilenstein-Abgaben offen.</gray>", "",
                            "<aqua>Offene Ziele im Chat: <white>/abgabe</white></aqua>"));
        }
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), pending);
        List<String> lore = new ArrayList<>();
        if (!requirement.materials().isEmpty())
            lore.add("<gray>Materialgruppen: <white>" + requirement.materials().size() + "</white></gray>");
        if (!requirement.mined().isEmpty())
            lore.add("<gray>Selbstabbau-Aufträge: <white>" + requirement.mined().size() + "</white></gray>");
        if (!requirement.growths().isEmpty())
            lore.add("<gray>Aufforstungen: <white>" + requirement.growths().size() + "</white></gray>");
        if (!requirement.hunts().isEmpty())
            lore.add("<gray>Jagdaufträge: <white>" + requirement.hunts().size() + "</white></gray>");
        if (!requirement.fish().isEmpty())
            lore.add("<gray>Fisch-Abgaben: <white>" + requirement.fish().size() + "</white></gray>");
        if (!requirement.skills().isEmpty())
            lore.add("<gray>Angler-Skillziele: <white>" + requirement.skills().size() + "</white></gray>");
        lore.add("");
        lore.add(progress.level() >= pending ? "<yellow>Jetzt abschließbar, sobald alles erfüllt ist.</yellow>"
                : "<aqua>Vorauszahlung/Fortschritt bereits möglich.</aqua>");
        lore.add("<aqua>Offene Ziele im Chat: <white>/abgabe</white></aqua>");
        lore.add("<yellow>Klicken: Details öffnen</yellow>");
        return items.item(Material.CHEST, "<gold>Nächste Abgabe • Level " + pending + "</gold>", lore);
    }

    private ItemStack rewardsOverviewItem(int available) {
        return items.item(Material.CHEST_MINECART, "<aqua>Levelbelohnungen</aqua>",
                List.of("<gray>Abholbereit: <yellow>" + available + "</yellow></gray>", "",
                        "<gray>3 / 4 / 6 / 12 Werkzeugfragmente</gray>",
                        "<gray>plus Coins und Booster.</gray>", "<yellow>Klicken: Belohnungen öffnen</yellow>"));
    }

    private ItemStack milestoneItem(String professionId, ProfessionProgress progress, int milestone, int availableMilestone) {
        boolean completed = progress.completedMilestone() >= milestone;
        boolean available = milestone == availableMilestone;
        boolean reached = progress.level() >= milestone;
        MilestoneRequirement requirement = config.requirement(professionId, progress.prestige(), milestone);
        Material material = completed ? Material.LIME_STAINED_GLASS_PANE
                : available && reached ? Material.YELLOW_STAINED_GLASS_PANE
                : available ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        List<String> lore = new ArrayList<>();
        lore.add(completed ? "<green>✓ Abgeschlossen</green>" : available && reached ? "<yellow>⚠ Abgabe offen</yellow>"
                : available ? "<aqua>↗ Vorauszahlung möglich</aqua>" : "<red>✗ Vorherige Abgabe zuerst</red>");
        if (available && !reached) lore.add("<gray>Abschluss erst ab Level " + milestone + ".</gray>");
        lore.add("<gray>Materialgruppen: <white>" + requirement.materials().size() + "</white></gray>");
        if (!requirement.fish().isEmpty()) lore.add("<gray>Fische: <white>" + requirement.fish().size() + " Arten</white></gray>");
        if (!requirement.mined().isEmpty()) lore.add("<gray>Selbstabbau: <white>" + requirement.mined().size() + " Aufgaben</white></gray>");
        if (!requirement.growths().isEmpty()) lore.add("<gray>Aufforstung: <white>" + requirement.growths().size() + " Arten</white></gray>");
        if (!requirement.hunts().isEmpty()) lore.add("<gray>Jagdauftrag: <white>" + requirement.hunts().size() + " Ziele</white></gray>");
        if (!requirement.skills().isEmpty()) lore.add("<gray>Angler-Skills: <white>" + requirement.skills().size() + " Ziele</white></gray>");
        if (requirement.coinCost() > 0L) lore.add("<gray>Coins beim Abschluss: <gold>" + MenuFormat.integer(requirement.coinCost()) + "</gold></gray>");
        if (available) { lore.add(""); lore.add("<yellow>Klicken: Abgabe öffnen</yellow>"); }
        return items.item(material, "<yellow>Meilenstein Level " + milestone + "</yellow>", lore);
    }

    private List<Integer> expandedRequirementSlots(List<Integer> configured, int size, int requiredCount) {
        List<Integer> result = new ArrayList<>(configured);
        if (result.size() >= requiredCount) return result;
        int maximum = Math.min(size, 36);
        for (int slot = 9; slot < maximum && result.size() < requiredCount; slot++) if (!result.contains(slot)) result.add(slot);
        return result;
    }

    private ItemStack rewardItem(LevelReward reward, int prestige, boolean unlocked, boolean claimed) {
        Material material = claimed ? Material.LIME_DYE : unlocked ? Material.CHEST_MINECART : Material.GRAY_DYE;
        List<String> lore = new ArrayList<>();
        long coins = config.scaledRewardCoins(reward, prestige);
        if (coins > 0L) lore.add("<gray>Coins: <gold>" + MenuFormat.integer(coins) + "</gold></gray>");
        if (reward.lumis() > 0L) lore.add("<gray>Lumis: <light_purple>" + MenuFormat.integer(reward.lumis()) + "</light_purple></gray>");
        reward.customItems().forEach((id, amount) -> lore.add("<gray>" + amount + "× <aqua>" + readableItem(id) + "</aqua></gray>"));
        lore.add("");
        lore.add(claimed ? "<green>✓ Bereits abgeholt</green>" : unlocked ? "<yellow>Klicken zum Abholen</yellow>"
                : "<dark_gray>Freigeschaltet bei Level " + reward.level() + "</dark_gray>");
        return items.item(material, (claimed ? "<green>" : unlocked ? "<aqua>" : "<gray>")
                + "Belohnung • Level " + reward.level() + (claimed ? "</green>" : unlocked ? "</aqua>" : "</gray>"), lore);
    }

    private ItemStack prestigeItem(Player player, String professionId, ProfessionProgress progress) {
        if (progress.prestige() >= config.maxPrestige()) {
            return items.item(Material.NETHER_STAR, "<gold>Prestige V erreicht</gold>",
                    List.of("<gray>Du kannst weiterhin bis Level " + config.maxLevel() + " leveln.</gray>"));
        }
        ProfessionManager.PrestigeToolStatus toolStatus = manager.prestigeToolStatus(player, professionId);
        boolean ready = manager.canPerformPrestige(player, professionId);
        String toolLine;
        if (ProfessionManager.ANGLER.equals(professionId)) {
            toolLine = "<gray>Fanglager: <aqua>mehr Platz durch Prestige</aqua></gray>";
        } else if (ProfessionManager.MINER.equals(professionId) || ProfessionManager.HUNTER.equals(professionId)) {
            toolLine = toolStatus == ProfessionManager.PrestigeToolStatus.NO_SPACE
                    ? "<gray>Freier Inventarplatz: <red>fehlt ✗</red></gray>"
                    : "<gray>Neue Prestige-Belohnung: <green>Inventarplatz vorhanden ✓</green></gray>";
        } else {
            toolLine = switch (toolStatus) {
                case READY -> "<gray>Berufsaxt: <green>vorhanden und vollständig repariert ✓</green></gray>";
                case MISSING -> "<gray>Berufsaxt: <red>nicht im Inventar ✗</red></gray>";
                case DAMAGED -> "<gray>Berufsaxt: <red>noch beschädigt ✗</red></gray>";
                case NO_SPACE -> "<gray>Freier Inventarplatz: <red>fehlt ✗</red></gray>";
                case NOT_REQUIRED -> "<gray>Freier Inventarplatz: <green>vorhanden ✓</green></gray>";
            };
        }
        return items.item(ready ? Material.NETHER_STAR : Material.GRAY_DYE,
                "<gold>Nächstes Prestige: " + roman(progress.prestige() + 1) + "</gold>",
                List.of("<gray>Level 100: " + check(progress.level() >= config.maxLevel()) + "</gray>",
                        "<gray>Abgabe Level 100: " + check(progress.completedMilestone() >= config.maxLevel()) + "</gray>",
                        toolLine, "", ready ? "<yellow>Klicken zum Aufsteigen</yellow>" : "<dark_gray>Noch nicht bereit</dark_gray>"));
    }

    private ItemStack replacementItem(ProfessionProgress progress) {
        if (progress.prestige() <= 0) return items.item(Material.ANVIL, "<gray>Ersatzwerkzeug</gray>", List.of("<dark_gray>Ab Prestige I verfügbar.</dark_gray>"));
        return items.item(Material.ANVIL, "<gold>Ersatzwerkzeug kaufen</gold>",
                List.of("<gray>Aktuelle Stufe: <yellow>Holzschlag " + roman(progress.prestige()) + "</yellow></gray>",
                        "<gray>Preis: <gold>" + MenuFormat.integer(config.replacementPrice(progress.prestige())) + " Coins</gold></gray>", "",
                        "<red>Das bisherige Werkzeug wird dauerhaft deaktiviert.</red>", "<yellow>Klicken für Warnung und Bestätigung</yellow>"));
    }

    private ItemStack hunterRewardInfo(ProfessionProgress progress) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Freigeschaltet: <yellow>" + progress.prestige() + "/" + config.maxPrestige() + "</yellow></gray>");
        lore.add("");
        lore.add("<gray>I  • Jäger-Schwert</gray>");
        lore.add("<gray>II • Jäger-Speer</gray>");
        lore.add("<gray>III • Jäger-Bogen</gray>");
        lore.add("<gray>IV • Jäger-Mace</gray>");
        lore.add("<gray>V  • Jäger-Brustplatte</gray>");
        lore.add("");
        lore.add("<gray>Ersatz pro Stufe: <gold>100.000 Coins</gold></gray>");
        lore.add("");
        lore.add("<yellow>Klicken: Arsenal & Ersatzbelohnungen</yellow>");
        return items.item(Material.ARMOR_STAND, "<red>Jäger-Arsenal</red>", lore);
    }

    private ItemStack hunterReplacementItem(ProfessionProgress progress, int prestige) {
        boolean unlocked = prestige <= progress.prestige();
        Material material = switch (prestige) {
            case 1 -> Material.DIAMOND_SWORD;
            case 2 -> MaterialResolver.resolve("DIAMOND_SPEAR", Material.TRIDENT);
            case 3 -> Material.BOW;
            case 4 -> Material.MACE;
            default -> Material.NETHERITE_CHESTPLATE;
        };
        if (!unlocked) return items.item(Material.GRAY_DYE, "<dark_gray>" + hunterRewardName(prestige) + "</dark_gray>",
                List.of("<gray>Freischaltung: Prestige " + roman(prestige) + "</gray>", "", "<red>Noch nicht freigeschaltet.</red>"));
        long price = config.replacementPrice(ProfessionManager.HUNTER, prestige);
        return items.item(material, "<red>" + hunterRewardName(prestige) + "</red>",
                List.of("<gray>Freigeschaltet bei Prestige <yellow>" + roman(prestige) + "</yellow></gray>",
                        "<gray>Ersatzpreis: <gold>" + MenuFormat.integer(price) + " Coins</gold></gray>", "",
                        "<red>Ein Ersatz deaktiviert das bisherige Belohnungsitem.</red>",
                        "<yellow>Klicken: Ersatz kaufen</yellow>"));
    }

    private String hunterRewardName(int prestige) {
        return switch (prestige) {
            case 1 -> "Trophäenklinge";
            case 2 -> "Hetzjägerspeer";
            case 3 -> "Jagdbogen";
            case 4 -> "Großwild-Mace";
            case 5 -> "Meisterbrustplatte";
            default -> "Jäger-Belohnung";
        };
    }

    private ItemStack minerToolInfo(ProfessionProgress progress) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Freigeschaltet: <yellow>" + progress.prestige() + "/" + config.maxPrestige() + "</yellow></gray>");
        lore.add("<gray>Alle Prestige-Werkzeuge dürfen Mending erhalten.</gray>");
        lore.add("");
        lore.add("<yellow>Klicken: Sammlung & Ersatzwerkzeuge</yellow>");
        return items.item(Material.SMITHING_TABLE, "<aqua>Bergarbeiter-Werkzeugsammlung</aqua>", lore);
    }

    private ItemStack minerReplacementItem(ProfessionProgress progress, int prestige) {
        boolean unlocked = prestige <= progress.prestige();
        Material material = switch (prestige) {
            case 1, 2 -> Material.IRON_PICKAXE;
            case 3, 4 -> Material.DIAMOND_PICKAXE;
            default -> Material.NETHERITE_PICKAXE;
        };
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Freischaltung: Prestige <yellow>" + roman(prestige) + "</yellow></gray>");
        if (!unlocked) {
            lore.add("");
            lore.add("<red>Noch nicht freigeschaltet.</red>");
            return items.item(Material.GRAY_DYE, "<dark_gray>" + minerToolName(prestige) + "</dark_gray>", lore);
        }
        lore.add("<gray>Ersatzpreis: <gold>" + MenuFormat.integer(
                config.replacementPrice(ProfessionManager.MINER, prestige)) + " Coins</gold></gray>");
        lore.add("<green>✓ Mending erlaubt</green>");
        lore.add("");
        lore.add("<yellow>Klicken: Ersatz kaufen</yellow>");
        return items.item(material, "<aqua>" + minerToolName(prestige) + "</aqua>", lore);
    }

    private String minerToolName(int prestige) {
        return switch (prestige) {
            case 1 -> "Schmelzer-Eisenspitzhacke";
            case 2 -> "Veinminer-Eisenspitzhacke";
            case 3 -> "3×3-Diamantspitzhacke";
            case 4 -> "Veinminer + Schmelzer";
            case 5 -> "TNT-Netheritespitzhacke";
            default -> "Bergarbeiter-Werkzeug";
        };
    }

    private ItemStack boosterStatus(Player player) {
        ActiveBooster profession = manager.boosters().snapshot(player.getUniqueId()).get(BoosterCategory.PROFESSION);
        return items.item(Material.CLOCK, "<aqua>Berufe-Booster</aqua>",
                List.of(profession == null ? "<gray>Aktuell: <dark_gray>kein Berufe-Booster aktiv</dark_gray></gray>"
                                : "<gray>Aktuell: <green>×" + formatMultiplier(profession.multiplier()) + "</green> • "
                                + MenuFormat.durationSeconds(profession.remainingSeconds()) + "</gray>",
                        "", "<dark_gray>Berufe-Booster laufen nur während Onlinezeit.</dark_gray>"));
    }

    private LevelNumbers levelNumbers(String professionId, ProfessionProgress progress) {
        double previous = config.xpThreshold(professionId, progress.prestige(), progress.level());
        double next = progress.level() >= config.maxLevel()
                ? config.totalXp(professionId, progress.prestige())
                : config.xpThreshold(professionId, progress.prestige(), progress.level() + 1);
        long current = Math.max(0L, Math.round(progress.xp() - previous));
        long needed = Math.max(1L, Math.round(next - previous));
        if (progress.level() >= config.maxLevel()) current = needed;
        return new LevelNumbers(current, needed);
    }

    private String professionAt(PlayerProfessionState state, int index) {
        return index >= 0 && index < state.activeProfessions().size() ? state.activeProfessions().get(index) : null;
    }

    private boolean isImplemented(String professionId) {
        return ProfessionManager.LUMBERJACK.equalsIgnoreCase(professionId)
                || ProfessionManager.MINER.equalsIgnoreCase(professionId)
                || ProfessionManager.HUNTER.equalsIgnoreCase(professionId)
                || ProfessionManager.ANGLER.equalsIgnoreCase(professionId);
    }

    private Material professionIcon(String id) {
        String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
        Material fallback = switch (normalized) {
            case ProfessionManager.LUMBERJACK -> Material.IRON_AXE;
            case ProfessionManager.MINER -> Material.IRON_PICKAXE;
            case ProfessionManager.HUNTER -> Material.DIAMOND_SWORD;
            case ProfessionManager.ANGLER -> Material.FISHING_ROD;
            case FARMER -> Material.WHEAT;
            default -> Material.PAPER;
        };
        if (!isImplemented(normalized)) return fallback;
        return MaterialResolver.resolve(config.string("professions." + normalized + ".icon", fallback.name()), fallback);
    }

    private String professionName(String id) {
        return switch (id == null ? "" : id.toLowerCase(Locale.ROOT)) {
            case ProfessionManager.LUMBERJACK -> "Holzfäller";
            case ProfessionManager.MINER -> "Bergarbeiter";
            case ProfessionManager.HUNTER -> "Jäger";
            case ProfessionManager.ANGLER -> "Angler";
            case FARMER -> "Farmer";
            default -> id;
        };
    }

    private String menuId(String professionId) {
        if (ProfessionManager.MINER.equals(professionId)) return "miner";
        if (ProfessionManager.HUNTER.equals(professionId)) return "hunter";
        if (ProfessionManager.ANGLER.equals(professionId)) return "angler";
        return "lumberjack";
    }

    private int slotAngler(String path, int size, int fallback) {
        int value = plugin.configs().angler().getInt(path, fallback);
        return value >= 0 && value < size ? value : fallback;
    }

    private String readableItem(String id) {
        return switch (id) {
            case "werkzeugfragment" -> "Werkzeugfragment";
            case "berufe_booster_15" -> "1,5× Berufe-Booster";
            case "berufe_booster_20" -> "2× Berufe-Booster";
            case "lumi_booster_15" -> "1,5× Lumi-Booster";
            case "lumi_booster_20" -> "2× Lumi-Booster";
            default -> {
                String[] parts = id.replace('-', '_').split("_");
                StringBuilder result = new StringBuilder();
                for (String part : parts) {
                    if (part.isBlank()) continue;
                    if (!result.isEmpty()) result.append(' ');
                    result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                }
                yield result.toString();
            }
        };
    }

    private String progressLine(long current, long required) {
        return "<gray>Fortschritt: <white>" + MenuFormat.integer(current) + "/" + MenuFormat.integer(required) + "</white></gray>";
    }

    private String progressBarLine(long current, long required, int segments) {
        double ratio = required <= 0L ? 1D : Math.min(1D, current / (double) required);
        int filled = (int) Math.round(ratio * segments);
        return "<green>" + "▰".repeat(Math.max(0, filled)) + "</green><dark_gray>"
                + "▱".repeat(Math.max(0, segments - filled)) + "</dark_gray> <gray>" + Math.round(ratio * 100D) + "%</gray>";
    }

    private String statusWord(boolean complete) {
        return complete ? "<green>vollständig</green>" : "<red>unvollständig</red>";
    }

    private String check(boolean complete) { return complete ? "<green>✓</green>" : "<red>✗</red>"; }
    private int rows(String path, int fallback) { return Math.max(1, Math.min(6, configInt(path, fallback))); }
    private int slot(String path, int size, int fallback) { return SlotLayout.valid(configInt(path, fallback), size, fallback); }
    private Gui withSounds(Gui gui) {
        return gui.sounds(
                config.string("gui-sounds.professions.open", ""),
                config.string("gui-sounds.professions.click", "minecraft:ui.button.click"),
                (float) plugin.configs().professions().getDouble("gui-sounds.professions.volume", 0.55D),
                (float) plugin.configs().professions().getDouble("gui-sounds.professions.open-pitch", 1.15D),
                (float) plugin.configs().professions().getDouble("gui-sounds.professions.click-pitch", 1.2D));
    }

    private int configInt(String path, int fallback) { return plugin.configs().professions().getInt(path, fallback); }
    private String stars(int prestige) { return prestige <= 0 ? "Keine" : "⭐".repeat(Math.min(config.maxPrestige(), prestige)); }
    private String roman(int value) {
        return switch (value) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> Integer.toString(value); };
    }
    private String formatMultiplier(double multiplier) {
        if (Math.abs(multiplier - Math.rint(multiplier)) < 0.0001D) return Integer.toString((int) Math.rint(multiplier));
        return String.format(Locale.GERMANY, "%.1f", multiplier);
    }

    private record LevelNumbers(long current, long needed) { }
}
