package de.walahi.novosmp.bounty;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.combat.*;
import de.walahi.novosmp.feature.DeathMessageListener;
import de.walahi.smpcore.api.event.EconomyTransactionEvent;
import io.papermc.paper.event.entity.WaterBottleSplashEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.damage.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import java.lang.reflect.*;
import java.util.*;

/** Real Paper services and transactional SQL, with capturing player/server adapters. */
public final class BountyAnonymityPaperProbe extends JavaPlugin implements Listener {
    private NovoSMPPlugin smp;
    private BountyManager bounties;
    private Server original;
    private Actor killer, victim, observer, friend, revealed;
    private final List<Actor> actors = new ArrayList<>();
    private final List<EconomyTransactionEvent> deposits = new ArrayList<>();
    private final Map<String, Object> oldConfig = new HashMap<>();
    private int broadcasts, scenarios;
    private boolean endInvisibilityDuringPayout;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                setup();
                claim(false, ValidKillCause.NORMAL_PVP, true);
                killer.invisible = true;
                death();
                long withoutBounty = smp.economyBalance(killer.id);
                resetMessages(); deposits.clear();
                bounties.onValidKill(new ValidPlayerKillEvent(killer.id, killer.name, victim.id, victim.name, ValidKillCause.NORMAL_PVP, null));
                require(smp.economyBalance(killer.id) == withoutBounty && deposits.isEmpty()
                        && actors.stream().allMatch(a -> a.messages.isEmpty()), "invisible kill without bounty has no claim or payout");
                require(smp.invisibilityAnonymity().shouldAnonymize(observer.player, killer.player, true), "ordinary viewer is anonymous");
                claim(true, ValidKillCause.NORMAL_PVP, true);
                smp.friendManager().repository().accept(killer.id, killer.name, friend.id, friend.name);
                ((de.walahi.novosmp.friends.FriendStateCache) field(smp.friendManager(), "cache")).invalidatePair(killer.id, friend.id);
                require(!smp.invisibilityAnonymity().shouldAnonymize(friend.player, killer.player, true), "real friendship reveals the killer");
                claim(true, ValidKillCause.NORMAL_PVP, true);
                ThrownPotion bottle = (ThrownPotion) Proxy.newProxyInstance(Player.class.getClassLoader(),
                        new Class<?>[]{ThrownPotion.class}, (proxy, method, args) -> method.getName().equals("getShooter") ? revealed.player : empty(method));
                smp.invisibilityAnonymity().onWaterBottleSplash(new WaterBottleSplashEvent(bottle, null, null, null,
                        Map.of(killer.player, 1.0), Set.of(), Set.of()));
                require(!smp.invisibilityAnonymity().shouldAnonymize(revealed.player, killer.player, true), "real water-bottle handler reveals this viewer");
                claim(true, ValidKillCause.COMBAT_LOG_DUMMY, true);
                configure(smp.configs().main(), "gameplay.invisibility-anonymity.chat-name", "<aqua>Verborgen</aqua>");
                claim(true, ValidKillCause.NORMAL_PVP, true);
                // A payout listener changing the potion must not undo the kill-state snapshot.
                endInvisibilityDuringPayout = true;
                claim(true, ValidKillCause.NORMAL_PVP, true);
                endInvisibilityDuringPayout = false;
                configure(smp.configs().server(), "bounty.announcements.enabled", false);
                claim(true, ValidKillCause.NORMAL_PVP, false);
                configure(smp.configs().server(), "bounty.announcements.enabled", true);
                configure(smp.configs().server(), "bounty.announcements.bounty-claimed", false);
                claim(true, ValidKillCause.NORMAL_PVP, false);
                configure(smp.configs().server(), "bounty.announcements.bounty-claimed", true);
                killer.online = false;
                claim(true, ValidKillCause.COMBAT_LOG_DUMMY, true);
                getLogger().info("BOUNTY_ANONYMITY_PAPER_SQL=PASS scenarios=" + scenarios);
            } catch (Throwable failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "BOUNTY_ANONYMITY_PAPER_SQL=FAIL", failure);
            } finally {
                try {
                    if (original != null) setServer(original);
                    for (var entry : oldConfig.entrySet()) {
                        int split = entry.getKey().indexOf(':');
                        FileConfiguration config = entry.getKey().startsWith("main:") ? smp.configs().main() : smp.configs().server();
                        config.set(entry.getKey().substring(split + 1), entry.getValue());
                    }
                } catch (Throwable failure) { getLogger().log(java.util.logging.Level.SEVERE, "restore probe state", failure); }
                Bukkit.shutdown();
            }
        }, 40L);
    }

    private void setup() throws Exception {
        smp = (NovoSMPPlugin) Bukkit.getPluginManager().getPlugin("NovoSMP");
        require(smp != null && smp.isEnabled(), "production enabled");
        bounties = (BountyManager) field(smp, "bountyManager");
        original = Bukkit.getServer();
        // Paper's legacy class converter needs the real CraftServer during class loading.
        for (String name : List.of("de.walahi.novosmp.bounty.BountyRepository$SqlWork",
                "de.walahi.novosmp.bounty.BountyRepository$Status", "de.walahi.smpcore.friends.FriendSettings",
                "de.walahi.novosmp.friends.FriendStateCache$TimedRelation", "de.walahi.smpcore.api.event.ActionSource"))
            Class.forName(name, true, smp.getClass().getClassLoader());
        killer = actor("BountyKiller"); victim = actor("BountyVictim"); observer = actor("NormalViewer");
        friend = actor("FriendViewer"); revealed = actor("WaterViewer");
        Server wrapper = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            if (method.getName().equals("getOnlinePlayers")) return actors.stream().filter(a -> a.online).map(a -> a.player).toList();
            if ((method.getName().equals("getPlayer") || method.getName().equals("getPlayerExact")) && args.length == 1)
                for (Actor actor : actors) if (actor.online && (actor.id.equals(args[0]) || actor.name.equals(args[0]))) return actor.player;
            if (method.getName().equals("getPlayer") || method.getName().equals("getPlayerExact")) return null;
            if (method.getName().equals("broadcast") && args[0] instanceof Component message) {
                broadcasts++;
                actors.stream().filter(a -> a.online).forEach(a -> a.messages.add(message));
                return (int) actors.stream().filter(a -> a.online).count();
            }
            try { return method.invoke(original, args); } catch (InvocationTargetException exception) { throw exception.getCause(); }
        });
        setServer(wrapper);
        configure(smp.configs().main(), "gameplay.invisibility-anonymity.enabled", true);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void claim(boolean invisible, ValidKillCause cause, boolean announced) throws Exception {
        killer.invisible = invisible;
        // Package-private repository records live in the production plugin's classloader.
        Class<?> knownType = Class.forName("de.walahi.novosmp.bounty.BountyRepository$KnownPlayer");
        Constructor<?> knownConstructor = knownType.getDeclaredConstructor(UUID.class, String.class);
        knownConstructor.setAccessible(true);
        Object mutation = BountyManager.class.getMethod("adminSet", knownType, long.class)
                .invoke(bounties, knownConstructor.newInstance(victim.id, victim.name), 2345L);
        Method status = mutation.getClass().getDeclaredMethod("status"); status.setAccessible(true);
        require(status.invoke(mutation).toString().equals("SUCCESS"), "real bounty created");
        resetMessages(); deposits.clear(); broadcasts = 0;
        long before = smp.economyBalance(killer.id);
        Map<UUID, Boolean> anonymous = new HashMap<>();
        for (Actor viewer : actors) anonymous.put(viewer.id, killer.online && smp.invisibilityAnonymity()
                .shouldAnonymize(viewer.player, killer.player, invisible));
        ValidPlayerKillEvent event = new ValidPlayerKillEvent(killer.id, killer.name, victim.id, victim.name, cause, null);
        bounties.onValidKill(event);
        require(smp.economyBalance(killer.id) == before + 2345L && bounties.get(victim.id) == null, "exact UUID credited, amount unchanged, bounty removed");
        require(deposits.size() == 1 && deposits.getFirst().getPlayerUuid().equals(killer.id)
                && deposits.getFirst().getAmount() == 2345L, "one real-UUID economy event");
        String anonymousName = plain(smp.invisibilityAnonymity().anonymousName());
        for (Actor viewer : actors) {
            if (!viewer.online) continue;
            require(viewer.messages.size() == (announced ? 1 : 0), "announcement switches and one message per viewer");
            if (!announced) continue;
            String text = plain(viewer.messages.getFirst());
            String name = anonymous.get(viewer.id) ? anonymousName : killer.name;
            require(text.contains(name) && text.contains(victim.name) && text.contains("2.345"), "viewer name, victim and amount");
            if (anonymous.get(viewer.id)) require(!text.contains(killer.name), "no real-name leak to anonymous viewer");
            if (!invisible || !killer.online) {
                Component legacy = MiniMessage.miniMessage().deserialize(smp.configs().server().getString("bounty.messages.bounty-claimed")
                        .replace("%killer%", killer.name).replace("%player%", victim.name).replace("%amount%", "2.345"));
                require(viewer.messages.getFirst().equals(legacy), "visible/offline message unchanged including style");
            }
        }
        require(broadcasts == (announced && (!invisible || !killer.online) ? 1 : 0), "invisible claims use no global broadcast");
        if (invisible && killer.online && announced && !endInvisibilityDuringPayout) {
            resetMessages(); death();
        }
        resetMessages();
        bounties.onValidKill(event);
        require(smp.economyBalance(killer.id) == before + 2345L && deposits.size() == 1
                && actors.stream().allMatch(a -> a.messages.isEmpty()), "repeated kill cannot claim or announce twice");
        Object repository = field(bounties, "repository");
        Method load = repository.getClass().getDeclaredMethod("loadAll"); load.setAccessible(true);
        require(((Map<?, ?>) load.invoke(repository)).get(victim.id) == null, "claim removed from SQL persistence");
        scenarios++;
    }

    @EventHandler public void economy(EconomyTransactionEvent event) {
        if (!event.getReason().equals("BOUNTY_CLAIM")) return;
        deposits.add(event);
        if (endInvisibilityDuringPayout) killer.invisible = false;
    }

    @SuppressWarnings("deprecation")
    private void death() {
        resetMessages();
        victim.damage = new EntityDamageByEntityEvent(killer.player, victim.player, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 20.0);
        PlayerDeathEvent event = new PlayerDeathEvent(victim.player, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>(), 0, Component.empty(), false);
        new DeathMessageListener(smp).onDeath(event);
        require(event.deathMessage() == null, "existing invisible death suppresses global message");
        for (Actor viewer : actors) {
            if (!viewer.online) continue;
            require(viewer.messages.size() == 1, "existing viewer-specific death message");
            boolean anonymous = smp.invisibilityAnonymity().shouldAnonymize(viewer.player, killer.player, true);
            String text = plain(viewer.messages.getFirst());
            require(text.contains(anonymous ? plain(smp.invisibilityAnonymity().anonymousName()) : killer.name), "death follows the same identity rule");
            if (anonymous) require(!text.contains(killer.name), "death also has no leak");
        }
    }

    private Actor actor(String name) {
        Actor actor = new Actor(); actor.name = name;
        var board = Bukkit.getScoreboardManager().getNewScoreboard();
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> method.getReturnType() == ItemStack.class ? new ItemStack(Material.AIR) : empty(method));
        actor.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> actor.id;
                case "getName" -> actor.name;
                case "equals" -> proxy == args[0];
                case "hashCode" -> actor.id.hashCode();
                case "hasPotionEffect" -> actor.invisible && args[0].equals(PotionEffectType.INVISIBILITY);
                case "isOnline" -> actor.online;
                case "getScoreboard" -> board;
                case "getInventory" -> inventory;
                case "getLastDamageCause" -> actor.damage;
                case "getWorld" -> Bukkit.getWorlds().getFirst();
                case "sendMessage" -> { if (args[0] instanceof Component component) actor.messages.add(component); yield null; }
                default -> empty(method);
            };
        });
        actors.add(actor); return actor;
    }
    private void configure(FileConfiguration config, String path, Object value) {
        String key = (config == smp.configs().main() ? "main:" : "server:") + path;
        if (!oldConfig.containsKey(key)) oldConfig.put(key, config.get(path));
        config.set(path, value);
    }
    private void resetMessages() { actors.forEach(actor -> actor.messages.clear()); }
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    private static Object empty(Method method) {
        if (method.getReturnType() == boolean.class) return false;
        if (method.getReturnType() == int.class) return 0;
        if (method.getReturnType() == long.class) return 0L;
        if (method.getReturnType() == double.class) return 0.0;
        if (method.getReturnType() == float.class) return 0.0f;
        return null;
    }
    private static Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void setServer(Server server) throws Exception { Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, server); }
    private static void require(boolean okay, String reason) { if (!okay) throw new AssertionError(reason); }
    private static final class Actor {
        UUID id = UUID.randomUUID(); String name; Player player; boolean invisible, online = true;
        EntityDamageEvent damage; List<Component> messages = new ArrayList<>();
    }
}
