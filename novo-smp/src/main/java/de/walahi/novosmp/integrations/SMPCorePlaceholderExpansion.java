package de.walahi.novosmp.integrations;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.ranks.RankManager;
import de.walahi.smpcore.services.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/** PlaceholderAPI bridge. Available placeholders: %smpcore_balance%, %smpcore_money%, %smpcore_rank%. */
public final class SMPCorePlaceholderExpansion extends PlaceholderExpansion {
    private static final NumberFormat COIN_FORMAT = NumberFormat.getIntegerInstance(Locale.GERMANY);

    private final SMPCorePlugin plugin;
    private final EconomyService economy;
    private final RankManager ranks;

    public SMPCorePlaceholderExpansion(SMPCorePlugin plugin, EconomyService economy, RankManager ranks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.ranks = Objects.requireNonNull(ranks, "ranks");
    }

    @Override public @NotNull String getIdentifier() { return "smpcore"; }
    @Override public @NotNull String getAuthor() { return "Walahi"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || player.getUniqueId() == null) return "";
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "balance" -> Long.toString(economy.balance(player.getUniqueId()));
            case "money" -> COIN_FORMAT.format(economy.balance(player.getUniqueId()));
            case "rank" -> rank(player);
            default -> null;
        };
    }

    private String rank(OfflinePlayer offlinePlayer) {
        Player online = offlinePlayer.getPlayer();
        return online == null ? ranks.fallbackPlayerRank().key() : ranks.resolve(online).key();
    }
}
