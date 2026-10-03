package de.walahi.novosmp.referral;

import de.walahi.novosmp.crates.CrateDefinition;
import de.walahi.novosmp.crates.CrateManager;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.StatType;
import de.walahi.smpcore.stats.StatsAccess;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Fachlogik für Referral-Codes, Verifizierung, Punkte, Stufen und Novo-Key-Belohnungen. */
public final class ReferralManager {
    private final SMPCorePlugin plugin;
    private final ReferralRepository repository;
    private final StatsAccess stats;
    private final CrateManager crates;
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();

    public ReferralManager(SMPCorePlugin plugin, ReferralRepository repository, StatsAccess stats, CrateManager crates) {
        this.plugin = plugin;
        this.repository = repository;
        this.stats = stats;
        this.crates = crates;
    }

    public boolean enabled() { return plugin.configs().main().getBoolean("referral.enabled", true); }

    public ReferralRepository.Account account(Player player) {
        try {
            return repository.account(player.getUniqueId(), player.getName());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Referral-Konto konnte nicht geladen werden: " + exception.getMessage());
            return null;
        }
    }

    /** Schaltet den persönlichen Referral-Code erst nach dem bewussten Nametag-Klick frei. */
    public ReferralRepository.Account unlockCode(Player player) {
        ReferralRepository.Account account = account(player);
        if (account == null) return null;
        if (account.codeUnlocked()) return account;
        try {
            repository.unlockCode(player.getUniqueId());
            return repository.findAccount(player.getUniqueId());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Referral-Code konnte nicht freigeschaltet werden: " + exception.getMessage());
            return null;
        }
    }

    public int invites(UUID playerId) {
        try { return repository.inviteCount(playerId); }
        catch (SQLException exception) {
            plugin.getLogger().warning("Referral-Einladungen konnten nicht geladen werden: " + exception.getMessage());
            return 0;
        }
    }

    public Set<Integer> claimed(UUID playerId) {
        try { return repository.claimedTiers(playerId); }
        catch (SQLException exception) {
            plugin.getLogger().warning("Referral-Claims konnten nicht geladen werden: " + exception.getMessage());
            return Set.of();
        }
    }

    /** Vollständige Bestenliste aller Spieler mit mindestens einem erfolgreichen Referral. */
    public List<ReferralRepository.LeaderboardEntry> leaderboard() {
        try { return repository.leaderboard(); }
        catch (SQLException exception) {
            plugin.getLogger().warning("Referral-Bestenliste konnte nicht geladen werden: " + exception.getMessage());
            return List.of();
        }
    }

