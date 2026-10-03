package de.walahi.novosmp.moderation;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.smpcore.commands.*;
import de.walahi.smpcore.moderation.*;
import de.walahi.smpcore.punishments.*;
import de.walahi.smpcore.ranks.RankManager;
import net.kyori.adventure.text.Component;
import net.luckperms.api.*;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.time.*;
import java.util.*;
import java.util.logging.*;

/** Real punishment SQL and LuckPerms persistence; captures outgoing Velocity payloads on Paper. */
public final class OfflineBanPaperProbe extends JavaPlugin {
    private NovoSMPPlugin smp;
    private BanService bans;
    private LuckPerms lp;
    private Server original;
    private Actor owner, staff, superior, carrier;
    private final List<Actor> actors = new ArrayList<>();
    private final List<Actor> targets = new ArrayList<>();
    private final List<Packet> packets = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private Handler logCapture;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                setup();
                command(false, true); command(false, false);
                command(true, true); command(true, false);
                hierarchy();
                require(bans.ban(owner.player, owner.id, owner.name, null, "self") == BanService.Result.SELF, "self-ban unchanged");
                int sent = packets.size();
                Actor consoleTarget = actor("ConsoleOffline", false);
                require(bans.ban(Bukkit.getConsoleSender(), consoleTarget.id, consoleTarget.name, null, "console") == BanService.Result.SUCCESS, "console offline ban");
                require(packets.size() == sent + 1 && packets.getLast().carrier.equals(owner.id), "console uses existing first-online carrier fallback");
                login(consoleTarget, true);
                noCarrier();
                unknown();
                unban(targets.stream().filter(t -> t.kicksExpected == 0).findFirst().orElseThrow());
                setServer(original);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    try {
                        for (Actor target : targets) {
                            require(target.kicks == target.kicksExpected, "online targets kicked once, offline targets never kicked");
                            if (target.kicks > 0) require(sameScreen(target.screen, active(target)), "existing ban screen on fallback kick");
                        }
                        getLogger().info("ONLINE_OFFLINE_BAN_PAPER_SQL_LUCKPERMS=PASS");
                        finish(null);
                    } catch (Throwable failure) { finish(failure); }
                }, 12L);
            } catch (Throwable failure) { finish(failure); }
        }, 40L);
    }

    @SuppressWarnings("unchecked")
    private void setup() throws Exception {
        smp = (NovoSMPPlugin) Bukkit.getPluginManager().getPlugin("NovoSMP");
        require(smp != null && smp.isEnabled(), "production enabled");
        bans = smp.services().bans(); lp = LuckPermsProvider.get(); original = Bukkit.getServer();
        Class.forName(OfflineBanPaperProbe.class.getName() + "$Packet", true, getClass().getClassLoader());
        for (String name : List.of("de.walahi.smpcore.network.NetworkManager$PayloadWriter",
                "de.walahi.smpcore.punishments.Punishment", "de.walahi.smpcore.punishments.PunishmentResult",
                "de.walahi.smpcore.punishments.PunishmentFormatter", "de.walahi.smpcore.api.event.PunishmentCreateEvent",
                "de.walahi.smpcore.punishments.PunishmentType", "de.walahi.smpcore.messages.MessageChannel",
                "de.walahi.smpcore.punishments.DurationParser",
                "de.walahi.smpcore.api.event.PunishmentRevokeEvent", "de.walahi.smpcore.api.event.ActionSource",
                "de.walahi.smpcore.commands.BanCommand$1", "de.walahi.smpcore.commands.TempBanCommand$1",
                "de.walahi.smpcore.commands.UnbanCommand$1"))
            Class.forName(name, true, smp.getClass().getClassLoader());
        List<RankManager.RankDefinition> ranks = (List<RankManager.RankDefinition>) field(smp.getRankManager(), "ranks");
        var high = ranks.getFirst();
        var low = ranks.stream().filter(rank -> rank.priority() > high.priority()
                && rank.priority() < smp.getRankManager().fallbackPlayerRank().priority()).findFirst().orElseThrow();
        owner = actor("BanOwner", true); staff = actor("BanStaff", true);
        superior = actor("BanSuperior", true); carrier = actor("BanCarrier", true);
        group(owner, high.group()); group(staff, low.group()); group(superior, high.group());
        require(smp.getRankManager().resolve(owner.id).priority() < smp.getRankManager().resolve(staff.id).priority(), "real configured staff priorities");
        Server wrapper = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            if (method.getName().equals("getOnlinePlayers")) return actors.stream().filter(a -> a.online).map(a -> a.player).toList();
            if (method.getName().equals("createProfile") && args.length == 2)
                for (Actor actor : actors) if (actor.id.equals(args[0])) return actor.profile;
            if (method.getName().equals("getPlayer") || method.getName().equals("getPlayerExact")) {
                for (Actor actor : actors) if (actor.online && (actor.id.equals(args[0]) || actor.name.equals(args[0]))) return actor.player;
                return null;
            }
            if (method.getName().equals("getOfflinePlayer")) {
                for (Actor actor : actors) if (actor.id.equals(args[0]) || actor.name.equals(args[0])) return actor.player;
                return Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                        (p, m, a) -> m.getName().equals("hasPlayedBefore") || m.getName().equals("isOnline") ? false : empty(m));
            }
            try { return method.invoke(original, args); } catch (InvocationTargetException exception) { throw exception.getCause(); }
        });
        logCapture = new Handler() {
            public void publish(LogRecord record) { if (record.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(record.getMessage()); }
            public void flush() { } public void close() { }
        };
        smp.getLogger().addHandler(logCapture);
        setServer(wrapper);
    }

    private void command(boolean temporary, boolean online) throws Exception {
        Actor target = actor((temporary ? "Temp" : "Perm") + (online ? "Online" : "Offline"), online);
        target.kicksExpected = online ? 1 : 0; targets.add(target);
        Instant started = Instant.now(); int sent = packets.size();
        if (temporary) new TempBanCommand(smp, bans).onCommand(owner.player, null, "tempban", new String[]{target.name, "1h", "probe reason"});
        else new BanCommand(smp, bans).onCommand(owner.player, null, "ban", new String[]{target.name, "probe reason"});
        Punishment punishment = active(target);
        require(punishment.playerUuid().equals(target.id) && punishment.permanent() == !temporary
                && smp.services().punishments().history(target.id).size() == 1, "exact UUID and one SQL punishment");
        if (temporary) require(Math.abs(Duration.between(started.plusSeconds(3600), punishment.expiresAt()).toMillis()) < 3000,
                "one-hour expiry persisted");
        require(packets.size() == sent + 1, "one BAN_ADD for online and offline commands");
        Packet packet = packets.getLast();
        require(packet.action.equals("BAN_ADD") && packet.target.equals(target.id), "BAN_ADD encodes exact UUID");
        require(packet.expires == (temporary ? punishment.expiresAt().toEpochMilli() : -1L),
                "expiry sent=" + packet.expires + " persisted=" + punishment.expiresAt());
        String expected = bans.buildBanMiniMessage(punishment);
        String remaining = PunishmentFormatter.remaining(punishment, Instant.now());
        // A clock tick between construction and assertion may turn exactly one hour into 59 minutes.
        require(packet.message.equals(expected) || temporary && packet.message.equals(expected.replace(remaining, "1 Stunde")),
                "original ban screen and correct remaining time encoded");
        require(packet.carrier.equals(online ? target.id : owner.id), "target or executing staff is preferred carrier");
        login(target, true);
        require(bans.ban(owner.player, target.id, target.name, online ? target.player : null, "duplicate") == BanService.Result.ALREADY_BANNED
                && packets.size() == sent + 1 && smp.services().punishments().history(target.id).size() == 1, "no duplicate ban or BAN_ADD");
        getLogger().info((temporary ? "TEMP" : "PERMANENT") + (online ? "_ONLINE" : "_OFFLINE") + "_SQL_UUID_NETWORK_LOGIN=PASS");
    }

    private void hierarchy() throws Exception {
        int sent = packets.size();
        require(bans.ban(staff.player, superior.id, superior.name, superior.player, "deny") == BanService.Result.HIERARCHY, "higher rank online denied");
        superior.online = false;
        User saved = lp.getUserManager().getUser(superior.id);
        lp.getUserManager().cleanupUser(saved);
        // LP defers API cleanup. Force eviction only in this isolated fixture to prove a real cold load.
        Field handle = lp.getUserManager().getClass().getSuperclass().getDeclaredField("handle");
        handle.setAccessible(true);
        Object internal = handle.get(lp.getUserManager());
        internal.getClass().getMethod("unload", Object.class).invoke(internal, superior.id);
        require(lp.getUserManager().getUser(superior.id) == null, "higher offline rank really evicted from LuckPerms cache");
        require(bans.tempBan(staff.player, superior.id, superior.name, null, "deny", Duration.ofHours(1)) == BanService.Result.HIERARCHY,
                "persisted offline rank loaded and denied");
        require(smp.services().punishments().history(superior.id).isEmpty() && packets.size() == sent, "hierarchy denial has no persistence or network effects");
        getLogger().info("REAL_PERSISTED_UNLOADED_OFFLINE_LUCKPERMS_HIERARCHY=PASS");
    }

    private void noCarrier() {
        Map<Actor, Boolean> online = new HashMap<>();
        actors.forEach(actor -> { online.put(actor, actor.online); actor.online = false; });
        Actor target = actor("NoCarrier", false); int sent = packets.size();
        require(bans.ban(Bukkit.getConsoleSender(), target.id, target.name, null, "no carrier") == BanService.Result.SUCCESS, "no-carrier ban stays successful");
        require(packets.size() == sent && warnings.stream().anyMatch(warning -> warning.contains(target.id.toString())
                && warning.contains("Velocity-Ban-Sync")), "missing immediate sync logged");
        require(active(target).active(), "ban not rolled back"); login(target, true);
        online.forEach((actor, state) -> actor.online = state);
        getLogger().info("NO_CARRIER_PERSISTENCE_WARNING_LOGIN=PASS");
    }

    private void unban(Actor target) {
        int sent = packets.size();
        new UnbanCommand(smp, bans).onCommand(owner.player, null, "unban", new String[]{target.name});
        require(smp.services().punishments().active(target.id, PunishmentType.BAN).isEmpty() && packets.size() == sent + 1
                && packets.getLast().action.equals("BAN_REMOVE") && packets.getLast().target.equals(target.id), "offline unban SQL and network removal");
        login(target, false);
    }

    private void unknown() {
        int sent = packets.size();
        int messages = owner.messages.size();
        new BanCommand(smp, bans).onCommand(owner.player, null, "ban", new String[]{"NeverPlayed"});
        new TempBanCommand(smp, bans).onCommand(owner.player, null, "tempban", new String[]{"NeverPlayed", "1h"});
        require(packets.size() == sent && owner.messages.size() == messages + 2, "unknown names rejected by both existing commands");
    }

    @SuppressWarnings("deprecation")
    private void login(Actor target, boolean banned) {
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(target.name, InetAddress.getLoopbackAddress(), target.id);
        new BanLoginListener(smp.services().punishments(), bans).onPreLogin(event);
        require(event.getLoginResult() == (banned ? AsyncPlayerPreLoginEvent.Result.KICK_BANNED : AsyncPlayerPreLoginEvent.Result.ALLOWED), "persistent login protection");
        if (banned) require(sameScreen(event.kickMessage(), active(target)), "original persisted ban screen");
    }
    private boolean sameScreen(Component screen, Punishment punishment) {
        Component expected = bans.buildBanScreen(punishment);
        if (screen.equals(expected)) return true;
        if (punishment.permanent()) return false;
        var serializer = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        return serializer.serialize(screen).equals(serializer.serialize(expected)
                .replace(PunishmentFormatter.remaining(punishment, Instant.now()), "1 Stunde"));
    }
    private Punishment active(Actor actor) { return smp.services().punishments().active(actor.id, PunishmentType.BAN).orElseThrow(); }
    private void group(Actor actor, String group) {
        lp.getGroupManager().createAndLoadGroup(group).join();
        User user = lp.getUserManager().loadUser(actor.id).join();
        user.data().add(InheritanceNode.builder(group).build());
        lp.getUserManager().saveUser(user).join();
    }

    private Actor actor(String name, boolean online) {
        Actor actor = new Actor(); actor.name = name; actor.online = online;
        actor.profile = original.createProfile(actor.id, name);
        actor.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> actor.id; case "getName" -> actor.name;
                case "equals" -> proxy == args[0]; case "hashCode" -> actor.id.hashCode();
                case "isOnline" -> actor.online; case "hasPlayedBefore", "hasPermission" -> true;
                case "getWorld" -> Bukkit.getWorlds().getFirst();
                case "sendMessage" -> { if (args[0] instanceof Component message) actor.messages.add(message); yield null; }
                case "sendPluginMessage" -> {
                    require(args[1].equals("smpcore:network"), "existing channel");
                    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream((byte[]) args[2]))) {
                        String action = input.readUTF(); UUID target = UUID.fromString(input.readUTF());
                        long expires = action.equals("BAN_ADD") ? input.readLong() : -1L;
                        String message = action.equals("BAN_ADD") ? input.readUTF() : "";
                        packets.add(new Packet(action, target, expires, message, actor.id));
                        require(input.available() == 0, "unchanged payload format");
                    }
                    yield null;
                }
                case "kick" -> { actor.kicks++; actor.screen = (Component) args[0]; actor.online = false; yield null; }
                default -> empty(method);
            };
        });
        actors.add(actor); return actor;
    }
    private void finish(Throwable failure) {
        if (failure != null) getLogger().log(Level.SEVERE, "ONLINE_OFFLINE_BAN_PAPER_SQL_LUCKPERMS=FAIL", failure);
        try { if (original != null) setServer(original); } catch (Exception exception) { getLogger().log(Level.SEVERE, "restore server", exception); }
        if (logCapture != null) smp.getLogger().removeHandler(logCapture);
        Bukkit.shutdown();
    }
    private static Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void setServer(Server server) throws Exception { Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, server); }
    private static Object empty(Method method) {
        if (method.getReturnType() == boolean.class) return false;
        if (method.getReturnType() == int.class) return 0;
        if (method.getReturnType() == long.class) return 0L;
        if (method.getReturnType() == double.class) return 0.0;
        return null;
    }
    private static void require(boolean okay, String reason) { if (!okay) throw new AssertionError(reason); }
    private record Packet(String action, UUID target, long expires, String message, UUID carrier) { }
    private static final class Actor {
        UUID id = UUID.randomUUID(); String name; Player player; boolean online;
        com.destroystokyo.paper.profile.PlayerProfile profile;
        int kicks, kicksExpected; Component screen; List<Component> messages = new ArrayList<>();
    }
}
