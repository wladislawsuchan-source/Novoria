package de.walahi.novosmp.vote;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.gui.ItemBuilder;
import de.walahi.smpcore.gui.MiniMessageItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Vote menu with configurable server-list links and persistent progress overview. */
public final class VoteMenu implements Listener {
    private static final int SIZE = 54;
    private static final int[] SITE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int TOTAL_SLOT = 29;
    private static final int MILESTONE_SLOT = 31;
    private static final int REWARD_SLOT = 33;
    private static final int CLOSE_SLOT = 49;

    private final NovoSMPPlugin plugin;
    private final VoteManager manager;
    private final MiniMessageItems items = new MiniMessageItems();

    public VoteMenu(NovoSMPPlugin plugin, VoteManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void open(Player player) {
        manager.claimPendingRewards(player);

        VoteManager.VoteProgress progress = manager.progress(player.getUniqueId());
        VoteManager.VoteRewardOverview rewards = manager.rewardOverview();
        List<VoteSite> sites = loadSites();
        Set<String> completedSites = completedSites(player, sites);
        int votedToday = completedSites.size();
        FileConfiguration config = voteConfig();

        String title = config.getString("menu.title", "<dark_gray>Votes</dark_gray>");
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, items.component(title));
        holder.inventory = inventory;
        fill(inventory);

        for (int index = 0; index < sites.size() && index < SITE_SLOTS.length; index++) {
            VoteSite site = sites.get(index);
            int slot = SITE_SLOTS[index];
            boolean completed = completedSites.contains(site.id());
            if (!completed) holder.sitesBySlot.put(slot, site);
            String statePath = completed ? "menu.site-button.completed" : "menu.site-button.pending";
            String siteNameTemplate = config.getString(statePath + ".name",
                    completed ? "<gray><bold>%site%</bold></gray>" : "<light_purple><bold>%site%</bold></light_purple>");
            List<String> siteLoreTemplate = configuredList(statePath + ".lore", completed
                    ? List.of("", "<green>✔ Heute erfolgreich gevotet</green>", "<dark_gray>Morgen wieder verfügbar.</dark_gray>")
                    : List.of("", "<yellow>Heute noch offen</yellow>", "<gray>Klicke für den Link.</gray>"));
            Material siteMaterial = configuredMaterial(statePath + ".material", Material.PAPER);
            Map<String, String> placeholders = Map.of(
                    "%site%", escape(site.name()),
                    "%site_id%", escape(site.id())
            );
            inventory.setItem(slot, items.item(
                    siteMaterial,
                    replace(siteNameTemplate, placeholders),
                    replace(siteLoreTemplate, placeholders)
            ));
        }

        Map<String, String> totalPlaceholders = new LinkedHashMap<>();
        totalPlaceholders.put("%total_votes%", format(progress.totalVotes()));
        totalPlaceholders.put("%today_votes%", String.valueOf(votedToday));
        totalPlaceholders.put("%site_count%", String.valueOf(sites.size()));
        inventory.setItem(TOTAL_SLOT, items.item(
                configuredMaterial("menu.total.material", Material.BOOK),
                replace(config.getString("menu.total.name", "<light_purple><bold>Deine Votes</bold></light_purple>"), totalPlaceholders),
                replace(configuredList("menu.total.lore", List.of(
                        "",
                        "<gray>Votes insgesamt:</gray> <white>%total_votes%</white>",
                        "<gray>Heute erledigt:</gray> <white>%today_votes% / %site_count%</white>",
                        "",
                        "<dark_gray>Jeder Vote unterstützt Novoria.</dark_gray>"
                )), totalPlaceholders)
        ));

        Map<String, String> milestonePlaceholders = milestonePlaceholders(progress);
        List<String> milestoneLore = replace(configuredList("menu.milestone.lore", List.of(
                "",
                "<gray>Alle <white>%interval% Votes</white> erhältst du:</gray>",
                "<dark_gray>• <light_purple>%milestone_key_amount%x %milestone_key_name%</light_purple>",
                "",
                "%progress_bar%",
                "<gray>Fortschritt:</gray> <white>%progress_votes% / %interval%</white>",
                "<gray>Nächster Novo-Key bei:</gray> <light_purple>%next_milestone% Votes</light_purple>",
                "<gray>Noch benötigt:</gray> <yellow>%votes_until_next% Votes</yellow>"
        )), milestonePlaceholders);
        if (progress.milestonesPending() > 0) {
            milestoneLore.addAll(replace(configuredList("menu.milestone.pending-lore", List.of(
                    "",
                    "<yellow><bold>Ausstehend:</bold> %milestones_pending% Meilenstein(e)</yellow>",
                    "<dark_gray>Schaffe Inventarplatz und öffne /vote erneut.</dark_gray>"
            )), milestonePlaceholders));
        }
        boolean milestoneGlint = config.getBoolean("menu.milestone.glint-when-pending", true)
                && progress.milestonesPending() > 0;
        int glintLastVotes = Math.max(0, config.getInt("menu.milestone.glint-last-votes", 5));
        if (glintLastVotes > 0 && progress.votesUntilNext() <= glintLastVotes) milestoneGlint = true;
        ItemStack milestoneItem = ItemBuilder.of(configuredMaterial("menu.milestone.material", Material.TRIPWIRE_HOOK))
                .name(items.component(replace(config.getString("menu.milestone.name",
                        "<light_purple><bold>Novo-Key Meilenstein</bold></light_purple>"), milestonePlaceholders)))
                .lore(items.lore(milestoneLore))
                .glint(milestoneGlint)
                .build();
        inventory.setItem(MILESTONE_SLOT, milestoneItem);

        Map<String, String> rewardPlaceholders = new LinkedHashMap<>(milestonePlaceholders);
        rewardPlaceholders.put("%coins%", format(rewards.coins()));
        rewardPlaceholders.put("%key_amount%", String.valueOf(rewards.keyAmount()));
        rewardPlaceholders.put("%key_name%", escape(rewards.keyDisplayName()));
        List<String> rewardLore = new ArrayList<>(replace(configuredList("menu.rewards.lore-before", List.of(
                "",
                "<gray>Pro gültigem Vote:</gray>"
        )), rewardPlaceholders));
        if (rewards.coins() > 0) {
            rewardLore.add(replace(config.getString("menu.rewards.coins-line",
                    "<dark_gray>• <gold>%coins% Coins</gold>"), rewardPlaceholders));
        }
        if (rewards.keyAmount() > 0) {
            rewardLore.add(replace(config.getString("menu.rewards.key-line",
                    "<dark_gray>• <yellow>%key_amount%x %key_name%</yellow>"), rewardPlaceholders));
        }
        if (rewards.coins() <= 0 && rewards.keyAmount() <= 0) {
            rewardLore.add(replace(config.getString("menu.rewards.no-direct-reward-line",
                    "<dark_gray>• <gray>Keine direkte Belohnung</gray>"), rewardPlaceholders));
        }
        rewardLore.addAll(replace(configuredList("menu.rewards.lore-after", List.of(
                "",
                "<gray>Alle <white>%interval% Votes</white>:</gray>",
                "<dark_gray>• <light_purple>%milestone_key_amount%x %milestone_key_name%</light_purple>"
        )), rewardPlaceholders));
        inventory.setItem(REWARD_SLOT, items.item(
                configuredMaterial("menu.rewards.material", Material.EMERALD),
                replace(config.getString("menu.rewards.name", "<gold><bold>Vote-Belohnungen</bold></gold>"), rewardPlaceholders),
                rewardLore
        ));

        inventory.setItem(CLOSE_SLOT, items.item(
                configuredMaterial("menu.close.material", Material.BARRIER),
                config.getString("menu.close.name", "<red><bold>Schließen</bold></red>"),
                configuredList("menu.close.lore", List.of())
        ));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) return;

