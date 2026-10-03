package de.walahi.novosmp.chatevent;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.ZonedDateTime;

/** Automatische, zufällige Chat-Aufgaben mit einem still vergebenen Vanta-Key. */
public final class ChatEventManager implements Listener {
    private static final int CHAT_CENTER_PX = 160;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final NovoSMPPlugin plugin;
    private final CrateManager crateManager;
    private FileConfiguration config;
    private final Random random = new Random();
    private final AtomicBoolean acceptingWinner = new AtomicBoolean(false);

    private volatile ActiveEvent activeEvent;
    private BukkitTask intervalTask;
    private String lastScheduledSlot = "";
    private BukkitTask timeoutTask;

    public ChatEventManager(NovoSMPPlugin plugin, CrateManager crateManager) {
        this.plugin = plugin;
        this.crateManager = crateManager;
        loadConfig();
    }

    private void loadConfig() {
        this.config = plugin.configs().chatEvents();
    }

    public void start() {
        stopTasks();
        if (!config.getBoolean("enabled", true)) return;

        // Echte Uhrzeit-Slots statt "30 Minuten nach Serverstart": xx:00 und xx:30.
        // Der Check läuft sekündlich, sodass auch kurze Server-Lags keinen Slot verpassen.
        intervalTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkScheduledSlot, 20L, 20L);
    }

    private void checkScheduledSlot() {
        ZonedDateTime now = ZonedDateTime.now();
        int minute = now.getMinute();
        if (minute != 0 && minute != 30) return;

        String slot = now.getYear() + "-" + now.getDayOfYear() + "-" + now.getHour() + "-" + minute;
        if (slot.equals(lastScheduledSlot)) return;
        lastScheduledSlot = slot;

        // Bei 0 Spielern wird dieser feste Slot still übersprungen.
        startRandomEvent();
    }

    public void shutdown() {
        stopTasks();
        activeEvent = null;
        acceptingWinner.set(false);
    }

    public void reload() {
        shutdown();
        loadConfig();
        start();
    }

    public boolean startRandomEvent() {
        if (!config.getBoolean("enabled", true)) return false;
        if (Bukkit.getOnlinePlayers().isEmpty()) return false;
        if (activeEvent != null) return false;

        ActiveEvent generated = generateEvent();
        activeEvent = generated;
        acceptingWinner.set(true);
        broadcastEvent(generated);
        playConfiguredSound("sounds.start", Sound.BLOCK_NOTE_BLOCK_XYLOPHONE, 1.0f, 1.15f);

        long durationSeconds = generated.durationSeconds();
        timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(generated), durationSeconds * 20L);
        return true;
    }

    public boolean stopCurrent(boolean announceSolution) {
        ActiveEvent current = activeEvent;
        if (current == null || !acceptingWinner.compareAndSet(true, false)) return false;
        activeEvent = null;
        cancelTimeout();
        if (announceSolution) announceTimeout(current);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ActiveEvent current = activeEvent;
        if (current == null || !acceptingWinner.get()) return;
        String message = PLAIN.serialize(event.message()).trim();
        boolean correct = current.exact()
                ? message.equals(current.answer())
                : normalize(message).equals(normalize(current.answer()));
        if (!correct) return;

        // Richtige Lösungen werden nicht als normale Chatnachricht angezeigt.
        event.setCancelled(true);
        if (!acceptingWinner.compareAndSet(true, false)) return;

        Player winner = event.getPlayer();
        activeEvent = null;
        Bukkit.getScheduler().runTask(plugin, () -> finishWinner(winner, current));
    }

    private void finishWinner(Player winner, ActiveEvent event) {
        cancelTimeout();
        if (!winner.isOnline()) return;

        String crateId = config.getString("reward.crate-id", "vanta");
        int amount = Math.max(1, config.getInt("reward.amount", 1));
        Optional<CrateDefinition> crate = crateManager.find(crateId);
        if (crate.isPresent()) {
            CrateDefinition rewardCrate = crate.get();
            int dropped = crateManager.keys().addAndCountDropped(winner, rewardCrate, amount);
            sendRewardMessage(winner, rewardCrate, amount, dropped);
        } else {
            plugin.getLogger().warning("Chat-Event-Belohnung konnte nicht vergeben werden: Crate '" + crateId + "' fehlt.");
        }
        broadcastWinner(winner, event.answer(), elapsedSeconds(event));
        playConfiguredSound(winner, "sounds.win", Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.15f);
        playConfiguredSoundForOthers(winner, "sounds.lose", Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE, 1.0f, 0.8f);
    }


    private void sendRewardMessage(Player winner, CrateDefinition crate, int amount, int dropped) {
        String keyName = crateManager.keys().keyDisplayName(crate);
        String path = dropped > 0 ? "messages.reward-dropped" : "messages.reward-received";
        String fallback = dropped > 0
                ? "<dark_gray>[</dark_gray><green>SMP</green><dark_gray>]</dark_gray> <yellow>Dein Inventar war voll. %amount%x %key% wurde vor dir gedroppt.</yellow>"
                : "<dark_gray>[</dark_gray><green>SMP</green><dark_gray>]</dark_gray> <green>Du hast %amount%x %key% erhalten.</green>";
        String template = config.getString(path, fallback);
        winner.sendMessage(MM.deserialize(template
                .replace("%amount%", String.valueOf(amount))
                .replace("%dropped%", String.valueOf(dropped))
                .replace("%key%", escape(keyName))));
    }

    private void expire(ActiveEvent expected) {
        if (activeEvent != expected || !acceptingWinner.compareAndSet(true, false)) return;
        activeEvent = null;
        timeoutTask = null;
        announceTimeout(expected);
    }

    private void announceTimeout(ActiveEvent event) {
        broadcastTimeout(event.answer());
        playConfiguredSound("sounds.timeout", Sound.ENTITY_VILLAGER_NO, 1.0f, 0.9f);
    }

    private ActiveEvent generateEvent() {
        List<String> enabled = config.getStringList("enabled-types");
        if (enabled.isEmpty()) enabled = List.of("math", "minecraft", "missing", "unscramble", "type");

        List<String> weighted = new ArrayList<>();
        for (String rawType : enabled) {
            String type = rawType.toLowerCase(Locale.ROOT);
            int weight = switch (type) {
                case "math" -> Math.max(1, config.getInt("weights.math", 45));
                case "minecraft", "question", "fragen" -> Math.max(1, config.getInt("weights.minecraft", 20));
                case "missing", "complete", "vervollstaendige" -> Math.max(1, config.getInt("weights.missing", 15));
                case "unscramble", "entwirre" -> Math.max(1, config.getInt("weights.unscramble", 15));
                case "type", "tippe" -> Math.max(1, config.getInt("weights.type", 5));
                default -> 0;
            };
            for (int i = 0; i < weight; i++) weighted.add(type);
        }
        if (weighted.isEmpty()) return mathQuestion();

        String type = weighted.get(random.nextInt(weighted.size()));
        return switch (type) {
            case "minecraft", "question", "fragen" -> minecraftQuestion();
            case "missing", "complete", "vervollstaendige" -> missingWord();
            case "unscramble", "entwirre" -> unscrambleWord();
            case "type", "tippe" -> typeExactly();
            default -> mathQuestion();
        };
    }

    private ActiveEvent mathQuestion() {
        // Nur leicht bis schwer – keine „sehr schweren“ Aufgaben, keine Division und keine Brüche.
        int difficulty = random.nextInt(3);
        String expression;
        int answer;

        if (difficulty == 0) {
            int a = randomSigned(4, 25);
            int b = randomSigned(3, 20);
            if (random.nextBoolean()) {
                answer = a + b;
                expression = formatNumber(a) + " + " + formatNumber(b);
            } else {
                answer = a - b;
                expression = formatNumber(a) + " - " + formatNumber(b);
            }
        } else if (difficulty == 1) {
            int a = randomSigned(2, 12);
            int b = randomSigned(2, 9);
            int c = randomSigned(1, 12);
            answer = a * b + c;
            expression = formatNumber(a) + " × " + formatNumber(b) + " + " + formatNumber(c);
        } else {
            // Beispiel: -4 + (-3) × -3 + 1
            int a = randomSigned(2, 9);
            int b = randomSigned(2, 7);
            int c = randomSigned(2, 7);
            int d = randomSigned(1, 9);
            answer = a + (b * c) + d;
            expression = formatNumber(a) + " + " + parenthesizedNumber(b)
                    + " × " + formatNumber(c) + " + " + formatNumber(d);
        }

        // Unnötig riesige Ergebnisse werden verworfen und neu generiert.
        if (answer < -100 || answer > 100) return mathQuestion();
        long duration = Math.max(5L, config.getLong("time.math", 30L));
        return new ActiveEvent(config.getString("messages.math", "Löse als Erstes im Chat:"), expression, String.valueOf(answer), false, System.nanoTime(), duration);
    }

    private int randomSigned(int minAbsolute, int maxAbsolute) {
        int value = random.nextInt(maxAbsolute - minAbsolute + 1) + minAbsolute;
        return random.nextBoolean() ? value : -value;
    }

    private String formatNumber(int value) {
        return value < 0 ? "(" + value + ")" : String.valueOf(value);
    }

    private String parenthesizedNumber(int value) {
        return "(" + value + ")";
    }

    private ActiveEvent minecraftQuestion() {
        List<Question> questions = configuredQuestions();
        if (questions.isEmpty()) {
            questions = List.of(
                    new Question("Wie viele Slots hat eine Doppelkiste?", "54"),
                    new Question("Wie viele Herzen hat ein Spieler standardmäßig?", "10"),
                    new Question("Wie heißt die Dimension des Enderdrachen?", "End"),
                    new Question("Welches Material benötigt man für ein Netherportal?", "Obsidian"),
                    new Question("Wie viele Blöcke hoch ist ein normales Netherportal mindestens?", "5")
            );
        }
        Question selected = questions.get(random.nextInt(questions.size()));
        long duration = Math.max(5L, config.getLong("time.default", 20L));
        return new ActiveEvent(config.getString("messages.minecraft", "Beantworte als Erstes im Chat:"), selected.question(), selected.answer(), false, System.nanoTime(), duration);
    }

    private List<Question> configuredQuestions() {
        List<Question> result = new ArrayList<>();
        for (var entry : config.getMapList("minecraft-questions")) {
            Object question = entry.get("question");
            Object answer = entry.get("answer");
            if (question != null && answer != null && !question.toString().isBlank() && !answer.toString().isBlank()) {
                result.add(new Question(question.toString(), answer.toString()));
            }
        }
        return result;
    }

    private ActiveEvent missingWord() {
        String answer = randomWord();
        String displayAnswer = displayWord(answer);
        char[] shown = displayAnswer.toCharArray();
        int removals = Math.max(1, Math.min(2, answer.length() / 4));
        List<Integer> positions = new ArrayList<>();
        for (int i = 1; i < shown.length - 1; i++) if (Character.isLetterOrDigit(shown[i])) positions.add(i);
        Collections.shuffle(positions, random);
        for (int i = 0; i < Math.min(removals, positions.size()); i++) shown[positions.get(i)] = '_';
        long duration = Math.max(5L, config.getLong("time.default", 20L));
        return new ActiveEvent(config.getString("messages.missing", "Vervollständige als Erstes im Chat:"), new String(shown), answer, false, System.nanoTime(), duration);
    }

    private ActiveEvent unscrambleWord() {
        String answer = randomWord();
        List<Character> chars = new ArrayList<>();
        String displayAnswer = displayWord(answer);
        // Groß-/Kleinschreibung bleibt am ursprünglichen Buchstaben hängen.
        // Bei „Redstone“ bleibt also das R groß, egal an welcher Position es landet.
        for (char c : displayAnswer.toCharArray()) chars.add(c);
        String shuffled;
        int attempts = 0;
        do {
            Collections.shuffle(chars, random);
            StringBuilder builder = new StringBuilder(chars.size());
            chars.forEach(builder::append);
            shuffled = builder.toString();
        } while (shuffled.equals(displayAnswer) && ++attempts < 8);
        long duration = Math.max(5L, config.getLong("time.default", 20L));
        return new ActiveEvent(config.getString("messages.unscramble", "Entwirre als Erstes im Chat:"), shuffled, answer, false, System.nanoTime(), duration);
    }

    private ActiveEvent typeExactly() {
        int length = Math.max(4, config.getInt("type.length", 8));
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        long duration = Math.max(5L, config.getLong("time.default", 20L));
        return new ActiveEvent(config.getString("messages.typing", "Tippe als Erstes im Chat:"), value.toString(), value.toString(), true, System.nanoTime(), duration);
    }

    private String randomWord() {
        List<String> words = config.getStringList("words").stream()
                .map(String::trim).filter(word -> word.length() >= 4).toList();
        if (words.isEmpty()) words = List.of("Diamant", "Crafting", "Redstone", "Netherite", "Enderman", "Werkbank", "Spawner", "Novoria");
        return words.get(random.nextInt(words.size()));
    }

    private void broadcastEvent(ActiveEvent event) {
        String borderText = config.getString("layout.border-text", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        String borderFormat = config.getString("layout.border-format", "<#7A1E2C><bold>%text%</bold></#7A1E2C>");
        Component border = centered(borderFormat.replace("%text%", escape(borderText)), borderText, true);

        String titleText = config.getString("messages.title", "AUFGABE");
        String titleFormat = config.getString("layout.title-format", "<#E63946><bold>%text%</bold></#E63946>");
        Component title = centered(titleFormat.replace("%text%", escape(titleText)), titleText, true);
        int wrapPixels = Math.max(80, config.getInt("layout.wrap-pixels", 220));

        List<Component> lines = new ArrayList<>();
        lines.add(border);
        lines.add(blankLine());
        lines.add(title);
        lines.add(blankLine());
        for (String line : wrapText(event.instruction(), wrapPixels, false)) {
            lines.add(centered("<gray>" + escape(line) + "</gray>", line, false));
        }
        lines.add(blankLine());
        for (String line : wrapText(event.taskText(), wrapPixels, true)) {
            lines.add(centered("<#FF6B6B><bold>" + escape(line) + "</bold></#FF6B6B>", line, true));
        }

        String rewardLine = config.getString("messages.reward-line", "");
        if (rewardLine != null && !rewardLine.isBlank()) {
            lines.add(blankLine());
            lines.add(centered("<dark_gray>" + escape(rewardLine) + "</dark_gray>", rewardLine, false));
        }
        lines.add(blankLine());
        lines.add(border);
        broadcastBlock(lines.toArray(Component[]::new));
    }

    private void broadcastWinner(Player winnerPlayer, String answer, double elapsedSeconds) {
        String formattedTime = String.format(Locale.GERMANY, "%.2f", elapsedSeconds);
        Component winnerName = plugin.getRankManager() != null
                ? plugin.getRankManager().coloredName(winnerPlayer)
                : Component.text(winnerPlayer.getName()).color(net.kyori.adventure.text.format.NamedTextColor.GRAY);

        String visibleAnswer = displayAnswer(answer);
        String template = config.getString("messages.winner",
                "<dark_gray>[</dark_gray><green>SMP</green><dark_gray>]</dark_gray> <green>✔ </green>%player% <gray>hat die Frage richtig beantwortet:</gray> <#FF6B6B><bold>%answer%</bold></#FF6B6B> <dark_gray>(</dark_gray><aqua>%time%s</aqua><dark_gray>)</dark_gray>");
        String[] parts = template.split("%player%", 2);
        Component message = MM.deserialize(parts[0]
                .replace("%time%", formattedTime)
                .replace("%answer%", escape(visibleAnswer)));
        message = message.append(winnerName);
        if (parts.length > 1) {
            message = message.append(MM.deserialize(parts[1]
                    .replace("%time%", formattedTime)
                    .replace("%answer%", escape(visibleAnswer))));
        }
        Bukkit.getServer().broadcast(message);
    }

    private void broadcastTimeout(String answer) {
        String visibleAnswer = displayAnswer(answer);
        String template = config.getString("messages.timeout",
                "<dark_gray>[</dark_gray><green>SMP</green><dark_gray>]</dark_gray> <red>✘ Die Zeit ist abgelaufen.</red> <gray>Richtige Antwort:</gray> <#FF6B6B><bold>%answer%</bold></#FF6B6B>");
        Bukkit.getServer().broadcast(MM.deserialize(template.replace("%answer%", escape(visibleAnswer))));
    }

    private Component blankLine() {
        return Component.text(" ");
    }

    private double elapsedSeconds(ActiveEvent event) {
        return Math.max(0.0D, (System.nanoTime() - event.startedAtNanos()) / 1_000_000_000.0D);
    }

    private void broadcastBlock(Component... lines) {
        // Ein gemeinsamer Block bewahrt interne Leerzeilen. Außerhalb des roten
        // Rahmens werden bewusst keine zusätzlichen Leerzeilen eingefügt.
        Component block = Component.join(JoinConfiguration.separator(Component.newline()), lines);
        Bukkit.getServer().broadcast(block);
    }

    private List<String> wrapText(String text, int maxPixels, boolean bold) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && textWidth(candidate, bold) > maxPixels) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            } else {
                if (!current.isEmpty()) current.append(' ');
                current.append(word);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private String displayWord(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return trimmed;
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1).toLowerCase(Locale.ROOT);
    }

    private String displayAnswer(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.matches("[\\p{L}_-]+")) return displayWord(trimmed);
        return trimmed;
    }

    private Component centeredComponent(Component content, String visibleText, boolean bold) {
        int width = textWidth(visibleText, bold);
        int paddingPixels = Math.max(0, CHAT_CENTER_PX - (width / 2));
        int spaces = Math.max(0, paddingPixels / 4);
        return Component.text(" ".repeat(spaces)).append(content);
    }

    private Component centered(String miniMessage, String visibleText, boolean bold) {
        int width = textWidth(visibleText, bold);
        int paddingPixels = Math.max(0, CHAT_CENTER_PX - (width / 2));
        int spaces = Math.max(0, paddingPixels / 4);
        return Component.text(" ".repeat(spaces)).append(MM.deserialize(miniMessage));
    }

    private int textWidth(String text, boolean bold) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            width += glyphWidth(character);
            if (bold && character != ' ') width++;
        }
        return width;
    }

    private int glyphWidth(char character) {
        return switch (character) {
            case ' ', 'i', 'l', 'I', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 't', 'f', 'k', '(', ')', '[', ']', '{', '}', '"', '*' -> 4;
            case '━', '▬', '✦', '✔', '✘', '⚡', '⌛', '»', '«', '┃' -> 6;
            default -> 6;
        };
    }

    private void playConfiguredSound(String path, Sound fallback, float defaultVolume, float defaultPitch) {
        SoundSettings settings = configuredSound(path, fallback, defaultVolume, defaultPitch);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), settings.sound(), settings.volume(), settings.pitch());
        }
    }

    private void playConfiguredSound(Player player, String path, Sound fallback, float defaultVolume, float defaultPitch) {
        if (player == null || !player.isOnline()) return;
        SoundSettings settings = configuredSound(path, fallback, defaultVolume, defaultPitch);
        player.playSound(player.getLocation(), settings.sound(), settings.volume(), settings.pitch());
    }

    private void playConfiguredSoundForOthers(Player excluded, String path, Sound fallback,
                                              float defaultVolume, float defaultPitch) {
        SoundSettings settings = configuredSound(path, fallback, defaultVolume, defaultPitch);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (excluded != null && player.getUniqueId().equals(excluded.getUniqueId())) continue;
            player.playSound(player.getLocation(), settings.sound(), settings.volume(), settings.pitch());
        }
    }

    private SoundSettings configuredSound(String path, Sound fallback, float defaultVolume, float defaultPitch) {
        String configured = config.getString(path + ".name", fallback.name());
        float volume = (float) config.getDouble(path + ".volume", defaultVolume);
        float pitch = (float) config.getDouble(path + ".pitch", defaultPitch);
        Sound sound;
        try {
            sound = Sound.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sound = fallback;
            plugin.getLogger().warning("Ungültiger Chat-Event-Sound '" + configured + "' unter " + path
                    + ". Fallback: " + fallback.name());
        }
        return new SoundSettings(sound, volume, pitch);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String escape(String value) {
        return MM.escapeTags(value);
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
    }

    private void stopTasks() {
        if (intervalTask != null) {
            intervalTask.cancel();
            intervalTask = null;
        }
        cancelTimeout();
    }

    private record SoundSettings(Sound sound, float volume, float pitch) {}
    private record ActiveEvent(String instruction, String taskText, String answer, boolean exact,
                               long startedAtNanos, long durationSeconds) {}
    private record Question(String question, String answer) {}
}
