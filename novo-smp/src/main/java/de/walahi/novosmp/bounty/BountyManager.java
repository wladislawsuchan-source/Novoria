package de.walahi.novosmp.bounty;

import de.walahi.novosmp.NovoSMPPlugin;
import de.walahi.novosmp.combat.CombatRelationshipService;
import de.walahi.novosmp.combat.ValidKillCause;
import de.walahi.novosmp.combat.ValidPlayerKillEvent;
import de.walahi.smpcore.api.event.ActionContext;
import de.walahi.smpcore.api.event.ActionSource;
import de.walahi.smpcore.api.event.EconomyTransactionEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Event-driven bounty facade backed by transactional SQL operations. */
public final class BountyManager implements Listener {
    public enum PlaceStatus {
        NEW_BOUNTY, INCREASED, DISABLED, TARGET_NOT_FOUND, OFFLINE_NOT_ALLOWED,
        SELF_NOT_ALLOWED, BELOW_MINIMUM, ABOVE_MAXIMUM, INSUFFICIENT_FUNDS,
        INVALID_AMOUNT, STORAGE_ERROR
    }
    public record PlaceResult(PlaceStatus status, BountyEntry entry) { }

    private final NovoSMPPlugin plugin;
    private final CombatRelationshipService relationships;
    private final BountyRepository repository;
    private final BountyMenu menu;
    private final Map<UUID, BountyEntry> active = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private boolean enabled;
    private long minimumAmount;
    private long maximumAmount;
    private boolean allowSelf;
    private boolean allowOffline;
    private boolean friendsCanClaim;
    private boolean duelKillsCanClaim;