    /** Wird beim Join aufgerufen: Pending-Status laden und ausstehende Keys zustellen. */
    public void trackPlayer(Player player) {
        if (!enabled()) return;
        account(player);
        int delivered = deliverPendingKeys(player);
        if (delivered > 0) {
            player.sendRichMessage(config("referral.messages.pending-keys-delivered",
                    "<gold>Novoria</gold> <dark_gray>»</dark_gray> <green>Du hast <dark_purple>%amount%x Novo-Key</dark_purple> aus ausstehenden Referral-Belohnungen erhalten.</green>")
                    .replace("%amount%", Integer.toString(delivered)));
        }
        try {
            ReferralRepository.ReferralState state = repository.referral(player.getUniqueId());
            if (state != null && !state.verified()) {
                pendingPlayers.add(player.getUniqueId());
                tryVerify(player);
            } else {
                pendingPlayers.remove(player.getUniqueId());
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Referral-Status konnte nicht geladen werden: " + exception.getMessage());
        }
    }

    public void untrackPlayer(UUID playerId) {
        pendingPlayers.remove(playerId);
    }

    public boolean isPending(UUID playerId) {
        return pendingPlayers.contains(playerId);
    }

    /** Code-Eingabe: nur Vormerkung; Erfolg erst nach 100 Blöcken, 10 Mobs und 3 Advancements. */
    public RedeemOutcome redeem(Player player, String code) {
        if (!enabled()) return RedeemOutcome.DISABLED;
        if (code == null || !code.trim().matches("(?i)[A-Z2-9]{5}")) return RedeemOutcome.INVALID_CODE;
        long maxSeconds = maxRedeemPlaytimeSeconds();
        if (maxSeconds > 0L && stats != null && stats.getStat(player.getUniqueId(), StatType.PLAYTIME) >= maxSeconds) {
            return RedeemOutcome.TOO_OLD;
        }
        if (account(player) == null) return RedeemOutcome.STORAGE_ERROR;
        try {
            ReferralRepository.RedeemResult result = repository.redeem(player.getUniqueId(), code);
            if (result == ReferralRepository.RedeemResult.INVALID_CODE) return RedeemOutcome.INVALID_CODE;
            if (result == ReferralRepository.RedeemResult.OWN_CODE) return RedeemOutcome.OWN_CODE;
            if (result == ReferralRepository.RedeemResult.ALREADY_REDEEMED) return RedeemOutcome.ALREADY_REDEEMED;
            if (result instanceof ReferralRepository.RedeemResult.Success success) {
                pendingPlayers.add(player.getUniqueId());
                Player referrer = Bukkit.getPlayer(success.referrerId());
                if (referrer != null) {
                    referrer.sendRichMessage(config("referral.messages.referrer-pending",
                            "<gold>Novoria</gold> <dark_gray>»</dark_gray> <yellow>%player% hat deinen Referral-Code eingetragen. Die Empfehlung wird nach der Aktivitätsprüfung bestätigt.</yellow>")
                            .replace("%player%", player.getName()));
                }
                return tryVerify(player) == VerificationOutcome.VERIFIED
                        ? RedeemOutcome.VERIFIED
                        : RedeemOutcome.PENDING;
            }
            return RedeemOutcome.STORAGE_ERROR;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Referral-Code konnte nicht vorgemerkt werden: " + exception.getMessage());
            return RedeemOutcome.STORAGE_ERROR;
        }
    }

    /** Prüft eine vorgemerkte Empfehlung gegen die vereinbarten Stats. */
    public VerificationOutcome tryVerify(Player player) {
        if (!enabled() || !pendingPlayers.contains(player.getUniqueId())) return VerificationOutcome.NOT_PENDING;
        VerificationProgress progress = progress(player);
        if (!progress.complete()) return VerificationOutcome.NOT_READY;
        try {
            ReferralRepository.VerifyResult result = repository.verify(player.getUniqueId());
            if (result == ReferralRepository.VerifyResult.NO_REFERRAL) {
                pendingPlayers.remove(player.getUniqueId());
                return VerificationOutcome.NOT_PENDING;
            }
            UUID referrerId;
            if (result instanceof ReferralRepository.VerifyResult.Success success) {
                referrerId = success.referrerId();
            } else if (result instanceof ReferralRepository.VerifyResult.AlreadyVerified verified) {
                pendingPlayers.remove(player.getUniqueId());
                return VerificationOutcome.ALREADY_VERIFIED;
            } else {
                return VerificationOutcome.STORAGE_ERROR;
            }

            pendingPlayers.remove(player.getUniqueId());
            deliverPendingKeys(player);
            player.sendRichMessage(config("referral.messages.referred-verified",
                    "<gold>Novoria</gold> <dark_gray>»</dark_gray> <green>Deine Empfehlung wurde bestätigt. Du erhältst <dark_purple>1 Novo-Key</dark_purple>.</green>"));

            Player referrer = Bukkit.getPlayer(referrerId);
            if (referrer != null) {
                deliverPendingKeys(referrer);
                referrer.sendRichMessage(config("referral.messages.referrer-verified",
                                "<gold>Novoria</gold> <dark_gray>»</dark_gray> <green>%player% wurde als Empfehlung bestätigt. <aqua>+1 Punkt</aqua> und <dark_purple>+1 Novo-Key</dark_purple>.</green>")
                        .replace("%player%", player.getName()));
            }
            return VerificationOutcome.VERIFIED;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Referral konnte nicht verifiziert werden: " + exception.getMessage());
            return VerificationOutcome.STORAGE_ERROR;
        }
    }

    public VerificationProgress progress(Player player) {
        long blocks = stat(player, StatType.BLOCKS_BROKEN);
        long mobs = stat(player, StatType.MOB_KILLS);
        long advancements = stat(player, StatType.ADVANCEMENTS);
        return new VerificationProgress(
                blocks, requiredBlocks(), mobs, requiredMobKills(), advancements, requiredAdvancements());
    }

    private long stat(Player player, StatType type) {
        return stats == null ? 0L : Math.max(0L, stats.getStat(player.getUniqueId(), type));
    }

    public int requiredBlocks() {
        return Math.max(0, plugin.configs().main().getInt("referral.verification.blocks-mined", 100));
    }

    public int requiredMobKills() {
        return Math.max(0, plugin.configs().main().getInt("referral.verification.mob-kills", 10));
    }

    public int requiredAdvancements() {
        return Math.max(0, plugin.configs().main().getInt("referral.verification.advancements", 3));
    }

    /** Liefert persistierte Referral-Keys aus, sobald der Spieler online ist. */
    public int deliverPendingKeys(Player player) {
        String crateId = plugin.configs().main().getString("referral.novo-crate-id", "novo");
        CrateDefinition crate = crates.find(crateId == null ? "novo" : crateId).orElse(null);
        if (crate == null || !crate.enabled()) return 0;
        try {
            int amount = repository.takePendingKeys(player.getUniqueId());
            if (amount <= 0) return 0;
            try {
                crates.keys().add(player, crate, amount);
                return amount;
            } catch (RuntimeException exception) {
                repository.addPendingKeys(player.getUniqueId(), amount);
                throw exception;
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Ausstehende Referral-Keys konnten nicht zugestellt werden: " + exception.getMessage());
            return 0;
        }
    }

    public BuyOutcome buyNovoKey(Player player) {
        if (!enabled()) return BuyOutcome.DISABLED;
        ReferralRepository.Account account = account(player);
        if (account == null) return BuyOutcome.STORAGE_ERROR;
        int price = pointPrice();
        String crateId = plugin.configs().main().getString("referral.novo-crate-id", "novo");
        CrateDefinition crate = crates.find(crateId == null ? "novo" : crateId).orElse(null);
        if (crate == null || !crate.enabled()) return BuyOutcome.KEY_UNAVAILABLE;
        try {
            if (!repository.spendPoints(player.getUniqueId(), price)) return BuyOutcome.NOT_ENOUGH_POINTS;
            try {
                crates.keys().add(player, crate, 1);
                return BuyOutcome.SUCCESS;
            } catch (RuntimeException exception) {
                repository.addPoints(player.getUniqueId(), price);
                throw exception;
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Referral-Punktekauf fehlgeschlagen: " + exception.getMessage());
            return BuyOutcome.STORAGE_ERROR;
        }
    }

    public ClaimOutcome claim(Player player, RewardTier tier) {
        if (!enabled()) return ClaimOutcome.DISABLED;
        if (tier == null) return ClaimOutcome.CONFIG_ERROR;
        if (invites(player.getUniqueId()) < tier.requiredInvites()) return ClaimOutcome.NOT_READY;
        String crateId = plugin.configs().main().getString("referral.novo-crate-id", "novo");
        CrateDefinition crate = crates.find(crateId == null ? "novo" : crateId).orElse(null);
        if (crate == null || !crate.enabled()) return ClaimOutcome.KEY_UNAVAILABLE;
        try {
            if (!repository.reserveTier(player.getUniqueId(), tier.id())) return ClaimOutcome.ALREADY_CLAIMED;
            try {
                crates.keys().add(player, crate, tier.novoKeys());
                return ClaimOutcome.SUCCESS;
            } catch (RuntimeException exception) {
                repository.releaseTier(player.getUniqueId(), tier.id());
                throw exception;
            }
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Referral-Stufe konnte nicht abgeholt werden: " + exception.getMessage());
            return ClaimOutcome.STORAGE_ERROR;
        }
    }

    public List<RewardTier> tiers() {
        ConfigurationSection root = plugin.configs().main().getConfigurationSection("referral.reward-tiers");
        List<RewardTier> result = new ArrayList<>();
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null || !section.getBoolean("enabled", true)) continue;
                int id;
                try { id = Integer.parseInt(key); }
                catch (NumberFormatException ignored) { continue; }
                int invites = Math.max(1, section.getInt("invites", id));
                int keys = Math.max(1, section.getInt("novo-keys", id));
                result.add(new RewardTier(id, invites, keys));
            }
        }
        if (result.isEmpty()) {
            result.add(new RewardTier(1, 1, 1));
            result.add(new RewardTier(2, 2, 2));
            result.add(new RewardTier(3, 3, 3));
            result.add(new RewardTier(4, 5, 4));
            result.add(new RewardTier(5, 7, 5));
        }
        result.sort(Comparator.comparingInt(RewardTier::id));
        return List.copyOf(result);
    }

