package de.walahi.novosmp.friends;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Viewer-spezifischer Glow. Zusaetzlich zum initialen Metadata-Paket wird jedes
 * spaetere ENTITY_METADATA-Update beobachtet. Falls Vanilla/Paper dabei das nur
 * clientseitig gesetzte Glow-Bit wieder ueberschreibt, wird es im Folgetick fuer
 * genau diesen Viewer erneut gesetzt.
 */
public final class ViewerGlowService {
    private record MetadataKey(UUID viewer, int entityId) { }

    private final JavaPlugin plugin;
    private final BiPredicate<Player, Player> shouldGlow;
    private final Set<String> reportedFailures = new HashSet<>();
    private final Set<MetadataKey> queuedCorrections = ConcurrentHashMap.newKeySet();

    public ViewerGlowService(JavaPlugin plugin, BiPredicate<Player, Player> shouldGlow) {
        this.plugin = plugin;
        this.shouldGlow = shouldGlow;
        registerMetadataGuard();
    }

    public void setGlowing(Player viewer, Player target, boolean glowing) {
        if (viewer == null || target == null || !viewer.isOnline() || !target.isOnline()) return;
        try {
            WrappedDataWatcher entityWatcher = WrappedDataWatcher.getEntityWatcher(target);
            Byte current = entityWatcher.getByte(0);
            byte flags = current == null ? 0 : current;
            // Bei viewer-spezifischem Glow wird das Flag zusaetzlich gesetzt. Beim
            // Ausschalten senden wir den echten serverseitigen Metadata-Zustand zurueck,
            // damit legitimer globaler Glowing-Status (z.B. Spectral Arrow) erhalten bleibt.
            if (glowing) flags = (byte) (flags | 0x40);

            WrappedDataWatcher changed = new WrappedDataWatcher();
            WrappedDataWatcher.WrappedDataWatcherObject object = new WrappedDataWatcher.WrappedDataWatcherObject(
                    0, WrappedDataWatcher.Registry.get(Byte.class));
            changed.setObject(object, flags);

            PacketContainer packet = ProtocolLibrary.getProtocolManager()
                    .createPacket(PacketType.Play.Server.ENTITY_METADATA);
            packet.getIntegers().write(0, target.getEntityId());
            packet.getDataValueCollectionModifier().write(0, changed.toDataValueCollection());
            ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet);
        } catch (Exception exception) {
            reportFailure(exception);
        }
    }

    public void shutdown() {
        queuedCorrections.clear();
    }

    private void registerMetadataGuard() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) return;
        try {
            ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                    plugin,
                    ListenerPriority.MONITOR,
                    PacketType.Play.Server.ENTITY_METADATA
            ) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    Player viewer = event.getPlayer();
                    if (viewer == null) return;
                    Integer entityId = event.getPacket().getIntegers().readSafely(0);
                    if (entityId == null) return;

                    // Nur ein Metadata-Paket, das Index 0 tatsaechlich mit deaktiviertem
                    // Glow-Bit sendet, kann den clientseitigen Viewer-Glow entfernen.
                    // Unser eigenes Korrekturpaket hat das Bit bereits gesetzt und erzeugt
                    // deshalb keine Endlosschleife.
                    java.util.List<WrappedDataValue> values = event.getPacket()
                            .getDataValueCollectionModifier().readSafely(0);
                    if (values == null) return;
                    boolean clearsGlow = false;
                    for (WrappedDataValue value : values) {
                        if (value != null && value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                            clearsGlow = (flags & 0x40) == 0;
                            break;
                        }
                    }
                    if (!clearsGlow) return;

                    // Packet-Callbacks koennen ausserhalb des Main-Threads laufen. Deshalb
                    // wird hier nur dedupliziert; Bukkit-/Friend-State-Zugriffe passieren
                    // ausschliesslich im Folgetick auf dem Server-Thread.
                    MetadataKey key = new MetadataKey(viewer.getUniqueId(), entityId);
                    if (!queuedCorrections.add(key)) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        queuedCorrections.remove(key);
                        Player onlineViewer = Bukkit.getPlayer(key.viewer());
                        if (onlineViewer == null || !onlineViewer.isOnline()) return;
                        Player onlineTarget = findPlayer(onlineViewer, key.entityId());
                        if (onlineTarget == null || onlineViewer.getWorld() != onlineTarget.getWorld()) return;
                        try {
                            if (shouldGlow.test(onlineViewer, onlineTarget)) {
                                setGlowing(onlineViewer, onlineTarget, true);
                            }
                        } catch (RuntimeException exception) {
                            reportFailure(exception);
                        }
                    });
                }
            });
        } catch (RuntimeException exception) {
            reportFailure(exception);
        }
    }

    private Player findPlayer(Player viewer, int entityId) {
        if (viewer.getEntityId() == entityId) return viewer;
        for (Player candidate : viewer.getWorld().getPlayers()) {
            if (candidate.getEntityId() == entityId) return candidate;
        }
        return null;
    }

    private void reportFailure(Exception exception) {
        String key = exception.getClass().getName() + ":" + String.valueOf(exception.getMessage());
        synchronized (reportedFailures) {
            if (reportedFailures.add(key)) {
                plugin.getLogger().warning("Viewer-Glow konnte nicht synchronisiert werden: " + key);
            }
        }
    }
}