        if (rawSlot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }

        VoteSite site = holder.sitesBySlot.get(rawSlot);
        if (site == null) return;

        // Keep the menu open. Players can click all seven papers once, close the GUI afterwards
        // and then use the collected one-line links from chat without entering /vote seven times.
        if (!holder.linksSent.add(site.id())) return;
        if (!sendVoteLink(player, site)) holder.linksSent.remove(site.id());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize)) event.setCancelled(true);
    }

    /** Sends exactly one chat line. Only the configured link component is clickable. */
    private boolean sendVoteLink(Player player, VoteSite site) {
        String resolvedUrl = resolveVoteUrl(site.url(), player.getName());
        URI uri = validHttpUri(resolvedUrl);
        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("%site%", escape(site.name()));
        placeholders.put("%site_id%", escape(site.id()));
        placeholders.put("%url%", escape(resolvedUrl));
        placeholders.put("%player%", escape(player.getName()));

        if (uri == null) {
            String invalid = voteConfig().getString("messages.invalid-link",
                    "<light_purple><bold>[VOTE]</bold></light_purple> <red>Für %site% ist kein gültiger Vote-Link konfiguriert.</red>");
            player.sendMessage(items.component(replace(invalid, placeholders)));
            return false;
        }

        FileConfiguration config = voteConfig();
        String prefixText = config.getString("messages.vote-link.prefix",
                "<light_purple><bold>[VOTE]</bold></light_purple> ");
        String clickableText = config.getString("messages.vote-link.clickable-text",
                "<aqua><underlined>%url%</underlined></aqua>");
        String hoverText = config.getString("messages.vote-link.hover",
                "<gray>Klicken, um auf %site% zu voten.</gray>");

        Component prefix = items.component(replace(prefixText, placeholders));
        Component link = items.component(replace(clickableText, placeholders))
                .clickEvent(ClickEvent.openUrl(uri.toString()))
                .hoverEvent(HoverEvent.showText(items.component(replace(hoverText, placeholders))));
        player.sendMessage(prefix.append(link));
        return true;
    }

    private Set<String> completedSites(Player player, List<VoteSite> sites) {
        if (sites.isEmpty()) return Set.of();
        ZoneId zone = configuredZone();
        long since = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        List<String> receivedServices = manager.serviceNamesSince(player.getUniqueId(), since);
        Set<String> completed = new HashSet<>();
        for (VoteSite site : sites) {
            if (receivedServices.stream().anyMatch(site::matchesService)) completed.add(site.id());
        }
        return completed;
    }

    private ZoneId configuredZone() {
        String value = voteConfig().getString("menu.time-zone", "Europe/Berlin");
        try {
            return ZoneId.of(value == null || value.isBlank() ? "Europe/Berlin" : value.trim());
        } catch (RuntimeException ignored) {
            return ZoneId.of("Europe/Berlin");
        }
    }

    private List<VoteSite> loadSites() {
        ConfigurationSection root = voteConfig().getConfigurationSection("sites");
        if (root == null) return List.of();
        List<VoteSite> result = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            if (result.size() >= SITE_SLOTS.length) break;
            ConfigurationSection site = root.getConfigurationSection(id);
            if (site == null || !site.getBoolean("enabled", true)) continue;
            String name = site.getString("name", id);
            String url = site.getString("url", "");
            List<String> aliases = new ArrayList<>(site.getStringList("service-names"));
            if (aliases.isEmpty()) aliases.add(name);
            result.add(new VoteSite(id, name == null ? id : name, url == null ? "" : url, aliases));
        }
        return result;
    }

    private String resolveVoteUrl(String template, String playerName) {
        if (template == null || template.isBlank()) return "";
        String encodedName = URLEncoder.encode(playerName == null ? "" : playerName, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return template.replace("%player%", encodedName);
    }

    private URI validHttpUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) return null;
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return null;
            return uri;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private void fill(Inventory inventory) {
        ItemStack filler = items.item(
                configuredMaterial("menu.filler.material", Material.GRAY_STAINED_GLASS_PANE),
                voteConfig().getString("menu.filler.name", " "),
                configuredList("menu.filler.lore", List.of())
        );
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler.clone());
    }

    private Map<String, String> milestonePlaceholders(VoteManager.VoteProgress progress) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("%interval%", format(progress.milestoneInterval()));
        values.put("%milestone_key_amount%", String.valueOf(progress.milestoneKeyAmount()));
        values.put("%milestone_key_name%", escape(progress.milestoneKeyDisplayName()));
        values.put("%progress_votes%", format(progress.progressVotes()));
        values.put("%next_milestone%", format(progress.nextMilestoneAt()));
        values.put("%votes_until_next%", format(progress.votesUntilNext()));
        values.put("%milestones_pending%", format(progress.milestonesPending()));
        values.put("%total_votes%", format(progress.totalVotes()));
        values.put("%progress_bar%", progressBar(progress.progressVotes(), progress.milestoneInterval()));
        return values;
    }

    private String progressBar(long progress, long target) {
        FileConfiguration config = voteConfig();
        int length = Math.max(1, Math.min(50, config.getInt("menu.milestone.progress-bar.length", 14)));
        long safeTarget = Math.max(1L, target);
        int filled = (int) Math.min(length, Math.max(0L, progress) * length / safeTarget);
        String filledPart = config.getString("menu.milestone.progress-bar.filled", "<light_purple>■</light_purple>");
        String emptyPart = config.getString("menu.milestone.progress-bar.empty", "<dark_gray>■</dark_gray>");
        return filledPart.repeat(filled) + emptyPart.repeat(length - filled);
    }

    private FileConfiguration voteConfig() {
        return plugin.configs().vote();
    }

    private Material configuredMaterial(String path, Material fallback) {
        String raw = voteConfig().getString(path);
        if (raw == null || raw.isBlank()) return fallback;
        Material material = Material.matchMaterial(raw.trim());
        return material == null ? fallback : material;
    }

    private List<String> configuredList(String path, List<String> fallback) {
        if (!voteConfig().isList(path)) return fallback;
        return voteConfig().getStringList(path);
    }

    private String escape(String value) {
        return items.miniMessage().escapeTags(value == null ? "" : value);
    }

    private static String replace(String input, Map<String, String> placeholders) {
        String value = input == null ? "" : input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return value;
    }

    private static List<String> replace(List<String> lines, Map<String, String> placeholders) {
        if (lines == null || lines.isEmpty()) return new ArrayList<>();
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) result.add(replace(line, placeholders));
        return result;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String format(long amount) {
        return String.format(Locale.GERMANY, "%,d", amount);
    }

    private record VoteSite(String id, String name, String url, List<String> serviceNames) {
        boolean matchesService(String serviceName) {
            String actual = normalize(serviceName);
            if (actual.isEmpty()) return false;
            if (actual.equals(normalize(id)) || actual.equals(normalize(name))) return true;
            for (String alias : serviceNames) {
                String expected = normalize(alias);
                if (!expected.isEmpty() && (actual.equals(expected) || actual.contains(expected) || expected.contains(actual))) return true;
            }
            return false;
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, VoteSite> sitesBySlot = new LinkedHashMap<>();
        private final java.util.Set<String> linksSent = new java.util.HashSet<>();
        @Override public Inventory getInventory() { return inventory; }
    }
}
