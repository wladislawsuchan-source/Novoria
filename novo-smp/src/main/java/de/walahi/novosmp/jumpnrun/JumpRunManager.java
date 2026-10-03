package de.walahi.novosmp.jumpnrun;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.HologramManager;
import de.walahi.smpcore.commands.framework.BaseCommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/** Endless personal parkour generated from the values in jumpnrun.yml. */
public final class JumpRunManager extends BaseCommand implements Listener {
    private static final double TECH_MAX_FLAT = 4.0, TECH_MAX_UP = 3.25, TECH_MAX_DOWN = 4.25;
    private static final String LEADERBOARD_HOLOGRAM_ID = "jumpnrun_leaderboard";
    private final NovoSMPPlugin novo;
    private final Supplier<HologramManager> hologramSupplier;
    private final JumpRunHighscoreRepository highscoreRepository;
    private final Map<UUID, LeaderboardEntry> highscoreCache = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Set<String> warnedMaterials = new HashSet<>(), warnedSounds = new HashSet<>();
    private final Random random = new Random();
    private final NamespacedKey highscoreKey;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final BukkitTask actionbarTask;
    private final BukkitTask leaderboardTask;

    public JumpRunManager(NovoSMPPlugin plugin, Supplier<HologramManager> hologramSupplier) {
        super(plugin);
        novo = plugin;
        this.hologramSupplier = hologramSupplier;
        highscoreKey = new NamespacedKey(plugin, "jumpnrun_highscore");
        highscoreRepository = new JumpRunHighscoreRepository(plugin.databaseManager());
        try {
            for (JumpRunHighscoreRepository.Entry entry : highscoreRepository.loadAll().values()) {
                highscoreCache.put(entry.playerId(), new LeaderboardEntry(
                        entry.playerId(), entry.name(), entry.score()));
            }
            migrateYamlHighscores();
        } catch (SQLException exception) {
            throw new IllegalStateException("Jump'n'Run-Highscores konnten nicht geladen werden", exception);
        }
        long interval = clamp(config().getLong("actionbar.refresh-ticks", 20), 5, 200);
        actionbarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshActionbars, interval, interval);
        long refreshSeconds = clamp(config().getLong("leaderboard.refresh-seconds", 60), 0, 86_400);
        leaderboardTask = refreshSeconds > 0
                ? Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLeaderboard,
                Math.max(1, refreshSeconds * 20), Math.max(1, refreshSeconds * 20))
                : null;
        Bukkit.getScheduler().runTask(plugin, this::refreshLeaderboard);
    }

    @Override protected String permission() { return null; }

    @Override protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) { send(sender, "messages.player-only", Map.of()); return true; }
        if (args.length == 0) {
            sendLines(player, "messages.usage", Map.of());
            if (player.hasPermission("smpcore.admin")) sendLines(player, "messages.admin-usage", Map.of());
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("check") || sub.equals("info")) { showInfo(player); return true; }
        if (!player.hasPermission("smpcore.admin")) { send(player, "messages.no-permission", Map.of()); return true; }
        if (sub.equals("remove")) {
            config().set("trigger.world", "");
            novo.configs().jumpnrunFile().saveWithDefaults();
            HologramManager holograms = holograms();
            if (holograms != null) holograms.delete(LEADERBOARD_HOLOGRAM_ID);
            send(player, "messages.trigger-removed", Map.of());
            return true;
        }
        if (!sub.equals("setplate")) {
            sendLines(player, "messages.usage", Map.of());
            sendLines(player, "messages.admin-usage", Map.of());
            return true;
        }
        Block target = player.getTargetBlockExact(clamp(config().getInt("admin.target-distance", 6), 1, 20));
        if (target == null) target = player.getLocation().clone().subtract(0, 1, 0).getBlock();
        Material requested = args.length > 1 ? Material.matchMaterial(args[1]) : target.getType();
        if (requested == null || !isPressurePlate(requested) || target.getType() != requested) {
            send(player, "messages.invalid-trigger", Map.of()); return true;
        }
        config().set("trigger.world", target.getWorld().getName());
        config().set("trigger.x", target.getX()); config().set("trigger.y", target.getY());
        config().set("trigger.z", target.getZ()); config().set("trigger.material", requested.name());
        novo.configs().jumpnrunFile().saveWithDefaults();
        refreshLeaderboard();
        send(player, "messages.trigger-saved", triggerValues(target));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;
        Location to = event.getTo();
        if (to == null) return;
        Location from = event.getFrom();
        boolean changedBlock = from.getWorld() != to.getWorld()
                || from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
        if (changedBlock && !sessions.containsKey(event.getPlayer().getUniqueId())
                && !isTriggerLocation(from) && isTriggerLocation(to)) {
            start(event.getPlayer());
            return;
        }
        Session session = sessions.get(event.getPlayer().getUniqueId());
        Step next = session == null ? null : session.upcoming.peekFirst();
        if (session == null || session.teleporting || next == null) return;
        if (isLanding(event.getPlayer(), from, to, next.block)) {
            completeJump(event.getPlayer(), session);
            return;
        }
        int below = clamp(config().getInt("fail.below-current-block", 8), 2, 128);
        if (to.getY() < session.current.y - below) finish(event.getPlayer(), session, FinishReason.FALL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !internalTeleports.contains(event.getPlayer().getUniqueId()))
            finish(event.getPlayer(), session, FinishReason.EXTERNAL);
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && !internalTeleports.contains(event.getPlayer().getUniqueId()))
            finish(event.getPlayer(), session, FinishReason.EXTERNAL);
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Session session = sessions.get(event.getEntity().getUniqueId());
        if (session != null) finish(event.getEntity(), session, FinishReason.DEATH);
    }
    @EventHandler public void onKick(PlayerKickEvent event) { disconnect(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { disconnect(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent event) {
        if (isGeneratedBlock(event.getBlock())) event.setCancelled(true);
    }

    public void shutdown() {
        actionbarTask.cancel();
        if (leaderboardTask != null) leaderboardTask.cancel();
        for (Session session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player != null) storeHighscore(player, session.score);
            cleanup(session);
        }
        sessions.clear(); internalTeleports.clear();
        HologramManager holograms = holograms();
        if (holograms != null) holograms.remove(LEADERBOARD_HOLOGRAM_ID);
    }

    public void reload() {
        refreshLeaderboard();
    }

    private void disconnect(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) finish(player, session, FinishReason.DISCONNECT);
    }

    private void start(Player player) {
        if (!config().getBoolean("enabled", true)) return;
        if (sessions.containsKey(player.getUniqueId())) { send(player, "messages.already-running", Map.of()); return; }
        Location spawn = novo.getSmpSpawnLocation();
        if (spawn == null || spawn.getWorld() == null) spawn = player.getWorld().getSpawnLocation();
        Block start = findStart(spawn);
        if (start == null) { send(player, "messages.no-start-position", Map.of()); return; }
        Material material = randomMaterial();
        if (material == null) { send(player, "messages.no-valid-material", Map.of()); return; }
        Vector heading = randomHeading();
        Session session = new Session(player.getUniqueId(), place(start, material), heading, highscore(player));
        remember(session, session.current); sessions.put(player.getUniqueId(), session);
        session.teleporting = true; internalTeleports.add(player.getUniqueId());
        player.teleportAsync(standingLocation(session.current, heading, player.getLocation().getPitch()))
                .whenComplete((success, error) -> Bukkit.getScheduler().runTask(novo,
                        () -> finishStart(player, session, error == null && Boolean.TRUE.equals(success))));
    }

    private void finishStart(Player player, Session session, boolean success) {
        internalTeleports.remove(player.getUniqueId());
        if (sessions.get(player.getUniqueId()) != session) return;
        session.teleporting = false;
        if (!success || !player.isOnline() || !fillPreview(session)) {
            finish(player, session, FinishReason.TECHNICAL); return;
        }
        send(player, "messages.started", runValues(session)); showActionbar(player, session);
    }

    private Block findStart(Location spawn) {
        World world = spawn.getWorld(); if (world == null) return null;
        int radius = clamp(config().getInt("start.horizontal-radius", 150), 0, 10_000);
        int attempts = clamp(config().getInt("start.max-position-attempts", 40), 1, 500);
        int height = clamp(config().getInt("start.height-above-spawn", 100), 16, 1_000);
        int variation = clamp(config().getInt("start.height-variation", 10), 0, 128);
        int runDistance = clamp(config().getInt("runs.minimum-distance", 32), 4, 1_000);
        for (int attempt = 0; attempt < attempts; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2, distance = Math.sqrt(random.nextDouble()) * radius;
            int x = spawn.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = spawn.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = spawn.getBlockY() + height + randomBetween(-variation, variation);
            if (y < world.getMinHeight() + 2 || y > world.getMaxHeight() - 4 || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block candidate = world.getBlockAt(x, y, z);
            if (insideBorder(candidate.getLocation(), borderMargin()) && startAreaClear(candidate)
                    && farFromOtherRuns(candidate.getLocation(), runDistance, null)) return candidate;
        }
        return null;
    }

    private boolean fillPreview(Session session) {
        int visible = clamp(config().getInt("generator.visible-platforms", 3), 2, 5);
        while (1 + session.upcoming.size() < visible) {
            if (!createTarget(session)) return false;
        }
        return true;
    }

    private boolean createTarget(Session session) {
        Placed base = session.upcoming.isEmpty() ? session.current : session.upcoming.getLast().block;
        Vector heading = session.upcoming.isEmpty() ? session.heading.clone() : session.upcoming.getLast().heading.clone();
        int attempts = clamp(config().getInt("generator.max-attempts-per-jump", 30), 1, 200);
        for (int attempt = 0; attempt < attempts; attempt++) {
            Candidate candidate = randomCandidate(base, heading, difficultyAt(session.score + session.upcoming.size()));
            Result result = validate(session, base, candidate);
            if (result == Result.BORDER) heading = steerInward(base, heading);
            if (result == Result.VALID) return accept(session, candidate);
        }
        int fallbackAttempts = clamp(config().getInt("generator.fallback-attempts", 20), 1, 100);
        for (int attempt = 0; attempt < fallbackAttempts; attempt++) {
            if (attempt % 4 == 0) heading = steerInward(base, heading);
            Candidate candidate = fallbackCandidate(base, heading, attempt);
            Result result = validate(session, base, candidate);
            if (result == Result.BORDER) heading = steerInward(base, heading);
            if (result == Result.VALID) return accept(session, candidate);
        }
        return false;
    }

    private Candidate randomCandidate(Placed base, Vector baseHeading, Difficulty difficulty) {
        Vector heading = baseHeading.clone();
        if (roll(difficulty.turnChance)) heading = rotate(heading,
                Math.toRadians(difficulty.maxTurn) * (random.nextBoolean() ? 1 : -1));
        int distance = weighted(difficulty.horizontal, 1), rise = weighted(difficulty.vertical, 0);
        double side = roll(difficulty.diagonalChance)
                ? config().getDouble("generator.diagonal-side-offset", 1) * (random.nextBoolean() ? 1 : -1) : 0;
        Vector right = new Vector(-heading.getZ(), 0, heading.getX());
        int dx = (int) Math.round(heading.getX() * distance + right.getX() * side);
        int dz = (int) Math.round(heading.getZ() * distance + right.getZ() * side);
        if (dx == 0 && dz == 0) { dx = (int) Math.signum(heading.getX()); dz = dx == 0 ? (int) Math.signum(heading.getZ()) : 0; }
        return new Candidate(base.x + dx, base.y + rise, base.z + dz, heading);
    }

    private Candidate fallbackCandidate(Placed base, Vector baseHeading, int attempt) {
        Vector heading = rotate(baseHeading, Math.toRadians((attempt % 5 - 2) * 10));
        // Two block coordinates are the shortest fallback that still leaves an air gap.
        int distance = 2;
        int dx = (int) Math.round(heading.getX() * distance), dz = (int) Math.round(heading.getZ() * distance);
        if (dx == 0 && dz == 0) dz = 1;
        return new Candidate(base.x + dx, base.y, base.z + dz, heading);
    }

    /** Every generated jump, including fallbacks, must pass this central validator. */
    private Result validate(Session session, Placed base, Candidate candidate) {
        World world = world(base.world); if (world == null) return Result.BLOCKED;
        if (candidate.y < world.getMinHeight() + 1 || candidate.y > world.getMaxHeight() - 3
                || !world.isChunkLoaded(candidate.x >> 4, candidate.z >> 4)) return Result.BLOCKED;
        Block target = world.getBlockAt(candidate.x, candidate.y, candidate.z);
        if (!hasRequiredAirGap(base, candidate)) return Result.AIR_GAP;
        if (!isJumpPossible(base, target)) return Result.IMPOSSIBLE;
        if (!landingClear(target) || !pathClear(base, target)) return Result.BLOCKED;
        if (!insideBorder(target.getLocation(), borderMargin())) return Result.BORDER;
        if (!farFromOtherRuns(target.getLocation(), clamp(config().getInt("runs.minimum-distance", 32), 4, 1_000), session)) return Result.OTHER_RUN;
        if (session.visited.contains(blockKey(target))) return Result.SELF_INTERSECTION;
        double selfDistance = Math.max(0, config().getDouble("generator.minimum-self-distance", 1.25));
        for (Position position : session.history) {
            if (position.x == base.x && position.y == base.y && position.z == base.z) continue;
            if (distance(position.x, position.z, candidate.x, candidate.z) < selfDistance) return Result.SELF_INTERSECTION;
        }
        return Result.VALID;
    }

    /** Prevents face, edge and corner contact between two consecutive platforms. */
    private boolean hasRequiredAirGap(Placed current, Candidate candidate) {
        int dx = Math.abs(candidate.x - current.x);
        int dy = Math.abs(candidate.y - current.y);
        int dz = Math.abs(candidate.z - current.z);
        return dx > 1 || dy > 1 || dz > 1;
    }

    private boolean isJumpPossible(Placed current, Block candidate) {
        int dx = candidate.getX() - current.x, dz = candidate.getZ() - current.z, dy = candidate.getY() - current.y;
        double horizontal = Math.hypot(dx, dz);
        double minimum = Math.max(.5, config().getDouble("validator.minimum-horizontal", 1));
        if (horizontal < minimum) return false;
        int rise = clamp(config().getInt("validator.maximum-rise", 1), 0, 1);
        int drop = clamp(config().getInt("validator.maximum-drop", 1), 0, 3);
        if (dy > rise || dy < -drop) return false;
        double configured, technical;
        if (dy > 0) { configured = config().getDouble("validator.maximum-horizontal.up-one", 3); technical = TECH_MAX_UP; }
        else if (dy < 0) { configured = config().getDouble("validator.maximum-horizontal.down-one", 4); technical = TECH_MAX_DOWN; }
        else { configured = config().getDouble("validator.maximum-horizontal.same-level", 3.75); technical = TECH_MAX_FLAT; }
        return horizontal <= Math.min(Math.max(minimum, configured), technical);
    }

    private boolean pathClear(Placed current, Block target) {
        World world = target.getWorld();
        double arc = clamp(config().getDouble("validator.path-arc-height", 1.25), .75, 2);
        int samples = clamp(config().getInt("validator.path-samples", 12), 4, 40);
        for (int i = 1; i < samples; i++) {
            double p = i / (double) samples;
            double x = lerp(current.x + .5, target.getX() + .5, p), z = lerp(current.z + .5, target.getZ() + .5, p);
            double feetY = lerp(current.y + 1.001, target.getY() + 1.001, p) + 4 * arc * p * (1 - p);
            if (!world.getBlockAt(floor(x), floor(feetY + .05), floor(z)).isPassable()
                    || !world.getBlockAt(floor(x), floor(feetY + 1.75), floor(z)).isPassable()) return false;
        }
        return true;
    }

    private boolean accept(Session session, Candidate candidate) {
        Material material = randomMaterial(); World world = world(session.current.world);
        if (material == null || world == null) return false;
        Placed block = place(world.getBlockAt(candidate.x, candidate.y, candidate.z), material);
        session.upcoming.addLast(new Step(block, candidate.heading.clone()));
        remember(session, block);
        return true;
    }

    private void completeJump(Player player, Session session) {
        Step reached = session.upcoming.pollFirst();
        if (reached == null) return;
        Placed old = session.current;
        session.current = reached.block;
        session.heading = reached.heading;
        session.score++;
        remove(old);
        playSound(player, "sounds.jump-success");
        if (!fillPreview(session)) { finish(player, session, FinishReason.TECHNICAL); return; }
        showActionbar(player, session);
    }

    private boolean isLanding(Player player, Location from, Location feet, Placed target) {
        if (!target.world.equals(feet.getWorld().getName())) return false;
        double expectedY = target.y + 1;
        if (feet.getY() < expectedY - .08 || feet.getY() > expectedY + .20) return false;
        if (player.isFlying() || feet.getY() > from.getY() + .001 || player.getVelocity().getY() > .15) return false;
        World world = world(target.world);
        if (world == null || world.getBlockAt(target.x, target.y, target.z).getType() != target.material) return false;
        double half = .31;
        return feet.getX() + half > target.x && feet.getX() - half < target.x + 1
                && feet.getZ() + half > target.z && feet.getZ() - half < target.z + 1;
    }

    private boolean startAreaClear(Block center) {
        int radius = clamp(config().getInt("start.clearance.horizontal-radius", 1), 0, 4);
        int below = clamp(config().getInt("start.clearance.blocks-below", 1), 0, 8);
        int above = clamp(config().getInt("start.clearance.blocks-above", 3), 2, 8);
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            for (int y = -below; y <= above; y++) if (!center.getRelative(x, y, z).getType().isAir()) return false;
        return true;
    }

    private boolean landingClear(Block target) {
        if (!target.getType().isAir()) return false;
        int headroom = clamp(config().getInt("validator.landing-headroom", 2), 2, 4);
        for (int y = 1; y <= headroom; y++) if (!target.getRelative(0, y, 0).getType().isAir()) return false;
        return true;
    }

    private boolean insideBorder(Location location, double margin) {
        World world = location.getWorld(); if (world == null) return false;
        WorldBorder border = world.getWorldBorder(); Location center = border.getCenter();
        double usable = Math.max(0, border.getSize() / 2 - Math.max(0, margin));
        double x = location.getBlockX() + .5, z = location.getBlockZ() + .5;
        return border.isInside(location) && Math.abs(x - center.getX()) <= usable && Math.abs(z - center.getZ()) <= usable;
    }

    private double borderMargin() { return clamp(config().getDouble("border.safety-margin", 32), 0, 10_000); }

    private Vector steerInward(Placed base, Vector heading) {
        World world = world(base.world); if (world == null) return heading;
        Location center = world.getWorldBorder().getCenter();
        Vector inward = new Vector(center.getX() - base.x - .5, 0, center.getZ() - base.z - .5);
        if (inward.lengthSquared() < .0001) return heading;
        return rotateToward(heading, inward.normalize(), Math.toRadians(clamp(
                config().getDouble("border.inward-turn-degrees", 35), 1, 90)));
    }

    private boolean farFromOtherRuns(Location candidate, double minimum, Session own) {
        for (Session other : sessions.values()) {
            if (other == own || !other.current.world.equals(candidate.getWorld().getName())) continue;
            if (distance(candidate.getX(), candidate.getZ(), other.current.x + .5, other.current.z + .5) < minimum) return false;
            for (Step step : other.upcoming) {
                if (distance(candidate.getX(), candidate.getZ(), step.block.x + .5, step.block.z + .5) < minimum) return false;
            }
        }
        return true;
    }

    private Difficulty difficultyAt(int score) {
        ConfigurationSection root = config().getConfigurationSection("difficulty.stages");
        List<Stage> stages = new ArrayList<>();
        if (root != null) for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name); if (section == null) continue;
            stages.add(new Stage(section.getInt("from-score", 0),
                    weights(section.getConfigurationSection("horizontal"), Map.of(1, 70d, 2, 30d)),
                    weights(section.getConfigurationSection("vertical"), Map.of(0, 100d)),
                    clamp(section.getDouble("diagonal-chance", 20), 0, 100),
                    clamp(section.getDouble("turn-chance", 10), 0, 100),
                    clamp(section.getDouble("max-turn-degrees", 15), 0, 60)));
        }
        if (stages.isEmpty()) stages.add(new Stage(0, Map.of(1, 70d, 2, 30d), Map.of(0, 100d), 20, 10, 15));
        stages.sort(Comparator.comparingInt(Stage::score));
        Stage low = stages.getFirst(), high = low;
        for (Stage stage : stages) {
            if (stage.score <= score) low = stage;
            if (stage.score > score) { high = stage; break; }
            high = low;
        }
        if (low == high) return Difficulty.from(low);
        double p = (score - low.score) / (double) (high.score - low.score);
        return new Difficulty(interpolate(low.horizontal, high.horizontal, p), interpolate(low.vertical, high.vertical, p),
                lerp(low.diagonalChance, high.diagonalChance, p), lerp(low.turnChance, high.turnChance, p), lerp(low.maxTurn, high.maxTurn, p));
    }

    private Map<Integer, Double> weights(ConfigurationSection section, Map<Integer, Double> fallback) {
        if (section == null) return new LinkedHashMap<>(fallback);
        Map<Integer, Double> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) try {
            double value = section.getDouble(key); if (value > 0) values.put(Integer.parseInt(key), value);
        } catch (NumberFormatException ignored) { novo.getLogger().warning("Ungültiger Jump'n'Run-Difficulty-Wert: " + key); }
        return values.isEmpty() ? new LinkedHashMap<>(fallback) : values;
    }

    private Map<Integer, Double> interpolate(Map<Integer, Double> from, Map<Integer, Double> to, double p) {
        Set<Integer> keys = new TreeSet<>(from.keySet()); keys.addAll(to.keySet());
        Map<Integer, Double> result = new LinkedHashMap<>();
        for (int key : keys) { double value = lerp(from.getOrDefault(key, 0d), to.getOrDefault(key, 0d), p); if (value > 0) result.put(key, value); }
        return result;
    }

    private int weighted(Map<Integer, Double> weights, int fallback) {
        double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return fallback;
        double pick = random.nextDouble() * total;
        for (Map.Entry<Integer, Double> entry : weights.entrySet()) { pick -= entry.getValue(); if (pick <= 0) return entry.getKey(); }
        return fallback;
    }

    private Material randomMaterial() {
        List<Material> valid = new ArrayList<>();
        for (String raw : config().getStringList("blocks.materials")) {
            Material material = Material.matchMaterial(raw);
            if (material != null && material.isBlock() && material.isSolid() && !isPressurePlate(material)) valid.add(material);
            else if (warnedMaterials.add(String.valueOf(raw))) novo.getLogger().warning("Ungültiges Jump'n'Run-Blockmaterial: " + raw);
        }
        return valid.isEmpty() ? null : valid.get(random.nextInt(valid.size()));
    }

    private void playSound(Player player, String path) {
        if (!config().getBoolean(path + ".enabled", true)) return;
        String raw = config().getString(path + ".sound", ""); if (raw == null || raw.isBlank()) return;
        Sound sound = resolveSound(raw);
        if (sound == null) {
            if (warnedSounds.add(path + raw)) novo.getLogger().warning("Ungültiger Jump'n'Run-Sound: " + raw);
            return;
        }
        player.playSound(player, sound, SoundCategory.PLAYERS,
                (float) clamp(config().getDouble(path + ".volume", .7), 0, 10),
                (float) clamp(config().getDouble(path + ".pitch", 1), .5, 2));
    }

    private Sound resolveSound(String raw) {
        String normalized = raw.trim();
        NamespacedKey exactKey = normalized.contains(":")
                ? NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT))
                : NamespacedKey.fromString("minecraft:" + normalized.toLowerCase(Locale.ROOT));
        Sound sound = exactKey == null ? null : Registry.SOUNDS.get(exactKey);
        if (sound != null) return sound;
        try {
            // Bukkit exposes the familiar enum-style names as public Sound fields.
            Object value = Sound.class.getField(normalized.toUpperCase(Locale.ROOT)).get(null);
            return value instanceof Sound resolved ? resolved : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private void finish(Player player, Session session, FinishReason reason) {
        if (!sessions.remove(player.getUniqueId(), session)) return;
        internalTeleports.remove(player.getUniqueId());
        int previousBest = highscore(player);
        int best = storeHighscore(player, session.score);
        cleanup(session);
        if (reason == FinishReason.FALL) { playSound(player, "sounds.fail"); send(player, "messages.failed", runValues(session, best)); returnToSpawn(player); }
        else if (reason == FinishReason.TECHNICAL) { send(player, "messages.technical-stop", runValues(session, best)); returnToSpawn(player); }
        else if (reason == FinishReason.EXTERNAL) send(player, "messages.external-stop", runValues(session, best));
        else if (reason == FinishReason.DEATH) send(player, "messages.death-stop", runValues(session, best));
        if (session.score > previousBest && best >= session.score
                && reason != FinishReason.DISCONNECT && player.isOnline()) {
            send(player, "messages.new-highscore", runValues(session, best));
        }
    }

    private void returnToSpawn(Player player) {
        if (!config().getBoolean("fail.return-to-spawn", true) || !player.isOnline()) return;
        Location spawn = novo.getSmpSpawnLocation(); if (spawn == null) spawn = player.getWorld().getSpawnLocation();
        internalTeleports.add(player.getUniqueId());
        player.teleportAsync(spawn).whenComplete((success, error) -> Bukkit.getScheduler().runTask(novo,
                () -> internalTeleports.remove(player.getUniqueId())));
    }

    private int highscore(Player player) {
        UUID playerId = player.getUniqueId();
        LeaderboardEntry cached = highscoreCache.get(playerId);
        int result = cached == null ? 0 : cached.score;

        // Compatibility with the briefly used player-PDC storage. Once imported,
        // the SQL value is the sole active source and the old marker is removed.
        Integer legacy = player.getPersistentDataContainer().get(highscoreKey, PersistentDataType.INTEGER);
        if (legacy != null && legacy > result) {
            try {
                result = highscoreRepository.saveIfHigher(playerId, player.getName(), legacy);
                highscoreCache.put(playerId, new LeaderboardEntry(playerId, player.getName(), result));
                refreshLeaderboard();
            } catch (SQLException exception) {
                novo.getLogger().warning("Alter Jump'n'Run-Highscore von " + player.getName()
                        + " konnte nicht migriert werden: " + exception.getMessage());
                return result;
            }
        } else if (cached != null && !player.getName().equals(cached.name)) {
            highscoreCache.put(playerId, new LeaderboardEntry(playerId, player.getName(), cached.score));
        }
        if (legacy != null && result >= legacy) {
            player.getPersistentDataContainer().remove(highscoreKey);
            player.saveData();
        }
        return result;
    }

    private int storeHighscore(Player player, int score) {
        int old = highscore(player);
        if (score <= old) return old;
        try {
            int persisted = highscoreRepository.saveIfHigher(player.getUniqueId(), player.getName(), score);
            highscoreCache.put(player.getUniqueId(), new LeaderboardEntry(
                    player.getUniqueId(), player.getName(), persisted));
            refreshLeaderboard();
            return persisted;
        } catch (SQLException exception) {
            novo.getLogger().severe("Jump'n'Run-Highscore von " + player.getName()
                    + " konnte nicht gespeichert werden: " + exception.getMessage());
            return old;
        }
    }

    private List<LeaderboardEntry> leaderboardEntries() {
        List<LeaderboardEntry> entries = new ArrayList<>(highscoreCache.values());
        entries.sort(Comparator.comparingInt(LeaderboardEntry::score).reversed()
                .thenComparing(LeaderboardEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.playerId().toString()));
        return List.copyOf(entries);
    }

    private void migrateYamlHighscores() throws SQLException {
        ConfigurationSection root = config().getConfigurationSection("highscores");
        if (root == null) return;
        int migrated = 0;
        for (String rawId : root.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(rawId);
                String path = "highscores." + rawId;
                int yamlScore = config().isInt(path)
                        ? Math.max(0, config().getInt(path))
                        : Math.max(0, config().getInt(path + ".score", 0));
                if (yamlScore <= 0) continue;
                LeaderboardEntry current = highscoreCache.get(playerId);
                if (current != null && current.score >= yamlScore) continue;
                String fallback = current == null ? rawId.substring(0, 8) : current.name;
                String name = config().getString(path + ".name", fallback);
                int persisted = highscoreRepository.saveIfHigher(playerId, name, yamlScore);
                highscoreCache.put(playerId, new LeaderboardEntry(playerId, name, persisted));
                migrated++;
            } catch (IllegalArgumentException ignored) {
                novo.getLogger().warning("Ungültige UUID in jumpnrun.yml/highscores: " + rawId);
            }
        }
        config().set("highscores", null);
        novo.configs().jumpnrunFile().saveWithDefaults();
        novo.getLogger().info("Einmalige Jump'n'Run-YAML-Migration abgeschlossen: "
                + migrated + " Highscores in player_stats übernommen; YAML-Spielerdaten entfernt.");
    }

    private void refreshLeaderboard() {
        HologramManager holograms = holograms();
        if (holograms == null) return;
        if (!config().getBoolean("leaderboard.enabled", true)) {
            holograms.delete(LEADERBOARD_HOLOGRAM_ID);
            return;
        }
        String worldName = config().getString("trigger.world", "");
        World world = world(worldName);
        if (world == null) {
            holograms.delete(LEADERBOARD_HOLOGRAM_ID);
            return;
        }
        Location location = new Location(world,
                config().getInt("trigger.x") + .5 + config().getDouble("leaderboard.offset.x", 0),
                config().getInt("trigger.y") + config().getDouble("leaderboard.offset.y", 1.5),
                config().getInt("trigger.z") + .5 + config().getDouble("leaderboard.offset.z", 0));
        int maxEntries = clamp(config().getInt("leaderboard.max-entries", 10), 1, 50);
        List<LeaderboardEntry> entries = leaderboardEntries();
        List<String> lines = new ArrayList<>();
        String title = config().getString("leaderboard.title", "");
        if (title != null && !title.isBlank()) lines.add(title);
        String format = config().getString("leaderboard.line-format", "");
        int shown = Math.min(maxEntries, entries.size());
        for (int index = 0; index < shown; index++) {
            LeaderboardEntry entry = entries.get(index);
            lines.add(replace(Objects.requireNonNullElse(format, ""), Map.of(
                    "%rank%", String.valueOf(index + 1),
                    "%player%", entry.name,
                    "%score%", String.valueOf(entry.score))));
        }
        if (shown == 0) lines.add(config().getString("leaderboard.empty-line", ""));
        lines.removeIf(line -> line == null || line.isBlank());
        if (lines.isEmpty()) {
            holograms.delete(LEADERBOARD_HOLOGRAM_ID);
            return;
        }
        holograms.upsertStatic(LEADERBOARD_HOLOGRAM_ID, location, lines,
                clamp(config().getDouble("leaderboard.display.line-spacing", .3), .1, 2),
                clamp(config().getInt("leaderboard.display.line-width", 240), 40, 2_000),
                clamp(config().getDouble("leaderboard.display.view-range", 48), 1, 256),
                config().getBoolean("leaderboard.display.shadowed", true),
                config().getBoolean("leaderboard.display.see-through", false),
                config().getBoolean("leaderboard.display.background", false),
                config().getString("leaderboard.display.billboard", "CENTER"),
                clamp(config().getDouble("leaderboard.display.scale", 1), .1, 10));
    }

    private void cleanup(Session session) {
        remove(session.current);
        session.upcoming.forEach(step -> remove(step.block));
        session.upcoming.clear();
    }
    private void remove(Placed placed) {
        if (placed == null) return; World world = world(placed.world);
        if (world == null || !world.isChunkLoaded(placed.x >> 4, placed.z >> 4)) return;
        Block block = world.getBlockAt(placed.x, placed.y, placed.z);
        if (block.getType() == placed.material) block.setType(Material.AIR, false);
    }
    private Placed place(Block block, Material material) {
        block.setType(material, false); return new Placed(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), material);
    }
    private boolean isGeneratedBlock(Block block) {
        String key = blockKey(block);
        return sessions.values().stream().anyMatch(session -> session.current.key().equals(key)
                || session.upcoming.stream().anyMatch(step -> step.block.key().equals(key)));
    }
    private void remember(Session session, Placed block) {
        session.history.addLast(new Position(block.x, block.y, block.z)); session.visited.add(block.key());
        int limit = clamp(config().getInt("generator.history-limit", 128), 8, 2048);
        while (session.history.size() > limit) { Position old = session.history.removeFirst(); session.visited.remove(session.current.world + ";" + old.x + ";" + old.y + ";" + old.z); }
    }

    private boolean isTrigger(Block block) {
        String world = config().getString("trigger.world", "");
        Material material = Material.matchMaterial(config().getString("trigger.material", "STONE_PRESSURE_PLATE"));
        return config().getBoolean("enabled", true) && world != null && !world.isBlank() && material != null
                && block.getWorld().getName().equals(world) && block.getX() == config().getInt("trigger.x")
                && block.getY() == config().getInt("trigger.y") && block.getZ() == config().getInt("trigger.z") && block.getType() == material;
    }

    private boolean isTriggerLocation(Location location) {
        return location != null && location.getWorld() != null && isTrigger(location.getBlock());
    }

    private void showInfo(Player player) {
        refreshLeaderboard();
        String name = config().getString("trigger.world", ""); World world = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
        Block block = world == null ? null : world.getBlockAt(config().getInt("trigger.x"), config().getInt("trigger.y"), config().getInt("trigger.z"));
        Material expected = Material.matchMaterial(config().getString("trigger.material", "STONE_PRESSURE_PLATE"));
        boolean valid = block != null && expected != null && block.getType() == expected;
        Map<String, String> values = new HashMap<>();
        String notSet = config().getString("messages.not-set-value", "");
        values.put("%world%", name == null || name.isBlank() ? notSet : name);
        values.put("%x%", String.valueOf(config().getInt("trigger.x"))); values.put("%y%", String.valueOf(config().getInt("trigger.y")));
        values.put("%z%", String.valueOf(config().getInt("trigger.z"))); values.put("%material%", expected == null ? notSet : expected.name());
        values.put("%valid%", config().getString(valid ? "messages.valid-value" : "messages.invalid-value", ""));
        values.put("%active_runs%", String.valueOf(sessions.size())); values.put("%highscore%", String.valueOf(highscore(player)));
        values.put("%leaderboard_enabled%", config().getString(
                config().getBoolean("leaderboard.enabled", true) ? "messages.valid-value" : "messages.invalid-value", ""));
        double leaderboardX = config().getInt("trigger.x") + .5 + config().getDouble("leaderboard.offset.x", 0);
        double leaderboardY = config().getInt("trigger.y") + config().getDouble("leaderboard.offset.y", 1.5);
        double leaderboardZ = config().getInt("trigger.z") + .5 + config().getDouble("leaderboard.offset.z", 0);
        values.put("%leaderboard_world%", world == null ? notSet : world.getName());
        values.put("%leaderboard_x%", world == null ? notSet : String.format(Locale.ROOT, "%.1f", leaderboardX));
        values.put("%leaderboard_y%", world == null ? notSet : String.format(Locale.ROOT, "%.1f", leaderboardY));
        values.put("%leaderboard_z%", world == null ? notSet : String.format(Locale.ROOT, "%.1f", leaderboardZ));
        values.put("%leaderboard_entries%", String.valueOf(Math.min(
                clamp(config().getInt("leaderboard.max-entries", 10), 1, 50), leaderboardEntries().size())));
        HologramManager holograms = holograms();
        values.put("%leaderboard_active%", config().getString(
                holograms != null && holograms.isSpawned(LEADERBOARD_HOLOGRAM_ID)
                        ? "messages.valid-value" : "messages.invalid-value", ""));
        values.put("%leaderboard_id%", LEADERBOARD_HOLOGRAM_ID);
        values.put("%visible_platforms%", String.valueOf(clamp(config().getInt("generator.visible-platforms", 3), 2, 5)));
        sendLines(player, "messages.check", values);
    }

    private Map<String, String> triggerValues(Block block) {
        return Map.of("%world%", block.getWorld().getName(), "%x%", String.valueOf(block.getX()), "%y%", String.valueOf(block.getY()),
                "%z%", String.valueOf(block.getZ()), "%material%", block.getType().name());
    }
    private Map<String, String> runValues(Session session) { return runValues(session, Math.max(session.bestAtStart, session.score)); }
    private Map<String, String> runValues(Session session, int best) { return Map.of("%score%", String.valueOf(session.score), "%highscore%", String.valueOf(best)); }

    private void refreshActionbars() {
        if (!config().getBoolean("actionbar.enabled", true)) return;
        for (Session session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player != null && player.isOnline() && !session.teleporting) showActionbar(player, session);
        }
    }
    private void showActionbar(Player player, Session session) {
        if (!config().getBoolean("actionbar.enabled", true)) return;
        String text = config().getString("actionbar.text", "");
        if (text != null && !text.isBlank()) player.sendActionBar(miniMessage.deserialize(replace(text, runValues(session))));
    }
    private void send(CommandSender sender, String path, Map<String, String> values) {
        String message = config().getString(path, ""); if (message != null && !message.isBlank()) sender.sendRichMessage(replace(message, values));
    }
    private void sendLines(CommandSender sender, String path, Map<String, String> values) {
        List<String> lines = config().getStringList(path);
        if (lines.isEmpty()) { send(sender, path, values); return; }
        lines.forEach(line -> sender.sendRichMessage(replace(line, values)));
    }
    private String replace(String text, Map<String, String> values) {
        text = text.replace("%prefix%", Objects.requireNonNullElse(config().getString("messages.prefix"), ""));
        for (Map.Entry<String, String> entry : values.entrySet()) text = text.replace(entry.getKey(), miniMessage.escapeTags(Objects.toString(entry.getValue(), "")));
        return text;
    }

    private Location standingLocation(Placed block, Vector heading, float pitch) {
        Location result = new Location(world(block.world), block.x + .5, block.y + 1, block.z + .5);
        result.setYaw((float) Math.toDegrees(Math.atan2(-heading.getX(), heading.getZ()))); result.setPitch(clamp(pitch, -45, 45)); return result;
    }
    private Vector randomHeading() { double angle = random.nextDouble() * Math.PI * 2; return new Vector(Math.cos(angle), 0, Math.sin(angle)).normalize(); }
    private Vector rotate(Vector vector, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        return new Vector(vector.getX() * cos - vector.getZ() * sin, 0, vector.getX() * sin + vector.getZ() * cos).normalize();
    }
    private Vector rotateToward(Vector from, Vector to, double max) {
        double a = Math.atan2(from.getZ(), from.getX()), b = Math.atan2(to.getZ(), to.getX());
        double difference = Math.atan2(Math.sin(b - a), Math.cos(b - a)); return rotate(from, clamp(difference, -max, max));
    }

    private FileConfiguration config() { return novo.configs().jumpnrun(); }
    private HologramManager holograms() { return hologramSupplier == null ? null : hologramSupplier.get(); }
    private World world(String name) { return name == null ? null : Bukkit.getWorld(name); }
    private String blockKey(Block block) { return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ(); }
    private boolean isPressurePlate(Material material) { return material != null && material.name().endsWith("PRESSURE_PLATE"); }
    private boolean roll(double chance) { return random.nextDouble() * 100 < chance; }
    private int randomBetween(int min, int max) { return max <= min ? min : random.nextInt(max - min + 1) + min; }
    private double distance(double x1, double z1, double x2, double z2) { return Math.hypot(x1 - x2, z1 - z2); }
    private int floor(double value) { return (int) Math.floor(value); }
    private double lerp(double from, double to, double p) { return from + (to - from) * p; }
    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private enum FinishReason { FALL, DISCONNECT, DEATH, EXTERNAL, TECHNICAL }
    private enum Result { VALID, AIR_GAP, IMPOSSIBLE, BLOCKED, BORDER, OTHER_RUN, SELF_INTERSECTION }
    private record Placed(String world, int x, int y, int z, Material material) { String key() { return world + ";" + x + ";" + y + ";" + z; } }
    private record Position(int x, int y, int z) { }
    private record Candidate(int x, int y, int z, Vector heading) { }
    private record Step(Placed block, Vector heading) { }
    private record LeaderboardEntry(UUID playerId, String name, int score) { }
    private record Stage(int score, Map<Integer, Double> horizontal, Map<Integer, Double> vertical,
                         double diagonalChance, double turnChance, double maxTurn) { }
    private record Difficulty(Map<Integer, Double> horizontal, Map<Integer, Double> vertical,
                              double diagonalChance, double turnChance, double maxTurn) {
        static Difficulty from(Stage s) { return new Difficulty(s.horizontal, s.vertical, s.diagonalChance, s.turnChance, s.maxTurn); }
    }
    private static final class Session {
        final UUID playerId; final int bestAtStart; final Deque<Position> history = new ArrayDeque<>(); final Set<String> visited = new HashSet<>();
        final Deque<Step> upcoming = new ArrayDeque<>();
        Placed current; Vector heading; int score; boolean teleporting;
        Session(UUID playerId, Placed current, Vector heading, int best) { this.playerId = playerId; this.current = current; this.heading = heading; bestAtStart = best; }
    }
}
