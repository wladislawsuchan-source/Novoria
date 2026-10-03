package de.walahi.smpcore.network;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/** Communication from a Paper backend to Velocity. */
public final class NetworkManager implements PluginMessageListener {
    public static final String SMPCORE_CHANNEL = "smpcore:network";
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private final SMPCorePlugin plugin;
    private volatile int networkTotalOnline = -1;
    private volatile int networkHubOnline = -1;
    private volatile int networkSmpOnline = -1;
    private volatile long lastCountUpdateMillis;

    public NetworkManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SMPCORE_CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SMPCORE_CHANNEL, this);
    }

    public void unregister() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SMPCORE_CHANNEL);
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, SMPCORE_CHANNEL, this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!SMPCORE_CHANNEL.equals(channel)) return;
        try (java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(message);
             java.io.DataInputStream input = new java.io.DataInputStream(bytes)) {
            String action = input.readUTF();
            if (!"NETWORK_COUNTS".equals(action)) return;
            networkTotalOnline = Math.max(0, input.readInt());
            networkHubOnline = Math.max(0, input.readInt());
            networkSmpOnline = Math.max(0, input.readInt());
            lastCountUpdateMillis = System.currentTimeMillis();
        } catch (IOException exception) {
            plugin.getLogger().warning("Ungültige Netzwerk-Spielerzahl empfangen: " + exception.getMessage());
        }
    }

    private boolean countsFresh() {
        return lastCountUpdateMillis > 0L && System.currentTimeMillis() - lastCountUpdateMillis <= 10_000L;
    }

    public int totalOnline(int localFallback) {
        return countsFresh() ? networkTotalOnline : localFallback;
    }

    public int hubOnline(int localFallback) {
        return countsFresh() ? networkHubOnline : localFallback;
    }

    public int smpOnline(int localFallback) {
        return countsFresh() ? networkSmpOnline : localFallback;
    }

    public void connect(Player player, String serverName) {
        if (serverName == null || serverName.isBlank()) {
            player.sendMessage("§cDer Zielserver ist nicht konfiguriert.");
            return;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("Connect");
            output.writeUTF(serverName);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            plugin.getLogger().severe("Serverwechsel zu '" + serverName + "' fehlgeschlagen: " + exception.getMessage());
            player.sendMessage("§cDer Serverwechsel ist fehlgeschlagen.");
        }
    }

    public boolean kickFromNetwork(Player target, String miniMessageReason) {
        return send(target, "KICK", output -> {
            output.writeUTF(target.getUniqueId().toString());
            output.writeUTF(defaultReason(miniMessageReason));
        });
    }

    public boolean addNetworkBan(Player preferredCarrier, UUID targetUuid, Instant expiresAt, String miniMessageReason) {
        Player carrier = carrier(preferredCarrier);
        if (carrier == null) return false;
        return send(carrier, "BAN_ADD", output -> {
            output.writeUTF(targetUuid.toString());
            output.writeLong(expiresAt == null ? -1L : expiresAt.toEpochMilli());
            output.writeUTF(defaultReason(miniMessageReason));
        });
    }

    public boolean removeNetworkBan(Player preferredCarrier, UUID targetUuid) {
        Player carrier = carrier(preferredCarrier);
        if (carrier == null) return false;
        return send(carrier, "BAN_REMOVE", output -> output.writeUTF(targetUuid.toString()));
    }

    private Player carrier(Player preferred) {
        if (preferred != null && preferred.isOnline()) return preferred;
        return Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
    }

    private boolean send(Player carrier, String action, PayloadWriter writer) {
        if (carrier == null || !carrier.isOnline()) return false;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(action);
            writer.write(output);
            carrier.sendPluginMessage(plugin, SMPCORE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Netzwerknachricht '" + action + "' fehlgeschlagen: " + exception.getMessage());
            return false;
        }
    }

    private String defaultReason(String reason) {
        return reason == null ? "<red>Du wurdest vom Netzwerk getrennt.</red>" : reason;
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(DataOutputStream output) throws IOException;
    }
}
