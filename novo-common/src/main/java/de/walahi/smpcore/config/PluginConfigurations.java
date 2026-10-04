package de.walahi.smpcore.config;

import de.walahi.smpcore.network.ServerType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typed access to every independent configuration file.
 *
 * <p>No files are merged. A setting therefore has exactly one owner and two files can safely
 * use the same path without silently overwriting one another.</p>
 */
public final class PluginConfigurations {
    private final JavaPlugin plugin;
    private final Map<String, ConfigurationFile> files = new LinkedHashMap<>();
    private final Set<String> reportedAmbiguousPaths = ConcurrentHashMap.newKeySet();

    public PluginConfigurations(JavaPlugin plugin,
                                ConfigurationLoader loader,
                                File globalFolder,
                                File serverFolder,
                                String localResourceName,
                                ServerType serverType) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(globalFolder, "globalFolder");
        Objects.requireNonNull(serverFolder, "serverFolder");
        Objects.requireNonNull(localResourceName, "localResourceName");
        Objects.requireNonNull(serverType, "serverType");

        register("server", plugin, loader, new File(serverFolder, "config.yml"), localResourceName);

        register("main", plugin, loader, new File(globalFolder, "config.yml"), "config.yml");
        register("commands", plugin, loader, new File(globalFolder, "commands.yml"), "commands.yml");
        register("ranks", plugin, loader, new File(globalFolder, "ranks.yml"), "ranks.yml");
        register("messages", plugin, loader, new File(globalFolder, "messages.yml"), "messages.yml");
        register("sounds", plugin, loader, new File(globalFolder, "sounds.yml"), "sounds.yml");

        register("storage", plugin, loader, new File(serverFolder, "storage.yml"), "storage.yml");
        register("menus", plugin, loader, new File(serverFolder, "menus.yml"), "menus.yml");
        register("scoreboards", plugin, loader, new File(serverFolder, "scoreboards.yml"), "scoreboards.yml");

