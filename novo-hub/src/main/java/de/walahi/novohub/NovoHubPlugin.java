package de.walahi.novohub;

import de.walahi.novohub.feature.HubHungerListener;
import de.walahi.novohub.feature.HubPlayerStateManager;
import de.walahi.novohub.feature.HubScoreboardManager;
import de.walahi.novohub.feature.HubWorldProtectionListener;
import de.walahi.novohub.chat.HubChatListener;
import de.walahi.smpcore.commands.framework.CommandRegistry;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.network.ServerType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class NovoHubPlugin extends SMPCorePlugin {
    private HubPlayerStateManager hubPlayerStateManager;
    private HubScoreboardManager hubScoreboardManager;

    @Override
    protected ServerType forcedServerType() {
        return ServerType.HUB;
    }

    @Override
    protected String localConfigResourceName() {
        return "novo-hub.yml";
    }


    @Override
    protected void registerServerListeners() {
        Bukkit.getPluginManager().registerEvents(new HubWorldProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new HubChatListener(this), this);
    }

    @Override
    protected void registerServerCommands(CommandRegistry commands) {
    }

    @Override
    protected void initializeEarlyServerFeatures() {
        hubPlayerStateManager = new HubPlayerStateManager(this);
        Bukkit.getPluginManager().registerEvents(hubPlayerStateManager, this);
        Bukkit.getPluginManager().registerEvents(
                new HubHungerListener(this, hubPlayerStateManager), this);
    }

    @Override
    protected void initializeLateServerFeatures() {
        hubScoreboardManager = new HubScoreboardManager(this);
        hubScoreboardManager.start();
    }

    @Override
    protected void applyServerPlayerProfile(Player player) {
        if (hubPlayerStateManager != null) {
            hubPlayerStateManager.apply(player);
        }
    }

    @Override
    protected void reloadServerFeatures() {
        if (hubPlayerStateManager != null) hubPlayerStateManager.refreshAll();
    }

    @Override
    protected void stopServerFeatures() {
        if (hubPlayerStateManager != null) {
            hubPlayerStateManager.shutdown();
            hubPlayerStateManager = null;
        }
        if (hubScoreboardManager != null) {
            hubScoreboardManager.stop();
            hubScoreboardManager = null;
        }
    }

    @Override
    public void refreshVisibleOnlineDisplays() {
        super.refreshVisibleOnlineDisplays();
        if (hubScoreboardManager != null) hubScoreboardManager.refreshNow();
    }
}