    public Material novoKeyMaterial() {
        String crateId = plugin.configs().main().getString("referral.novo-crate-id", "novo");
        CrateDefinition crate = crates.find(crateId == null ? "novo" : crateId).orElse(null);
        return crate == null ? Material.AMETHYST_SHARD : crate.keyMaterial();
    }

    public int pointPrice() {
        return Math.max(1, plugin.configs().main().getInt("referral.point-shop.novo-key-price", 1));
    }

    public long maxRedeemPlaytimeSeconds() {
        return Math.max(0L, plugin.configs().main().getLong("referral.max-redeem-playtime-seconds", 3600L));
    }

    public String config(String path, String fallback) {
        return plugin.configs().main().getString(path, fallback);
    }

    public record VerificationProgress(long blocks, long requiredBlocks,
                                       long mobKills, long requiredMobKills,
                                       long advancements, long requiredAdvancements) {
        public boolean complete() {
            return blocks >= requiredBlocks && mobKills >= requiredMobKills && advancements >= requiredAdvancements;
        }
    }

    public record RewardTier(int id, int requiredInvites, int novoKeys) { }
    public enum RedeemOutcome { PENDING, VERIFIED, INVALID_CODE, OWN_CODE, ALREADY_REDEEMED, TOO_OLD, DISABLED, STORAGE_ERROR }
    public enum VerificationOutcome { VERIFIED, ALREADY_VERIFIED, NOT_READY, NOT_PENDING, STORAGE_ERROR }
    public enum BuyOutcome { SUCCESS, NOT_ENOUGH_POINTS, KEY_UNAVAILABLE, DISABLED, STORAGE_ERROR }
    public enum ClaimOutcome { SUCCESS, NOT_READY, ALREADY_CLAIMED, KEY_UNAVAILABLE, CONFIG_ERROR, DISABLED, STORAGE_ERROR }
}