    public BountyManager(NovoSMPPlugin plugin, CombatRelationshipService relationships) {
        this.plugin = plugin;
        this.relationships = relationships;
        this.repository = new BountyRepository(plugin.storageManager());
        this.menu = new BountyMenu(plugin, this);
        reload();
        try {
            active.putAll(repository.loadAll());
        } catch (SQLException exception) {
            plugin.getLogger().severe("Bounties konnten beim Start nicht geladen werden: " + exception.getMessage());
        }
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reload() {
        enabled = plugin.configs().server().getBoolean("bounty.enabled", true);
        minimumAmount = Math.max(1L, plugin.configs().server().getLong("bounty.minimum-amount", 1_000L));
        maximumAmount = plugin.configs().server().getLong("bounty.maximum-amount", -1L);
        if (maximumAmount == 0L || maximumAmount < -1L) maximumAmount = -1L;
        allowSelf = plugin.configs().server().getBoolean("bounty.allow-self-bounty", false);
        allowOffline = plugin.configs().server().getBoolean("bounty.allow-offline-targets", true);
        friendsCanClaim = plugin.configs().server().getBoolean("bounty.payout.friends-can-claim", false);
        duelKillsCanClaim = plugin.configs().server().getBoolean("bounty.payout.duel-kills-can-claim", false);
    }

    public boolean enabled() { return enabled; }
    public long minimumAmount() { return minimumAmount; }
    public long maximumAmount() { return maximumAmount; }

    public void open(Player player) { menu.open(player); }

    public List<BountyEntry> entries() {
        List<BountyEntry> result = new ArrayList<>(active.values());
        String sort = plugin.configs().server().getString("bounty.gui.sort", "HIGHEST_FIRST")
                .trim().toUpperCase(Locale.ROOT);
        Comparator<BountyEntry> comparator = switch (sort) {
            case "LOWEST_FIRST" -> Comparator.comparingLong(BountyEntry::amount)
                    .thenComparing(BountyEntry::targetName, String.CASE_INSENSITIVE_ORDER);
            case "NAME_ASC" -> Comparator.comparing(BountyEntry::targetName, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingLong(BountyEntry::amount).reversed()
                    .thenComparing(BountyEntry::targetName, String.CASE_INSENSITIVE_ORDER);
        };
        result.sort(comparator);
        return List.copyOf(result);
    }

    public BountyEntry get(UUID target) { return active.get(target); }

    public PlaceResult place(Player payer, String targetInput, long amount) {
        if (!enabled) return new PlaceResult(PlaceStatus.DISABLED, null);
        if (amount <= 0L) return new PlaceResult(PlaceStatus.INVALID_AMOUNT, null);
        if (amount < minimumAmount) return new PlaceResult(PlaceStatus.BELOW_MINIMUM, null);
        if (maximumAmount >= 0L && amount > maximumAmount) return new PlaceResult(PlaceStatus.ABOVE_MAXIMUM, null);

        BountyRepository.KnownPlayer target = resolveTarget(targetInput);
        if (target == null) return new PlaceResult(
                allowOffline ? PlaceStatus.TARGET_NOT_FOUND : PlaceStatus.OFFLINE_NOT_ALLOWED, null);
        if (!allowSelf && payer.getUniqueId().equals(target.id())) {
            return new PlaceResult(PlaceStatus.SELF_NOT_ALLOWED, null);
        }

        ActionContext context = ActionContext.actorTarget(
                ActionSource.COMMAND, payer.getUniqueId(), target.id());
        BountyRepository.Mutation mutation = repository.place(
                payer.getUniqueId(), target.id(), target.name(), amount, context);
        if (mutation.status() == BountyRepository.Status.INSUFFICIENT_FUNDS) {
            return new PlaceResult(PlaceStatus.INSUFFICIENT_FUNDS, null);
        }
        if (mutation.status() == BountyRepository.Status.INVALID_AMOUNT) {
            return new PlaceResult(PlaceStatus.INVALID_AMOUNT, null);
        }
        if (mutation.status() != BountyRepository.Status.SUCCESS || mutation.entry() == null) {
            return new PlaceResult(PlaceStatus.STORAGE_ERROR, null);
        }

        active.put(target.id(), mutation.entry());
        boolean existed = mutation.entry().amount() != amount;
        publishEconomy(payer.getUniqueId(), EconomyTransactionEvent.Type.WITHDRAW, amount,
                mutation.balanceBefore(), mutation.balanceAfter(), "BOUNTY_PLACE", context);
        announce(existed ? "bounty-increased" : "new-bounty", Map.of(
                "%player%", escape(target.name()),
                "%amount%", format(mutation.entry().amount())
        ));
        return new PlaceResult(existed ? PlaceStatus.INCREASED : PlaceStatus.NEW_BOUNTY, mutation.entry());
    }

    public BountyRepository.KnownPlayer resolveTarget(String input) {
        if (input == null || input.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return new BountyRepository.KnownPlayer(online.getUniqueId(), online.getName());
        if (!allowOffline) return null;
        return resolveKnown(input);
    }

    public BountyRepository.KnownPlayer resolveKnown(String input) {
        try {
            return repository.findKnownPlayer(input);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Bounty-Spielersuche fehlgeschlagen: " + exception.getMessage());
            return null;
        }
    }

    BountyRepository.KnownPlayer resolveAnyKnown(String input) {
        Player online = Bukkit.getPlayerExact(input);
        return online == null
                ? resolveKnown(input)
                : new BountyRepository.KnownPlayer(online.getUniqueId(), online.getName());
    }

    public BountyRepository.Mutation adminSet(BountyRepository.KnownPlayer target, long amount) {
        BountyRepository.Mutation result = repository.adminSet(target.id(), target.name(), amount);
        applyAdminResult(target.id(), result);
        return result;
    }

    public BountyRepository.Mutation adminAdd(BountyRepository.KnownPlayer target, long amount) {
        BountyRepository.Mutation result = repository.adminAdd(target.id(), target.name(), amount);
        applyAdminResult(target.id(), result);
        return result;
    }

    public BountyRepository.Mutation adminRemove(BountyRepository.KnownPlayer target) {
        BountyRepository.Mutation result = repository.adminRemove(target.id());
        if (result.status() == BountyRepository.Status.SUCCESS) active.remove(target.id());
        return result;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onValidKill(ValidPlayerKillEvent event) {
        if (!enabled) return;
        if (event.cause() == ValidKillCause.DUEL && !duelKillsCanClaim) return;
        if (!friendsCanClaim && relationships.isFriendly(event.killerId(), event.victimId())) return;

        ActionContext context = ActionContext.actorTarget(
                ActionSource.SYSTEM, event.killerId(), event.victimId());
        BountyRepository.Mutation result = repository.claim(event.killerId(), event.victimId(), context);
        if (result.status() == BountyRepository.Status.NOT_FOUND) {
            active.remove(event.victimId());
            return;
        }
        if (result.status() != BountyRepository.Status.SUCCESS || result.entry() == null) {
            plugin.getLogger().severe("Bounty auf " + event.victimId() + " konnte nicht sicher ausgezahlt werden.");
            return;
        }

        active.remove(event.victimId());
        publishEconomy(event.killerId(), EconomyTransactionEvent.Type.DEPOSIT, result.entry().amount(),
                result.balanceBefore(), result.balanceAfter(), "BOUNTY_CLAIM", context);
        announce("bounty-claimed", Map.of(
                "%killer%", escape(event.killerName()),
                "%player%", escape(event.victimName()),
                "%amount%", format(result.entry().amount())
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        BountyEntry current = active.get(event.getPlayer().getUniqueId());
        if (current == null || current.targetName().equals(event.getPlayer().getName())) return;
        try {
            repository.updateName(current.targetId(), event.getPlayer().getName());
            active.put(current.targetId(), new BountyEntry(current.targetId(), event.getPlayer().getName(),
                    current.amount(), current.updatedAt()));
        } catch (SQLException exception) {
            plugin.getLogger().warning("Bounty-Spielername konnte nicht aktualisiert werden: " + exception.getMessage());
        }
    }

    private void applyAdminResult(UUID target, BountyRepository.Mutation result) {
        if (result.status() != BountyRepository.Status.SUCCESS) return;
        if (result.entry() == null) active.remove(target);
        else active.put(target, result.entry());
    }

    private void publishEconomy(UUID player, EconomyTransactionEvent.Type type, long amount,
                                long before, long after, String reason, ActionContext context) {
        Bukkit.getPluginManager().callEvent(
                new EconomyTransactionEvent(player, type, amount, before, after, reason, context));
    }

    private void announce(String key, Map<String, String> replacements) {
        if (!plugin.configs().server().getBoolean("bounty.announcements.enabled", true)
                || !plugin.configs().server().getBoolean("bounty.announcements." + key, true)) return;
        String fallback = switch (key) {
            case "new-bounty" -> "<dark_red>☠</dark_red> <red>Auf <yellow>%player%</yellow> wurde ein Kopfgeld von <gold>%amount% Coins</gold> ausgesetzt!</red>";
            case "bounty-increased" -> "<dark_red>☠</dark_red> <red>Das Kopfgeld auf <yellow>%player%</yellow> wurde auf <gold>%amount% Coins</gold> erhöht!</red>";
            default -> "<dark_red>☠</dark_red> <red><yellow>%killer%</yellow> hat das Kopfgeld auf <yellow>%player%</yellow> kassiert: <gold>%amount% Coins</gold>!</red>";
        };
        String configured = plugin.configs().server().getString("bounty.messages." + key, fallback);
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            configured = configured.replace(replacement.getKey(), replacement.getValue());
        }
        Component message = miniMessage.deserialize(configured);
        Bukkit.broadcast(message);
    }

    public static String format(long value) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(value);
    }

    public static String escape(String value) {
        return value == null ? "" : value.replace("<", "\\<").replace(">", "\\>");
    }
}
