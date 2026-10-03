package de.walahi.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

public final class HologramManager {
    private static final String TAG = "smpcore_hologram";
    private final SMPCorePlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final File file;
    private YamlConfiguration cfg;
    private final Map<String,List<UUID>> entities = new HashMap<>();
    private final Map<String,BukkitTask> tasks = new HashMap<>();

    public HologramManager(SMPCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "holograms.yml");
        load();
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("holograms.yml", false);
        cfg = YamlConfiguration.loadConfiguration(file);
        repairBundledHubDefaultWorld();
        removeBundledHubDefaultFromSmp();
    }

    /** Repairs the old bundled Hub default that still referenced a non-existent world named "hub". */
    private void repairBundledHubDefaultWorld() {
        if (!plugin.isHubServer()) return;
        String p = "holograms.smp";
        if (!"NPC_ATTACHED".equalsIgnoreCase(cfg.getString(p + ".type", ""))) return;
        if (!"hub".equalsIgnoreCase(cfg.getString(p + ".location.world", ""))) return;
        if (cfg.getInt(p + ".npc-id", -1) != 0) return;
        cfg.set(p + ".location.world", "world");
        save();
        plugin.getLogger().info("Hub-Standardhologramm auf Welt 'world' migriert.");
    }

    private void removeBundledHubDefaultFromSmp() {
        if (!plugin.isSmpServer()) return;
        String p = "holograms.smp";
        if (!"NPC_ATTACHED".equalsIgnoreCase(cfg.getString(p + ".type", ""))) return;
        String world = cfg.getString(p + ".location.world", "");
        if (!"hub".equalsIgnoreCase(world) && !"world".equalsIgnoreCase(world)) return;
        if (cfg.getInt(p + ".npc-id", -1) != 0) return;
        cfg.set(p, null);
        save();
        plugin.getLogger().info("Unpassendes Hub-Standardhologramm aus NovoSMP/holograms.yml entfernt.");
    }

    public void reloadAll() {
        removeAll();
        load();
        int recovered = recoverMissingCrateHolograms();
        if (recovered > 0) {
            plugin.getLogger().info(recovered + " fehlende SMP-Crate-Hologramme automatisch aus crate-locations.yml rekonstruiert.");
        }
        spawnAvailable();
    }

    /**
     * Erzeugt alle aktuell auflösbaren Hologramme, ohne bereits funktionierende
     * Displays zu entfernen. Das ist wichtig, wenn Multiverse-Welten oder
     * Citizens-NPCs erst nach dem Plugin geladen werden.
     *
     * @return Anzahl der aktuell vollständig aktiven Hologramme
     */
    public int spawnAvailable() {
        int recovered = recoverMissingCrateHolograms();
        if (recovered > 0) {
            plugin.getLogger().info(recovered + " fehlende Crate-Hologramme erzeugt.");
        }
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return 0;
        for (String id : root.getKeys(false)) {
            if (!cfg.getBoolean("holograms." + id + ".enabled", true)) continue;
            String key = id.toLowerCase(Locale.ROOT);
            if (isActive(key)) continue;
            if (resolveBaseLocation(id) != null) spawn(id);
        }
        int active = 0;
        for (String id : root.getKeys(false)) {
            if (cfg.getBoolean("holograms." + id + ".enabled", true)
                    && isActive(id.toLowerCase(Locale.ROOT))) active++;
        }
        return active;
    }

    /**
     * Rekonstruiert ausschließlich fehlende Crate-Hologramme aus den weiterhin
     * vorhandenen Crate-Positionen. Dadurch benötigt NovoSMP keinen gelöschten
     * SMPCore-Ordner. Bereits konfigurierte Hologramme werden nie überschrieben.
     */
    public int recoverMissingCrateHolograms() {
        if (plugin.crates() == null) return 0;
        int created = 0;
        for (de.walahi.smpcore.bridge.CrateLocator.CratePlacement placement : plugin.crates().placements()) {
            Location block = placement.location();
            if (block == null || block.getWorld() == null) continue;
            if (hasCrateHologramAt(block)) continue;

            String id = generatedCrateHologramId(placement.crateId(), block);
            String p = "holograms." + id;
            cfg.set(p + ".enabled", true);
            cfg.set(p + ".type", "CRATE_ATTACHED");
            cfg.set(p + ".billboard", "CENTER");
            cfg.set(p + ".shadowed", true);
            cfg.set(p + ".see-through", false);
            cfg.set(p + ".background", false);
            cfg.set(p + ".line-width", 220);
            cfg.set(p + ".view-range", 48.0D);
            cfg.set(p + ".update-interval-ticks", 20);
            cfg.set(p + ".line-spacing", 0.30D);
            cfg.set(p + ".lines", List.of(
                    "<gold><bold>" + escapeMiniMessage(placement.displayName()) + "</bold></gold>",
                    "<gray>Linksklick: Vorschau</gray>",
                    "<gray>Rechtsklick: Öffnen</gray>"));
            writeBlockLocation(p + ".block-location", block);
            cfg.set(p + ".crate-id", placement.crateId());
            setDefaultBlockOffset(p);
            created++;
        }
        if (created > 0) save();
        return created;
    }

    private boolean hasCrateHologramAt(Location block) {
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return false;
        for (String id : root.getKeys(false)) {
            String p = "holograms." + id;
            if (!"CRATE_ATTACHED".equalsIgnoreCase(cfg.getString(p + ".type", ""))) continue;
            Location configured = readBlockLocation(p + ".block-location");
            if (configured == null || configured.getWorld() == null) continue;
            if (configured.getWorld().equals(block.getWorld())
                    && configured.getBlockX() == block.getBlockX()
                    && configured.getBlockY() == block.getBlockY()
                    && configured.getBlockZ() == block.getBlockZ()) return true;
        }
        return false;
    }

    private String generatedCrateHologramId(String crateId, Location block) {
        String world = block.getWorld().getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        String crate = Objects.requireNonNullElse(crateId, "crate").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return "crate_" + crate + "_" + world + "_" + block.getBlockX() + "_" + block.getBlockY() + "_" + block.getBlockZ();
    }

    private String escapeMiniMessage(String value) {
        return Objects.requireNonNullElse(value, "Crate").replace("<", "\\<").replace(">", "\\>");
    }

    public int configuredEnabledCount() {
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return 0;
        int count = 0;
        for (String id : root.getKeys(false)) {
            if (cfg.getBoolean("holograms." + id + ".enabled", true)) count++;
        }
        return count;
    }

    /**
     * Counts enabled holograms whose attachment and text can currently be
     * resolved. Unlike {@link #activeCount()}, this deliberately does not
     * require the target chunk and its transient TextDisplay entities to be
     * loaded at the exact instant of the startup check.
     */
    public int resolvableEnabledCount() {
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return 0;
        int count = 0;
        for (String id : root.getKeys(false)) {
            String p = "holograms." + id;
            if (!cfg.getBoolean(p + ".enabled", true)) continue;
            if (resolveBaseLocation(id) != null && !cfg.getStringList(p + ".lines").isEmpty()) count++;
        }
        return count;
    }

    private boolean isActive(String key) {
        List<UUID> ids = entities.get(key);
        if (ids == null || ids.isEmpty()) return false;
        for (UUID uuid : ids) {
            Entity entity = Bukkit.getEntity(uuid);
            if (!(entity instanceof TextDisplay display) || !display.isValid()) return false;
        }
        return true;
    }

    public boolean spawn(String id) {
        remove(id);
        Location base = resolveBaseLocation(id);
        List<String> lines = cfg.getStringList("holograms."+id+".lines");
        if (base == null || base.getWorld() == null || lines.isEmpty()) return false;

        double spacing = cfg.getDouble("holograms."+id+".line-spacing",0.30);
        List<UUID> ids = new ArrayList<>();
        for (int i=0;i<lines.size();i++) {
            double y=(lines.size()-1-i)*spacing;
            Location loc=base.clone().add(0,y,0);
            String raw=lines.get(i);
            TextDisplay d=base.getWorld().spawn(loc,TextDisplay.class,e->{
                e.addScoreboardTag(TAG);
                e.addScoreboardTag(TAG+"_"+id.toLowerCase(Locale.ROOT));
                e.setPersistent(false);
                e.setInvulnerable(true);
                e.setGravity(false);
                e.text(render(raw));
                e.setShadowed(cfg.getBoolean("holograms."+id+".shadowed",true));
                e.setSeeThrough(cfg.getBoolean("holograms."+id+".see-through",false));
                e.setLineWidth(cfg.getInt("holograms."+id+".line-width",200));
                e.setViewRange((float)cfg.getDouble("holograms."+id+".view-range",48));
                e.setBillboard(readBillboard(id));
                e.setTextOpacity((byte) 0xFF);
                float scale = (float) Math.max(0.1D, Math.min(10.0D,
                        cfg.getDouble("holograms." + id + ".scale", 1.0D)));
                var transformation = e.getTransformation();
                transformation.getScale().set(scale);
                e.setTransformation(transformation);
                if (!cfg.getBoolean("holograms."+id+".background",false)) e.setBackgroundColor(Color.fromARGB(0,0,0,0));
            });
            ids.add(d.getUniqueId());
        }
        entities.put(id.toLowerCase(Locale.ROOT),ids);
        int interval=cfg.getInt("holograms."+id+".update-interval-ticks",20);
        if (interval > 0) {
            tasks.put(id.toLowerCase(Locale.ROOT), Bukkit.getScheduler().runTaskTimer(
                    plugin, () -> update(id), interval, interval));
        }
        return true;
    }

    /**
     * Creates or replaces a configuration-backed static TextDisplay hologram.
     * An update interval of {@code 0} deliberately keeps it event driven.
     */
    public boolean upsertStatic(String id, Location location, List<String> lines, double lineSpacing,
                                int lineWidth, double viewRange, boolean shadowed,
                                boolean seeThrough, boolean background, String billboard, double scale) {
        if (id == null || id.isBlank() || location == null || location.getWorld() == null || lines == null || lines.isEmpty()) {
            return false;
        }
        String p = "holograms." + id;
        cfg.set(p + ".enabled", true);
        cfg.set(p + ".type", "STATIC");
        clearAttachmentData(p);
        cfg.set(p + ".billboard", billboard);
        cfg.set(p + ".shadowed", shadowed);
        cfg.set(p + ".see-through", seeThrough);
        cfg.set(p + ".background", background);
        cfg.set(p + ".line-width", lineWidth);
        cfg.set(p + ".view-range", viewRange);
        cfg.set(p + ".scale", scale);
        cfg.set(p + ".update-interval-ticks", 0);
        cfg.set(p + ".line-spacing", lineSpacing);
        cfg.set(p + ".lines", List.copyOf(lines));
        writeLocation(p + ".location", location);
        save();
        location.getChunk().load();
        return spawn(id);
    }

    public boolean isSpawned(String id) {
        return id != null && isActive(id.toLowerCase(Locale.ROOT));
    }

    private void update(String id) {
        List<UUID> ids=entities.get(id.toLowerCase(Locale.ROOT));
        List<String> lines=cfg.getStringList("holograms."+id+".lines");
        if(ids==null)return;

        Location base = resolveBaseLocation(id);
        double spacing = cfg.getDouble("holograms."+id+".line-spacing",0.30);

        for(int i=0;i<ids.size()&&i<lines.size();i++){
            Location target = base == null ? null : base.clone().add(0,(lines.size()-1-i)*spacing,0);
            Entity e=Bukkit.getEntity(ids.get(i));

            if(e instanceof TextDisplay d && d.isValid()) {
                d.text(render(lines.get(i)));
                if (isAttached(id) && target != null && target.getWorld() != null
                        && d.getWorld().equals(target.getWorld())) {
                    d.teleport(target);
                }
                continue;
            }

            // Nicht-persistente Displays dürfen mit dem Chunk verschwinden. Sobald der
            // Ziel-Chunk wieder geladen ist, wird das vollständige Hologramm neu erzeugt.
            if (target != null && target.getWorld() != null
                    && target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
                Bukkit.getScheduler().runTask(plugin, () -> spawn(id));
                return;
            }
        }
    }

    public void respawnHologramsInChunk(Chunk chunk) {
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            if (!cfg.getBoolean("holograms." + id + ".enabled", true)) continue;

            Location target = resolveBaseLocation(id);
            if (target == null || target.getWorld() == null) continue;
            if (!target.getWorld().equals(chunk.getWorld())) continue;
            if ((target.getBlockX() >> 4) != chunk.getX()) continue;
            if ((target.getBlockZ() >> 4) != chunk.getZ()) continue;

            // Citizens und die Chunk-Entities bekommen kurz Zeit, vollständig zu laden.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Location currentTarget = resolveBaseLocation(id);
                if (currentTarget == null || currentTarget.getWorld() == null) return;
                if (!currentTarget.getWorld().isChunkLoaded(
                        currentTarget.getBlockX() >> 4,
                        currentTarget.getBlockZ() >> 4)) return;
                spawn(id);
            }, 5L);
        }
    }

    private Component render(String raw) {
        int all = Bukkit.getOnlinePlayers().size();
        int hub = (int) Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getWorld().getName().equalsIgnoreCase(plugin.configs().server().getString("world", "world")))
                .count();
        int smp = (int) Bukkit.getOnlinePlayers().stream()
                .filter(p -> plugin.isSmpGameplayWorld(p.getWorld()))
                .count();
        if (plugin.getNetworkManager() != null) {
            all = plugin.getNetworkManager().totalOnline(all);
            hub = plugin.getNetworkManager().hubOnline(hub);
            smp = plugin.getNetworkManager().smpOnline(smp);
        }
        String hubWorld = plugin.configs().server().getString("world", "world");
        all = plugin.visibleOnlineCount(null, all, ignored -> true);
        hub = plugin.visibleOnlineCount(null, hub,
                player -> player.getWorld().getName().equalsIgnoreCase(hubWorld));
        smp = plugin.visibleOnlineCount(null, smp,
                player -> plugin.isSmpGameplayWorld(player.getWorld()));
        return mini.deserialize(raw
                .replace("%server_online%", String.valueOf(all))
                .replace("%online%", String.valueOf(all))
                .replace("%hub_online%", String.valueOf(hub))
                .replace("%smp_online%", String.valueOf(smp)));
    }

    public boolean setHere(String id, Location l) {
        if(l.getWorld()==null)return false;
        ensureDefaults(id);
        String p="holograms."+id;
        cfg.set(p+".type","STATIC");
        clearAttachmentData(p);
        String q=p+".location";
        writeLocation(q,l);
        save(); return spawn(id);
    }

    public boolean attach(String id, int npcId) {
        Location npc = resolveNpcLocation(npcId);
        if (npc == null) return false;
        ensureDefaults(id);
        String p="holograms."+id;
        cfg.set(p+".type","NPC_ATTACHED");
        clearAttachmentData(p);
        cfg.set(p+".npc-id",npcId);
        cfg.set(p+".offset.x",0.0);
        cfg.set(p+".offset.y",2.7);
        cfg.set(p+".offset.z",0.0);
        save();
        return spawn(id);
    }

    public boolean attachBlock(String id, Location blockLocation) {
        if (blockLocation == null || blockLocation.getWorld() == null) return false;
        ensureDefaults(id);
        String p = "holograms." + id;
        cfg.set(p + ".type", "BLOCK_ATTACHED");
        clearAttachmentData(p);
        writeBlockLocation(p + ".block-location", blockLocation);
        setDefaultBlockOffset(p);
        save();
        return spawn(id);
    }

    public boolean attachCrate(String id, Location blockLocation) {
        if (blockLocation == null || blockLocation.getWorld() == null || plugin.crates() == null) return false;
        String crateId = plugin.crates().crateAt(blockLocation).orElse(null);
        if (crateId == null || !blockLocation.getBlock().getType().name().endsWith("SHULKER_BOX")) return false;
        ensureDefaults(id);
        String p = "holograms." + id;
        cfg.set(p + ".type", "CRATE_ATTACHED");
        clearAttachmentData(p);
        writeBlockLocation(p + ".block-location", blockLocation);
        cfg.set(p + ".crate-id", crateId);
        setDefaultBlockOffset(p);
        save();
        return spawn(id);
    }

    public boolean detach(String id) {
        if (!isAttached(id)) return false;
        Location current = resolveBaseLocation(id);
        if (current == null) return false;
        String p="holograms."+id;
        cfg.set(p+".type","STATIC");
        clearAttachmentData(p);
        writeLocation(p+".location",current);
        save();
        return spawn(id);
    }

    public boolean setOffset(String id,double x,double y,double z) {
        String p="holograms."+id;
        if (!cfg.isConfigurationSection(p)) return false;
        cfg.set(p+".offset.x",x);
        cfg.set(p+".offset.y",y);
        cfg.set(p+".offset.z",z);
        save();
        return spawn(id);
    }

    private boolean isAttached(String id) {
        String type = cfg.getString("holograms." + id + ".type", "STATIC");
        return !"STATIC".equalsIgnoreCase(type);
    }

    private Location resolveBaseLocation(String id) {
        String p = "holograms." + id;
        String type = cfg.getString(p + ".type", "STATIC").toUpperCase(Locale.ROOT);
        if ("NPC_ATTACHED".equals(type)) {
            int npcId = cfg.getInt(p + ".npc-id", -1);
            Location npc = resolveNpcLocation(npcId);
            if (npc == null) return null;
            return npc.clone().add(
                    cfg.getDouble(p + ".offset.x", 0),
                    cfg.getDouble(p + ".offset.y", 2.7),
                    cfg.getDouble(p + ".offset.z", 0));
        }
        if ("BLOCK_ATTACHED".equals(type) || "CRATE_ATTACHED".equals(type)) {
            Location block = readBlockLocation(p + ".block-location");
            if (block == null || block.getWorld() == null || block.getBlock().getType().isAir()) return null;
            if ("CRATE_ATTACHED".equals(type)) {
                if (plugin.crates() == null) return null;
                String expected = cfg.getString(p + ".crate-id", "");
                String actual = plugin.crates().crateAt(block).orElse("");
                if (actual.isBlank() || !actual.equalsIgnoreCase(expected)
                        || !block.getBlock().getType().name().endsWith("SHULKER_BOX")) return null;
            }
            return block.clone().add(
                    cfg.getDouble(p + ".offset.x", 0.5),
                    cfg.getDouble(p + ".offset.y", 1.65),
                    cfg.getDouble(p + ".offset.z", 0.5));
        }
        return readLocation(id);
    }

    private Location resolveNpcLocation(int npcId) {
        if (npcId < 0 || Bukkit.getPluginManager().getPlugin("Citizens") == null) return null;
        try {
            Class<?> apiClass=Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry=apiClass.getMethod("getNPCRegistry").invoke(null);
            Method getById=registry.getClass().getMethod("getById",int.class);
            Object npc=getById.invoke(registry,npcId);
            if(npc==null)return null;

            try {
                Object stored=npc.getClass().getMethod("getStoredLocation").invoke(npc);
                if(stored instanceof Location location && location.getWorld()!=null)return location.clone();
            } catch (ReflectiveOperationException ignored) { }

            Object entity=npc.getClass().getMethod("getEntity").invoke(npc);
            if(entity instanceof Entity bukkitEntity)return bukkitEntity.getLocation();
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING,"Citizens-NPC #"+npcId+" konnte nicht gelesen werden.",ex);
        }
        return null;
    }

    private void setDefaultBlockOffset(String path) {
        cfg.set(path + ".offset.x", 0.5);
        cfg.set(path + ".offset.y", 1.65);
        cfg.set(path + ".offset.z", 0.5);
    }

    private void clearAttachmentData(String path) {
        cfg.set(path + ".npc-id", null);
        cfg.set(path + ".block-location", null);
        cfg.set(path + ".crate-id", null);
    }

    private void writeBlockLocation(String path, Location location) {
        cfg.set(path + ".world", location.getWorld().getName());
        cfg.set(path + ".x", location.getBlockX());
        cfg.set(path + ".y", location.getBlockY());
        cfg.set(path + ".z", location.getBlockZ());
    }

    private Location readBlockLocation(String path) {
        ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null) return null;
        World world = Bukkit.getWorld(Objects.requireNonNullElse(section.getString("world"), ""));
        if (world == null) return null;
        return new Location(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private void ensureDefaults(String id) {
        String p="holograms."+id;
        if(cfg.isConfigurationSection(p))return;
        cfg.set(p+".enabled",true); cfg.set(p+".type","STATIC"); cfg.set(p+".billboard","CENTER");
        cfg.set(p+".shadowed",true); cfg.set(p+".see-through",false); cfg.set(p+".background",false);
        cfg.set(p+".line-width",200); cfg.set(p+".view-range",48.0); cfg.set(p+".update-interval-ticks",5);
        cfg.set(p+".line-spacing",0.30); cfg.set(p+".lines",List.of("<gold><bold>"+id.toUpperCase(Locale.ROOT)+"</bold>","<gray>Klicke, um zu spielen</gray>"));
    }

    private void writeLocation(String path,Location l) {
        cfg.set(path+".world",l.getWorld().getName()); cfg.set(path+".x",l.getX()); cfg.set(path+".y",l.getY()); cfg.set(path+".z",l.getZ()); cfg.set(path+".yaw",l.getYaw()); cfg.set(path+".pitch",l.getPitch());
    }

    public void delete(String id){
        remove(id);
        String path = "holograms." + id;
        if (!cfg.contains(path)) return;
        cfg.set(path, null);
        save();
    }
    public List<String> listIds(){ ConfigurationSection s=cfg.getConfigurationSection("holograms"); return s==null?List.of():new ArrayList<>(s.getKeys(false)); }

    public void remove(String id){
        String key=id.toLowerCase(Locale.ROOT); BukkitTask t=tasks.remove(key); if(t!=null)t.cancel();
        List<UUID> list=entities.remove(key); if(list!=null)for(UUID u:list){Entity e=Bukkit.getEntity(u);if(e!=null)e.remove();}
        String tag=TAG+"_"+key; for(World w:Bukkit.getWorlds())for(TextDisplay d:w.getEntitiesByClass(TextDisplay.class))if(d.getScoreboardTags().contains(tag))d.remove();
    }

    public int activeCount() {
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return 0;
        int active = 0;
        for (String id : root.getKeys(false)) {
            if (cfg.getBoolean("holograms." + id + ".enabled", true)
                    && isActive(id.toLowerCase(Locale.ROOT))) active++;
        }
        return active;
    }

    public List<String> unresolvedDescriptions() {
        ConfigurationSection root = cfg.getConfigurationSection("holograms");
        if (root == null) return List.of();
        List<String> unresolved = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            String p = "holograms." + id;
            if (!cfg.getBoolean(p + ".enabled", true)) continue;
            Location resolved = resolveBaseLocation(id);
            if (resolved != null && !cfg.getStringList(p + ".lines").isEmpty()) continue;
            if (resolved != null) {
                unresolved.add(id + " [keine Textzeilen konfiguriert]");
                continue;
            }
            String type = cfg.getString(p + ".type", "STATIC").toUpperCase(Locale.ROOT);
            String detail;
            if ("NPC_ATTACHED".equals(type)) {
                detail = "NPC #" + cfg.getInt(p + ".npc-id", -1);
            } else if ("BLOCK_ATTACHED".equals(type) || "CRATE_ATTACHED".equals(type)) {
                String world = cfg.getString(p + ".block-location.world", "?");
                detail = world + " " + cfg.getInt(p + ".block-location.x") + " "
                        + cfg.getInt(p + ".block-location.y") + " " + cfg.getInt(p + ".block-location.z");
                if ("CRATE_ATTACHED".equals(type)) detail += " / crate=" + cfg.getString(p + ".crate-id", "?");
            } else {
                detail = cfg.getString(p + ".location.world", "?") + " (statisch)";
            }
            unresolved.add(id + " [" + type + ": " + detail + "]");
        }
        return List.copyOf(unresolved);
    }

    public void removeAll(){ for(BukkitTask t:tasks.values())t.cancel();tasks.clear();entities.clear();for(World w:Bukkit.getWorlds())for(TextDisplay d:w.getEntitiesByClass(TextDisplay.class))if(d.getScoreboardTags().contains(TAG))d.remove(); }

    private Location readLocation(String id){
        ConfigurationSection s=cfg.getConfigurationSection("holograms."+id+".location"); if(s==null)return null;
        World w=Bukkit.getWorld(Objects.requireNonNullElse(s.getString("world"),"")); if(w==null)return null;
        return new Location(w,s.getDouble("x"),s.getDouble("y"),s.getDouble("z"),(float)s.getDouble("yaw"),(float)s.getDouble("pitch"));
    }

    private Display.Billboard readBillboard(String id){
        try{return Display.Billboard.valueOf(cfg.getString("holograms."+id+".billboard","CENTER").toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){return Display.Billboard.CENTER;}
    }

    private void save(){try{cfg.save(file);}catch(IOException e){plugin.getLogger().log(Level.SEVERE,"holograms.yml konnte nicht gespeichert werden",e);}}
}