        if (serverType == ServerType.SMP) {
            register("prices", plugin, loader, new File(serverFolder, "prices.yml"), "prices.yml");
            register("vote", plugin, loader, new File(serverFolder, "vote.yml"), "vote.yml");
            register("daily", plugin, loader, new File(serverFolder, "daily.yml"), "daily.yml");
            register("chat-colors", plugin, loader, new File(serverFolder, "chat-colors.yml"), "chat-colors.yml");
            register("playtime", plugin, loader, new File(serverFolder, "playtime.yml"), "playtime.yml");
            register("trade", plugin, loader, new File(serverFolder, "trade.yml"), "trade.yml");
            register("orders", plugin, loader, new File(serverFolder, "orders.yml"), "orders.yml");
            register("auction-house", plugin, loader, new File(serverFolder, "auctionhouse.yml"), "auctionhouse.yml");
            register("friends", plugin, loader, new File(serverFolder, "friends.yml"), "friends.yml");
            register("duels", plugin, loader, new File(serverFolder, "duels.yml"), "duels.yml");
            register("kits", plugin, loader, new File(serverFolder, "kits.yml"), "kits.yml");
            register("chat-events", plugin, loader, new File(serverFolder, "chat-events.yml"), "chat-events.yml");
            register("items", plugin, loader, new File(serverFolder, "items.yml"), "items.yml");
            register("holzschlag", plugin, loader, new File(serverFolder, "holzschlag.yml"), "holzschlag.yml");
            register("mining-enchants", plugin, loader, new File(serverFolder, "mining-enchants.yml"), "mining-enchants.yml");
            register("custom-enchants", plugin, loader, new File(serverFolder, "custom-enchants.yml"), "custom-enchants.yml");
            register("lumi-shop", plugin, loader, new File(serverFolder, "lumi-shop.yml"), "lumi-shop.yml");
            register("custom-item-shop", plugin, loader, new File(serverFolder, "custom-item-shop.yml"), "custom-item-shop.yml");
            register("professions", plugin, loader, new File(serverFolder, "professions.yml"), "professions.yml");
            register("angler", plugin, loader, new File(serverFolder, "angler.yml"), "angler.yml");
            register("rules", plugin, loader, new File(serverFolder, "rules.yml"), "rules.yml");
            register("jumpnrun", plugin, loader, new File(serverFolder, "jumpnrun.yml"), "jumpnrun.yml");
            register("quests", plugin, loader, new File(serverFolder, "quests.yml"), "quests.yml");
        }
    }

    public FileConfiguration server() { return file("server").configuration(); }
    public FileConfiguration main() { return file("main").configuration(); }
    public FileConfiguration commands() { return file("commands").configuration(); }
    public FileConfiguration ranks() { return file("ranks").configuration(); }
    public FileConfiguration messages() { return file("messages").configuration(); }
    public FileConfiguration sounds() { return file("sounds").configuration(); }
    public FileConfiguration storage() { return file("storage").configuration(); }
    public FileConfiguration menus() { return file("menus").configuration(); }
    public FileConfiguration scoreboards() { return file("scoreboards").configuration(); }
    public FileConfiguration prices() { return file("prices").configuration(); }
    public FileConfiguration vote() { return file("vote").configuration(); }
    public FileConfiguration daily() { return file("daily").configuration(); }
    public FileConfiguration chatColors() { return file("chat-colors").configuration(); }
    public FileConfiguration playtime() { return file("playtime").configuration(); }
    public FileConfiguration trade() { return file("trade").configuration(); }
    public FileConfiguration orders() { return file("orders").configuration(); }
    public FileConfiguration auctionHouse() { return file("auction-house").configuration(); }
    public FileConfiguration friends() { return file("friends").configuration(); }
    public FileConfiguration duels() { return file("duels").configuration(); }
    public FileConfiguration kits() { return file("kits").configuration(); }
    public FileConfiguration chatEvents() { return file("chat-events").configuration(); }
    public FileConfiguration items() { return file("items").configuration(); }
    public FileConfiguration holzschlag() { return file("holzschlag").configuration(); }
    public FileConfiguration miningEnchants() { return file("mining-enchants").configuration(); }
    public FileConfiguration customEnchants() { return file("custom-enchants").configuration(); }
    public FileConfiguration lumiShop() { return file("lumi-shop").configuration(); }
    public FileConfiguration customItemShop() { return file("custom-item-shop").configuration(); }
    public FileConfiguration professions() { return file("professions").configuration(); }
    public FileConfiguration angler() { return file("angler").configuration(); }
    public FileConfiguration rules() { return file("rules").configuration(); }
    public FileConfiguration jumpnrun() { return file("jumpnrun").configuration(); }
    public FileConfiguration quests() { return file("quests").configuration(); }

    public ConfigurationFile serverFile() { return file("server"); }
    public ConfigurationFile mainFile() { return file("main"); }
    public ConfigurationFile commandsFile() { return file("commands"); }
    public ConfigurationFile ranksFile() { return file("ranks"); }
    public ConfigurationFile messagesFile() { return file("messages"); }
    public ConfigurationFile soundsFile() { return file("sounds"); }
    public ConfigurationFile storageFile() { return file("storage"); }
    public ConfigurationFile menusFile() { return file("menus"); }
    public ConfigurationFile scoreboardsFile() { return file("scoreboards"); }
    public ConfigurationFile pricesFile() { return file("prices"); }
    public ConfigurationFile voteFile() { return file("vote"); }
    public ConfigurationFile dailyFile() { return file("daily"); }
    public ConfigurationFile chatColorsFile() { return file("chat-colors"); }
    public ConfigurationFile playtimeFile() { return file("playtime"); }
    public ConfigurationFile tradeFile() { return file("trade"); }
    public ConfigurationFile ordersFile() { return file("orders"); }
    public ConfigurationFile auctionHouseFile() { return file("auction-house"); }
    public ConfigurationFile friendsFile() { return file("friends"); }
    public ConfigurationFile duelsFile() { return file("duels"); }
    public ConfigurationFile kitsFile() { return file("kits"); }
    public ConfigurationFile chatEventsFile() { return file("chat-events"); }
    public ConfigurationFile itemsFile() { return file("items"); }
    public ConfigurationFile holzschlagFile() { return file("holzschlag"); }
    public ConfigurationFile miningEnchantsFile() { return file("mining-enchants"); }
    public ConfigurationFile customEnchantsFile() { return file("custom-enchants"); }
    public ConfigurationFile lumiShopFile() { return file("lumi-shop"); }
    public ConfigurationFile customItemShopFile() { return file("custom-item-shop"); }
    public ConfigurationFile professionsFile() { return file("professions"); }
    public ConfigurationFile anglerFile() { return file("angler"); }
    public ConfigurationFile rulesFile() { return file("rules"); }
    public ConfigurationFile jumpnrunFile() { return file("jumpnrun"); }
    public ConfigurationFile questsFile() { return file("quests"); }


    /**
     * Resolves the single file that owns an exact configured path. This is intended only for
     * infrastructure such as the central message service; feature code should use a typed getter.
     */
    public synchronized Optional<FileConfiguration> findOwner(String path) {
        if (path == null || path.isBlank()) return Optional.empty();
        ConfigurationFile found = null;
        String foundId = null;
        for (Map.Entry<String, ConfigurationFile> entry : files.entrySet()) {
            FileConfiguration configuration = entry.getValue().configuration();
            if (!configuration.contains(path, false)) continue;
            if (found != null) {
                if (reportedAmbiguousPaths.add(path)) {
                    plugin.getLogger().warning("Config-Pfad '" + path + "' ist nicht eindeutig: "
                            + foundId + " und " + entry.getKey() + ". Es wird kein Wert automatisch ausgewählt.");
                }
                return Optional.empty();
            }
            found = entry.getValue();
            foundId = entry.getKey();
        }
        return found == null ? Optional.empty() : Optional.of(found.configuration());
    }

    public synchronized void reloadAll() {
        for (ConfigurationFile file : files.values()) file.reload();
    }

    public synchronized boolean has(String id) {
        return files.containsKey(id);
    }

    private void register(String id, JavaPlugin plugin, ConfigurationLoader loader,
                          File target, String resourceName) {
        files.put(id, new ConfigurationFile(plugin, loader, target, resourceName));
    }

    private ConfigurationFile file(String id) {
        ConfigurationFile file = files.get(id);
        if (file == null) {
            throw new IllegalStateException("Konfiguration ist auf diesem Servertyp nicht verfügbar: " + id);
        }
        return file;
    }
}
