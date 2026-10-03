package de.walahi.novosmp.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public final class RulesCommand extends BaseCommand {
    private static final int VANILLA_MAX_PAGES = 100;

    private final MiniMessage miniMessage = MiniMessage.builder().strict(true).build();
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private volatile PreparedBook preparedBook;

    public RulesCommand(SMPCorePlugin plugin) {
        super(plugin);
        reload();
    }

    @Override
    protected String permission() {
        return null;
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sendConfigured(sender, "messages.player-only");
            return true;
        }

        PreparedBook prepared = preparedBook;
        if (prepared == null) {
            sendConfigured(player, "messages.error");
            return true;
        }

        try {
            player.openBook(prepared.book());
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Das virtuelle Regelbuch konnte für " + player.getName() + " nicht geöffnet werden.", exception);
            sendConfigured(player, "messages.error");
        }
        return true;
    }

    /** Rebuilds the immutable book cache after the central configuration reload. */
    public void reload() {
        try {
            PreparedBook rebuilt = buildBook(plugin.configs().rules());
            preparedBook = rebuilt;
            plugin.getLogger().info("Virtuelles Regelbuch vorbereitet (" + rebuilt.pageCount()
                    + " Seiten, " + rebuilt.chapterCount() + " Kapitel)." );
        } catch (RuntimeException exception) {
            String cacheState = preparedBook == null
                    ? "/rules bleibt bis zur Korrektur deaktiviert."
                    : "Der zuletzt erfolgreich erzeugte Buch-Cache bleibt aktiv.";
            plugin.getLogger().log(Level.WARNING,
                    "rules.yml konnte nicht als virtuelles Regelbuch geladen werden. " + cacheState,
                    exception);
        }
    }

    private PreparedBook buildBook(FileConfiguration config) {
        if (config.contains("categories", true) && !config.contains("chapters", true)) {
            plugin.getLogger().warning("Veraltete rules.yml erkannt: Die Datei verwendet noch 'categories:' statt 'chapters:'. "
                    + "Die Datei wurde nicht überschrieben. Bitte die neue rules.yml manuell übernehmen.");
            throw new IllegalArgumentException("Veraltete rules.yml-Struktur ('categories:' ohne 'chapters:').");
        }

        TagResolver configuredTags = configuredTags(config);
        Component bookTitle = parseRequired(config, "book.title", configuredTags);
        Component bookAuthor = parseRequired(config, "book.author", configuredTags);
        ConfigurationSection chapterSection = config.getConfigurationSection("chapters");
        if (chapterSection == null) {
            throw new IllegalArgumentException("Der Abschnitt 'chapters' fehlt.");
        }

        List<Chapter> chapters = new ArrayList<>();
        for (String id : chapterSection.getKeys(false)) {
            String path = "chapters." + id;
            ConfigurationSection section = config.getConfigurationSection(path);
            if (section == null) {
                plugin.getLogger().warning("Regelkapitel '" + id + "' ist kein gültiger YAML-Abschnitt und wird übersprungen.");
                continue;
            }
            String title = requiredString(config, path + ".title");
            List<String> rawPages = config.getStringList(path + ".pages");
            List<Component> pages = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < rawPages.size(); pageIndex++) {
                String rawPage = rawPages.get(pageIndex);
                if (rawPage == null || rawPage.isBlank()) {
                    plugin.getLogger().warning("Leere Seite " + (pageIndex + 1) + " in Regelkapitel '" + id + "' wird übersprungen.");
                    continue;
                }
                try {
                    pages.add(parse(rawPage, path + ".pages[" + pageIndex + "]", configuredTags));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("MiniMessage-Fehler in Kapitel '" + id + "', Seite "
                            + (pageIndex + 1) + " (" + preview(rawPage) + ").", exception);
                }
            }
            if (pages.isEmpty()) {
                plugin.getLogger().warning("Regelkapitel '" + id + "' hat keine nutzbaren Seiten und wird übersprungen.");
                continue;
            }
            chapters.add(new Chapter(id, title, List.copyOf(pages)));
        }
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("Es wurden keine nutzbaren Regelkapitel gefunden.");
        }

        int entriesPerPage = Math.max(1, config.getInt("index.entries-per-page", 5));
        int indexPageCount = (chapters.size() + entriesPerPage - 1) / entriesPerPage;
        Map<String, Integer> chapterStartPages = new LinkedHashMap<>();
        int nextPage = indexPageCount + 1;
        for (Chapter chapter : chapters) {
            chapterStartPages.put(chapter.id(), nextPage);
            nextPage += chapter.pages().size();
        }

        List<Component> pages = new ArrayList<>();
        for (int indexPage = 0; indexPage < indexPageCount; indexPage++) {
            pages.add(buildIndexPage(config, chapters, chapterStartPages, indexPage, indexPageCount,
                    entriesPerPage, configuredTags));
        }
        for (Chapter chapter : chapters) {
            for (int pageIndex = 0; pageIndex < chapter.pages().size(); pageIndex++) {
                pages.add(buildChapterPage(config, chapter, pageIndex, configuredTags));
            }
        }

        int configuredMaxPages = Math.max(1, Math.min(VANILLA_MAX_PAGES,
                config.getInt("limits.max-pages", VANILLA_MAX_PAGES)));
        if (pages.size() > configuredMaxPages) {
            throw new IllegalArgumentException("Das Regelbuch hat " + pages.size() + " Seiten, erlaubt sind "
                    + configuredMaxPages + ". Es wurde nichts abgeschnitten.");
        }

        int warningLength = Math.max(1, config.getInt("limits.warn-plain-characters-per-page", 700));
        for (int i = 0; i < pages.size(); i++) {
            int characters = plainText.serialize(pages.get(i)).length();
            if (characters > warningLength) {
                plugin.getLogger().warning("Regelbuch-Seite " + (i + 1) + " enthält " + characters
                        + " sichtbare Zeichen und könnte im Vanilla-Buch zu dicht dargestellt werden.");
            }
        }

        return new PreparedBook(Book.book(bookTitle, bookAuthor, List.copyOf(pages)), pages.size(), chapters.size());
    }

    private Component buildIndexPage(FileConfiguration config,
                                     List<Chapter> chapters,
                                     Map<String, Integer> chapterStartPages,
                                     int indexPage,
                                     int indexPageCount,
                                     int entriesPerPage,
                                     TagResolver configuredTags) {
        Component page = parseRequired(config, "index.title", configuredTags)
                .append(Component.newline())
                .append(parseRequired(config, "index.subtitle", configuredTags));

        String pageIndicator = config.getString("index.page-indicator", "");
        if (indexPageCount > 1 && pageIndicator != null && !pageIndicator.isBlank()) {
            page = page.append(Component.newline())
                    .append(parse(replacePageNumbers(pageIndicator, indexPage + 1, indexPageCount),
                            "index.page-indicator", configuredTags));
        }
        page = page.append(Component.newline()).append(Component.newline());

        int from = indexPage * entriesPerPage;
        int to = Math.min(from + entriesPerPage, chapters.size());
        String entryFormat = requiredString(config, "index.entry-format");
        String hoverFormat = requiredString(config, "index.entry-hover");
        for (int i = from; i < to; i++) {
            Chapter chapter = chapters.get(i);
            String renderedEntry = entryFormat
                    .replace("%number%", Integer.toString(i + 1))
                    .replace("%title%", chapter.title());
            String renderedHover = hoverFormat
                    .replace("%number%", Integer.toString(i + 1))
                    .replace("%title%", chapter.title());
            Component entry = parse(renderedEntry, "index.entry-format", configuredTags)
                    .clickEvent(ClickEvent.changePage(chapterStartPages.get(chapter.id())))
                    .hoverEvent(HoverEvent.showText(parse(renderedHover, "index.entry-hover", configuredTags)));
            page = page.append(entry);
            if (i + 1 < to) page = page.append(Component.newline());
        }
        return page;
    }

    private Component buildChapterPage(FileConfiguration config, Chapter chapter, int pageIndex,
                                       TagResolver configuredTags) {
        String title = requiredString(config, "chapter.title-format")
                .replace("%title%", chapter.title());
        String indicator = requiredString(config, "chapter.page-indicator")
                .replace("%page%", Integer.toString(pageIndex + 1))
                .replace("%pages%", Integer.toString(chapter.pages().size()));
        Component back = parseRequired(config, "navigation.back-to-index", configuredTags)
                .clickEvent(ClickEvent.changePage(1))
                .hoverEvent(HoverEvent.showText(parseRequired(config, "navigation.back-hover", configuredTags)));

        return parse(title, "chapter.title-format", configuredTags)
                .append(Component.newline())
                .append(parse(indicator, "chapter.page-indicator", configuredTags))
                .append(Component.newline())
                .append(Component.newline())
                .append(chapter.pages().get(pageIndex))
                .append(Component.newline())
                .append(Component.newline())
                .append(back);
    }

    private String replacePageNumbers(String input, int page, int pages) {
        return input.replace("%page%", Integer.toString(page))
                .replace("%pages%", Integer.toString(pages));
    }

    private Component parseRequired(FileConfiguration config, String path, TagResolver configuredTags) {
        return parse(requiredString(config, path), path, configuredTags);
    }

    private String requiredString(FileConfiguration config, String path) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Pflichtwert '" + path + "' fehlt oder ist leer.");
        }
        return value;
    }

    private Component parse(String input, String path, TagResolver configuredTags) {
        try {
            return miniMessage.deserialize(input, configuredTags);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Ungültige MiniMessage-Formatierung bei '" + path + "'.", exception);
        }
    }

    private TagResolver configuredTags(FileConfiguration config) {
        String sectionSymbol = requiredString(config, "symbols.section");
        return TagResolver.resolver("section", Tag.inserting(Component.text(sectionSymbol)));
    }

    private String preview(String input) {
        String normalized = input.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    private void sendConfigured(CommandSender sender, String path) {
        String configured = plugin.configs().rules().getString(path);
        if (configured == null || configured.isBlank()) {
            plugin.getLogger().warning("Rules-Nachricht fehlt: " + path);
            return;
        }
        try {
            sender.sendMessage(miniMessage.deserialize(configured, configuredTags(plugin.configs().rules())));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Ungültige Rules-Nachricht bei '" + path + "'.", exception);
        }
    }

    private record Chapter(String id, String title, List<Component> pages) {}
    private record PreparedBook(Book book, int pageCount, int chapterCount) {}
}
