package de.walahi.novosmp.king;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.duel.*;
import de.walahi.smpcore.services.EconomyService;
import de.walahi.smpcore.economy.EconomyOperationResult;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.gui.GuiButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;

/** Isolated Paper regression probe; never packaged into the production plugin. */
public final class KingDuelPaperProbe extends JavaPlugin {
    private NovoSMPPlugin smp;
    private DragonEggKingService kings;
    private DuelManager duels;
    private EconomyService economy;
    private Server original;
    private Actor challenger, king, stranger;
    private World world;
    private boolean finished;
    private final List<Actor> actors = new ArrayList<>();

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                setup();
                checkClick();
                checkManual();
                checkPreparingTimerCleanup();
                checkOfflineAndOwnership();
                checkRace(false);
                checkRace(true);
                DuelRequest automatic = pending();
                require(deadline(automatic) - System.currentTimeMillis() > 59_000L, "original 60-second deadline");
                getLogger().info("KING_AUTO_ACCEPT_60_SECONDS=RUNNING");
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    try {
                        assertStarted(automatic);
                        getLogger().info("KING_AUTO_ACCEPT_60_SECONDS=PASS");
                        finish(null);
                    } catch (Throwable failure) { finish(failure); }
                }, 1240L);
            } catch (Throwable failure) { finish(failure); }
        }, 40L);
    }

    @SuppressWarnings("unchecked")
    private void setup() throws Exception {
        smp = (NovoSMPPlugin) Bukkit.getPluginManager().getPlugin("NovoSMP");
        require(smp != null && smp.isEnabled(), "production enabled");
        kings = (DragonEggKingService) field(smp, "dragonEggKingService");
        duels = (DuelManager) field(smp, "duelManager");
        economy = (EconomyService) field(kings, "economy");
        original = Bukkit.getServer();
        world = Bukkit.getWorlds().getFirst();
        challenger = actor("KingChallenger");
        king = actor("KingTarget");
        stranger = actor("KingStranger");
        Server wrapper = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            if (method.getName().equals("getOnlinePlayers"))
                return actors.stream().filter(a -> a.online).map(a -> a.player).toList();
            if ((method.getName().equals("getPlayer") || method.getName().equals("getPlayerExact")) && args.length == 1) {
                for (Actor actor : actors)
                    if (actor.online && (actor.player.getUniqueId().equals(args[0])
                            || actor.player.getName().equalsIgnoreCase(String.valueOf(args[0])))) return actor.player;
            }
            return invoke(original, method, args);
        });
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        server.set(null, wrapper);
        Map<String, DuelMap> maps = (Map<String, DuelMap>) field(duels.config(), "maps");
        maps.clear();
        maps.put("king_probe", new DuelMap("king_probe", "King Probe", Material.GRASS_BLOCK, world.getName(),
                1000, 95, 1000, 1010, 105, 1010,
                new Location(world, 1002, 100, 1002), new Location(world, 1008, 100, 1008), "probe.schem"));
        ((Map<?, ?>) field(duels.config(), "mapInstances")).clear();
        ((Map<String, DuelKit>) field(duels.config(), "kits")).put("sword",
                new DuelKit("sword", "Sword", Material.IRON_SWORD, DuelLoadout.empty()));
        duels.config().raw().set("settings.arena-reset.before-match", false);
        duels.config().raw().set("settings.arena-reset.after-match", true);
        ((Set<?>) field(duels, "resetRequired")).clear();
        require(new DragonEggKingConfig(smp).acceptTimeoutSeconds() == 60, "timeout unchanged");
        require(economy.setBalance(challenger.player.getUniqueId(), 100_000L, "KING-PROBE",
                ActionContext.system(challenger.player.getUniqueId())) == EconomyOperationResult.SUCCESS, "test funds");
    }

    private Actor actor(String name) throws Exception {
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Object server = original.getClass().getMethod("getServer").invoke(original);
        Class<?> profileType = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(UUID.randomUUID(), name);
        Class<?> infoType = Class.forName("net.minecraft.server.level.ClientInformation");
        Class<?> ownerType = Class.forName("net.minecraft.server.level.ServerPlayer");
        Object owner = ownerType.getConstructor(Class.forName("net.minecraft.server.MinecraftServer"),
                Class.forName("net.minecraft.server.level.ServerLevel"), profileType, infoType)
                .newInstance(server, level, profile, infoType.getMethod("createDefault").invoke(null));
        Class<?> flowType = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Class<?> connectionType = Class.forName("net.minecraft.network.Connection");
        Object connection = connectionType.getConstructor(flowType).newInstance(flowType.getField("SERVERBOUND").get(null));
        Class<?> cookieType = Class.forName("net.minecraft.server.network.CommonListenerCookie");
        Object cookie = cookieType.getMethod("createInitial", profileType, boolean.class).invoke(null, profile, false);
        Object listener = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .getConstructor(Class.forName("net.minecraft.server.MinecraftServer"), connectionType, ownerType, cookieType)
                .newInstance(server, connection, owner, cookie);
        ownerType.getField("connection").set(owner, listener);
        Player actual = (Player) ownerType.getMethod("getBukkitEntity").invoke(owner);
        Actor actor = new Actor();
        actor.location = new Location(world, 0.5, 100, 0.5);
        actor.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            String operation = method.getName();
            if (operation.equals("isOnline")) return actor.online;
            if (operation.equals("equals")) return proxy == args[0];
            if (operation.equals("hashCode")) return actual.getUniqueId().hashCode();
            if (operation.equals("canSee")) return true;
            if (operation.equals("sendMessage")) {
                if (args != null) for (Object argument : args) if (argument instanceof Component component) actor.messages.add(component);
                return null;
            }
            if (operation.equals("sendActionBar") || operation.equals("hidePlayer") || operation.equals("showPlayer")
                    || operation.equals("sendPlayerListHeaderAndFooter")) return null;
            if (operation.equals("openInventory") && args[0] instanceof Inventory inventory) {
                actor.opened = inventory; return actual.getOpenInventory();
            }
            if (operation.equals("closeInventory")) { actor.opened = null; return null; }
            if (operation.equals("getLocation") && (args == null || args.length == 0)) return actor.location.clone();
            if (operation.equals("teleport") && args[0] instanceof Location location) {
                actor.location = location.clone(); actor.teleports++; return true;
            }
            return invoke(actual, method, args);
        });
        actors.add(actor);
        return actor;
    }

    private DuelRequest pending() throws Exception {
        challenger.online = king.online = true;
        clearMatch();
        challenger.messages.clear(); king.messages.clear();
        challenger.teleports = king.teleports = 0;
        require(kings.setHolder(king.player), "active king established");
        require(command(challenger, "/king duel " + king.player.getName()), "unchanged king command");
        require(duels.draft(challenger.player.getUniqueId()) != null, "specialized draft opened");
        duels.confirmDraft(challenger.player);
        DuelRequest request = duels.findRequest(king.player.getUniqueId(), challenger.player.getName());
        require(request != null && request.challenger().equals(challenger.player.getUniqueId())
                && request.target().equals(king.player.getUniqueId()), "correct participant UUIDs");
        return request;
    }

    private void checkClick() throws Exception {
        DuelRequest request = pending();
        String click = null;
        for (Component message : king.messages) {
            String found = clickCommand(message);
            if (found != null) click = found;
        }
        require(("/duel open " + request.id()).equals(click), "bound chat click command");
        require(command(king, "/duel requests"), "old click dispatched");
        require(king.messages.stream().anyMatch(c -> text(c).contains("nicht online")), "old requests-as-player error reproduced");
        getLogger().info("KING_OLD_CLICK_OFFLINE_ERROR=REPRODUCED playerLookup=requests");
        king.messages.clear();
        require(command(king, click), "chat command executed");
        require(king.opened != null, "request menu opened");
        Object gui = field(king.opened.getHolder(), "gui");
        @SuppressWarnings("unchecked") Map<Integer, GuiButton> buttons = (Map<Integer, GuiButton>) field(gui, "buttons");
        GuiButton accept = buttons.values().stream().filter(b -> b.item().getType() == Material.LIME_CONCRETE).findFirst().orElseThrow();
        accept.action().accept(null); // Actual existing accept button callback, no Minecraft client.
        assertStarted(request);
        require(king.messages.stream().noneMatch(c -> text(c).contains("nicht online")), "no false offline message");
        long before = economy.balance(challenger.player.getUniqueId());
        tick();
        require(economy.balance(challenger.player.getUniqueId()) == before, "no second debit");
        getLogger().info("KING_CLICK_MENU_ACCEPT=PASS command=" + click);
    }

    private void checkManual() throws Exception {
        DuelRequest request = pending();
        require(command(king, "/duel accept " + challenger.player.getName()), "existing manual command");
        assertStarted(request);
        require(Boolean.TRUE.equals(field(challenge(request), "accepted")), "auto-accept entry deactivated immediately");
        long before = economy.balance(challenger.player.getUniqueId());
        fieldObject(challenge(request), "autoAcceptAt").setLong(challenge(request), 0L);
        tick();
        require(economy.balance(challenger.player.getUniqueId()) == before && king.teleports == 1, "manual acceptance disables timer");
        getLogger().info("KING_MANUAL_ACCEPT_AND_TIMER_CLEANUP=PASS");
    }

    private void checkOfflineAndOwnership() throws Exception {
        DuelRequest request = pending();
        duels.accept(stranger.player, request);
        require(duels.findRequest(king.player.getUniqueId(), request.id().toString()) == request, "wrong target cannot consume request");
        challenger.online = false;
        command(king, "/duel accept " + request.id());
        require(!duels.inDuel(king.player.getUniqueId()), "offline challenger does not start");
        require(king.messages.stream().anyMatch(c -> text(c).contains("Herausforderer") && text(c).contains("online")), "existing offline challenger message");
        challenger.online = true;
        request = pending();
        king.online = false;
        for (var listener : duels.listeners()) {
            if (!listener.getClass().getSimpleName().equals("DuelSessionListener")) continue;
            Method quit = listener.getClass().getDeclaredMethod("onQuit", PlayerQuitEvent.class);
            quit.setAccessible(true);
            quit.invoke(listener, new PlayerQuitEvent(king.player, Component.empty()));
        }
        require(((Map<?, ?>) field(duels, "requests")).isEmpty() && ((Map<?, ?>) field(kings, "challenges")).isEmpty(), "king quit cleanup");
        king.online = true;
        getLogger().info("KING_OFFLINE_AND_TARGET_OWNERSHIP=PASS");
    }

    private void checkRace(boolean timerFirst) throws Exception {
        DuelRequest request = pending();
        fieldObject(challenge(request), "autoAcceptAt").setLong(challenge(request), 0L);
        long before = economy.balance(challenger.player.getUniqueId());
        if (timerFirst) {
            tick();
            // The normal duel command blocker now also rejects a new command.
            // Exercise an accept already queued before the timer at the central method.
            duels.accept(king.player, request);
        }
        else { command(king, "/duel accept " + challenger.player.getName()); tick(); }
        assertStarted(request);
        require(economy.balance(challenger.player.getUniqueId()) == before - 20_000L, "exactly one mandatory debit");
        require(king.teleports == 1 && challenger.teleports == 1, "exactly one teleport pair");
        getLogger().info("KING_ACCEPT_TIMER_RACE=PASS timerFirst=" + timerFirst);
    }

    private void checkPreparingTimerCleanup() throws Exception {
        DuelRequest request = pending();
        // The acceptance hook runs directly after the atomic pending claim,
        // before an asynchronous arena reset can reach beforeStart.
        ((Map<?, ?>) field(duels, "requests")).remove(request.id());
        kings.onRequestAccepted(request);
        require(!Boolean.TRUE.equals(field(challenge(request), "started")), "start has not happened yet");
        fieldObject(challenge(request), "autoAcceptAt").setLong(challenge(request), 0L);
        int messages = king.messages.size();
        tick();
        require(king.messages.size() == messages && king.teleports == 0, "accepted preparation never retries auto-accept");
        getLogger().info("KING_PREPARING_AUTO_ACCEPT_DISABLED=PASS");
    }

    private void assertStarted(DuelRequest request) throws Exception {
        require(duels.areOpponents(request.challenger(), request.target()), "same duel for both UUIDs");
        require(duels.findRequest(request.target(), request.id().toString()) == null, "pending request consumed");
        require(Boolean.TRUE.equals(field(challenge(request), "started")), "king match started");
        require(king.teleports == 1 && challenger.teleports == 1, "one teleport pair");
    }

    @SuppressWarnings("unchecked")
    private void clearMatch() throws Exception {
        Map<UUID, Object> matches = (Map<UUID, Object>) field(duels, "matchesByPlayer");
        for (Object match : new HashSet<>(matches.values())) {
            for (String taskName : new String[]{"timerTask", "countdownFreezeTask"}) {
                Object task = field(match, taskName);
                if (task instanceof org.bukkit.scheduler.BukkitTask scheduled) scheduled.cancel();
            }
            kings.onTechnicalAbort((DuelRequest) field(match, "request"));
        }
        matches.clear();
        ((Map<?, ?>) field(duels, "matchesByMap")).clear();
        ((Map<?, ?>) field(duels, "requests")).clear();
        ((Map<?, ?>) field(kings, "challenges")).clear();
    }

    private boolean command(Actor actor, String command) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(actor.player, command);
        Bukkit.getPluginManager().callEvent(event);
        require(!event.isCancelled(), "existing command blockers allow " + command);
        String[] words = command.substring(1).split(" ");
        return smp.getCommand(words[0]).execute(actor.player, words[0], Arrays.copyOfRange(words, 1, words.length));
    }

    private Object challenge(DuelRequest request) throws Exception { return ((Map<?, ?>) field(kings, "challenges")).get(request.id()); }
    private long deadline(DuelRequest request) throws Exception { return (long) field(challenge(request), "autoAcceptAt"); }
    private void tick() throws Exception {
        Method method = kings.getClass().getDeclaredMethod("tickChallenges"); method.setAccessible(true); method.invoke(kings);
    }

    private void finish(Throwable failure) {
        if (finished) return; finished = true;
        if (failure == null) getLogger().info("KING_DUEL_PROBE_RESULT=PASS");
        else getLogger().log(java.util.logging.Level.SEVERE, "KING_DUEL_PROBE_RESULT=FAIL", failure);
        try { clearMatch(); } catch (Exception ignored) { }
        if (original != null) try {
            Field server = Bukkit.class.getDeclaredField("server"); server.setAccessible(true); server.set(null, original);
        } catch (Exception ignored) { }
        Bukkit.shutdown();
    }

    private static String clickCommand(Component component) throws Exception {
        Object click = component.clickEvent();
        if (click != null && click.getClass().getMethod("action").invoke(click).toString().equalsIgnoreCase("run_command")) {
            try { return (String) click.getClass().getMethod("value").invoke(click); }
            catch (NoSuchMethodException ignored) {
                Object payload = click.getClass().getMethod("payload").invoke(click);
                return (String) Class.forName("net.kyori.adventure.text.event.ClickEvent$Payload$Text")
                        .getMethod("value").invoke(payload);
            }
        }
        for (Component child : component.children()) { String command = clickCommand(child); if (command != null) return command; }
        return null;
    }
    private static String text(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    private static Object field(Object object, String name) throws Exception { return fieldObject(object, name).get(object); }
    private static Field fieldObject(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object invoke(Object receiver, Method method, Object[] args) throws Throwable {
        try { return method.invoke(receiver, args); } catch (InvocationTargetException exception) { throw exception.getCause(); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private static final class Actor {
        Player player; boolean online = true; int teleports; Location location; Inventory opened;
        final List<Component> messages = new ArrayList<>();
    }
}

