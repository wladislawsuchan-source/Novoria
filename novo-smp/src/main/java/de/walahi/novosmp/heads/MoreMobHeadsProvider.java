package de.walahi.novosmp.heads;

import de.walahi.smpcore.SMPCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Uses MoreMobHeads as the canonical mob-head texture provider.
 *
 * MoreMobHeads ships a maintained, version-aware mob-head catalogue (including modern variants)
 * and creates the actual ItemStacks locally. NovoSMP only reads that already-loaded catalogue;
 * it does not scrape decorative-head databases or guess a texture from fuzzy global search results.
 * Reflection keeps MoreMobHeads optional at compile time while plugin.yml softdepend guarantees the
 * correct load order when it is installed.
 */
public final class MoreMobHeadsProvider {

    /**
     * Explicit, reviewed overrides for heads that are missing/outdated in the currently installed
     * MoreMobHeads catalogue. These are fixed textures.minecraft.net values from Minecraft-Heads,
     * not fuzzy search results. Keeping this list tiny avoids the old wrong-head problem entirely.
     */
    private static final Map<String, String> DIRECT_TEXTURE_URLS = Map.of(
            // Closed/complete vanilla-looking Shulker (shell included).
            "SHULKER", "https://textures.minecraft.net/texture/f2c642cfe33814767d692d27599c8bef4f5a306fc210d4b50a580b7040f02b18",
            // Force the normal black Wither appearance. MoreMobHeads also contains armored /
            // invulnerable blue-ish Wither forms and its generic resolver can otherwise select one.
            "WITHER", "https://textures.minecraft.net/texture/cdf74e323ed41436965f5c57ddf2815d5332fe999e68fbb9d6cf5c8bd4139f",
            // Minecraft 26.2 Sulfur Cube; keep the reviewed fallback until the provider ships it.
            "SULFUR_CUBE", "https://textures.minecraft.net/texture/f0d9056ec6db388af12304ef96ffdc8228dcf368ab255323258b716f990b4ab"
    );

    /** Exact reviewed per-collection-entry fallbacks when MoreMobHeads does not yet ship a new variant. */
    private static final Map<String, String> DIRECT_DEFINITION_TEXTURE_URLS = Map.of(
            // Minecraft 26.1 Baby Drowned. MoreMobHeads 1.0.45 has Baby Zombie/Husk and
            // Baby Zombie Villagers, but its drowned.json still only contains the adult head.
            "drowned:baby", "https://textures.minecraft.net/texture/4b6595a6e01fcf9620038f3aa829e3ca83e8f82834f4f9d66c5df23187456554"
    );

    private static final Set<String> BABY_WORDS = Set.of(
            "baby", "chick", "calf", "piglet", "lamb", "kitten", "kit", "pup", "puppy",
            "foal", "cria", "kid", "cub", "juvenile");

    private static final Map<String, List<String>> ENTITY_ALIASES = Map.ofEntries(
            Map.entry("MUSHROOM_COW", List.of("mooshroom", "mushroom cow")),
            Map.entry("MOOSHROOM", List.of("mooshroom", "mushroom cow")),
            Map.entry("ZOMBIFIED_PIGLIN", List.of("zombified piglin", "zombie piglin")),
            Map.entry("PIGLIN_BRUTE", List.of("piglin brute")),
            Map.entry("WITHER_SKELETON", List.of("wither skeleton")),
            Map.entry("ELDER_GUARDIAN", List.of("elder guardian")),
            Map.entry("ENDER_DRAGON", List.of("ender dragon", "dragon")),
            Map.entry("CAVE_SPIDER", List.of("cave spider")),
            Map.entry("GLOW_SQUID", List.of("glow squid")),
            Map.entry("SNOW_GOLEM", List.of("snow golem")),
            Map.entry("IRON_GOLEM", List.of("iron golem")),
            Map.entry("WANDERING_TRADER", List.of("wandering trader")),
            Map.entry("ZOMBIE_VILLAGER", List.of("zombie villager")),
            Map.entry("TRADER_LLAMA", List.of("trader llama")),
            Map.entry("TROPICAL_FISH", List.of("tropical fish")),
            Map.entry("CAMEL_HUSK", List.of("camel husk")),
            Map.entry("ZOMBIE_NAUTILUS", List.of("zombie nautilus")),
            Map.entry("COPPER_GOLEM", List.of("copper golem")),
            Map.entry("HAPPY_GHAST", List.of("happy ghast")),
            Map.entry("SULFUR_CUBE", List.of("sulfur cube"))
    );

    private static final Map<String, List<String>> BABY_ENTITY_ALIASES = Map.ofEntries(
            Map.entry("ZOMBIE", List.of("baby zombie")),
            Map.entry("HUSK", List.of("baby husk")),
            Map.entry("DROWNED", List.of("baby drowned")),
            Map.entry("ZOMBIE_VILLAGER", List.of("baby zombie villager")),
            Map.entry("CHICKEN", List.of("chick", "baby chicken")),
            Map.entry("COW", List.of("calf", "baby cow")),
            Map.entry("PIG", List.of("piglet", "baby pig")),
            Map.entry("SHEEP", List.of("lamb", "baby sheep")),
            Map.entry("CAT", List.of("kitten", "baby cat")),
            Map.entry("WOLF", List.of("wolf pup", "pup", "baby wolf")),
            Map.entry("FOX", List.of("fox kit", "kit", "baby fox")),
            Map.entry("RABBIT", List.of("bunny", "baby rabbit")),
            Map.entry("HORSE", List.of("foal", "baby horse")),
            Map.entry("DONKEY", List.of("donkey foal", "baby donkey")),
            Map.entry("MULE", List.of("mule foal", "baby mule")),
            Map.entry("LLAMA", List.of("llama cria", "cria", "baby llama")),
            Map.entry("CAMEL", List.of("camel calf", "baby camel")),
            Map.entry("GOAT", List.of("goat kid", "kid", "baby goat")),
            Map.entry("PANDA", List.of("panda cub", "baby panda")),
            Map.entry("POLAR_BEAR", List.of("polar bear cub", "baby polar bear")),
            Map.entry("BEE", List.of("baby bee")),
            Map.entry("TURTLE", List.of("baby turtle")),
            Map.entry("AXOLOTL", List.of("baby axolotl")),
            Map.entry("ARMADILLO", List.of("baby armadillo")),
            Map.entry("DOLPHIN", List.of("baby dolphin")),
            Map.entry("SQUID", List.of("baby squid")),
            Map.entry("GLOW_SQUID", List.of("baby glow squid")),
            Map.entry("STRIDER", List.of("baby strider")),
            Map.entry("HOGLIN", List.of("baby hoglin")),
            Map.entry("OCELOT", List.of("baby ocelot")),
            Map.entry("NAUTILUS", List.of("baby nautilus")),
            Map.entry("SNIFFER", List.of("snifflet", "baby sniffer"))
    );

    private final SMPCorePlugin plugin;
    private final Map<String, Entry> byExact = new ConcurrentHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, ItemStack> resolved = new ConcurrentHashMap<>();
    private final Set<String> unresolvedLogged = ConcurrentHashMap.newKeySet();
    private volatile boolean ready;

    public MoreMobHeadsProvider(SMPCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean load() {
        byExact.clear();
        entries.clear();
        resolved.clear();
        unresolvedLogged.clear();
        ready = false;

        Plugin provider = Bukkit.getPluginManager().getPlugin("MoreMobHeads");
        if (provider == null) {
            plugin.getLogger().severe("[Kopfsammlung] MoreMobHeads fehlt. Die Kopfsammlung wird bewusst nicht mit geratenen/falschen Texturen betrieben.");
            return false;
        }
        if (!provider.isEnabled()) {
            plugin.getLogger().severe("[Kopfsammlung] MoreMobHeads ist nicht aktiviert. Die Kopfsammlung wird ohne Kopf-Katalog nicht gestartet.");
            return false;
        }

        try {
            Field headManagerField = provider.getClass().getField("headManager");
            Object headManager = headManagerField.get(provider);
            if (headManager == null) throw new IllegalStateException("headManager ist noch nicht initialisiert");

            Method loadedMethod = headManager.getClass().getMethod("getLoadedMobHeads");
            Object raw = loadedMethod.invoke(headManager);
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
                throw new IllegalStateException("MoreMobHeads hat keine Mobköpfe geladen");
            }

            for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                Object mobHead = mapEntry.getValue();
                if (mobHead == null) continue;
                Method getHead = mobHead.getClass().getMethod("getHead");
                Method getDisplayName = mobHead.getClass().getMethod("getDisplayName");
                Method getLangKey = mobHead.getClass().getMethod("getLangKey");
                ItemStack item = (ItemStack) getHead.invoke(mobHead);
                if (item == null || item.getType().isAir()) continue;
                String displayName = String.valueOf(getDisplayName.invoke(mobHead));
                String langKey = String.valueOf(getLangKey.invoke(mobHead));
                String mapKey = String.valueOf(mapEntry.getKey());
                ItemStack safeItem = sanitizeProviderHead(mapKey, item);
                if (safeItem == null) continue;
                Entry entry = new Entry(mapKey, langKey, displayName, safeItem);
                entries.add(entry);
                index(entry, mapKey);
                index(entry, langKey);
                index(entry, displayName);
            }

            ready = !entries.isEmpty();
            return ready;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "[Kopfsammlung] MoreMobHeads konnte nicht als Kopf-Provider eingebunden werden. Es werden keine geratenen Ersatztexturen verwendet.", exception);
            return false;
        }
    }

    public boolean ready() {
        return ready;
    }

    public boolean has(HeadDefinition definition) {
        return resolve(definition) != null;
    }

    public ItemStack head(HeadDefinition definition) {
        ItemStack item = resolve(definition);
        return item == null ? null : item.clone();
    }

    private ItemStack resolve(HeadDefinition definition) {
        if (!ready || definition == null) return null;
        ItemStack cached = resolved.get(definition.id());
        if (cached != null) return cached;

        // Exact per-definition fallback first (for example the 26.1 Baby Drowned).
        String directDefinition = DIRECT_DEFINITION_TEXTURE_URLS.get(definition.id());
        if (directDefinition != null) {
            ItemStack head = fixedTextureHead(definition.id(), directDefinition);
            if (head != null) return remember(definition, head);
        }

        // Reviewed entity-level direct overrides are checked before MoreMobHeads so known bad/missing entries
        // (currently Shulker shell + 26.2 Sulfur Cube) always use the intended exact texture.
        if (!definition.baby() && "BASE".equalsIgnoreCase(definition.variant())) {
            String direct = DIRECT_TEXTURE_URLS.get(definition.entityType());
            if (direct != null) {
                ItemStack head = fixedTextureHead(definition.id(), direct);
                if (head != null) return remember(definition, head);
            }
        }

        // 1) Exact MoreMobHeads lang-key candidates. This covers virtually all normal variants.
        for (String candidate : exactCandidates(definition)) {
            Entry entry = byExact.get(normalize(candidate));
            if (entry != null && babyCompatible(definition, entry) && matchesFamily(definition, entry)) {
                return remember(definition, entry.head());
            }
        }

        // 2) Exact display-name/query match.
        Entry exact = byExact.get(normalize(definition.textureQuery()));
        if (exact != null && babyCompatible(definition, exact) && matchesFamily(definition, exact)) {
            return remember(definition, exact.head());
        }

        // 3) Conservative matching ONLY inside MoreMobHeads' mob-only catalogue. Unlike the old
        // decorative CSV resolver this can never choose an unrelated letter/plushie/decorative head.
        Set<String> wantedFamily = familyTokens(definition);
        Set<String> wantedVariant = variantTokens(definition.variant());
        Entry best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean ambiguous = false;
        for (Entry entry : entries) {
            if (!babyCompatible(definition, entry)) continue;
            Set<String> tokens = entry.tokens();
            if (!matchesFamily(definition, entry)) continue;
            if (!wantedVariant.isEmpty() && !matchesVariant(tokens, wantedVariant)) continue;

            int score = 0;
            for (String token : tokenSet(definition.textureQuery())) if (tokens.contains(token)) score += 4;
            for (String token : wantedFamily) if (tokens.contains(token)) score += 6;
            for (String token : wantedVariant) if (tokens.contains(token)) score += 5;
            if (normalize(entry.displayName()).equals(normalize(definition.textureQuery()))) score += 100;
            if (score > bestScore) {
                best = entry;
                bestScore = score;
                ambiguous = false;
            } else if (score == bestScore) {
                ambiguous = true;
            }
        }
        if (best != null && !ambiguous) return remember(definition, best.head());

        if (unresolvedLogged.add(definition.id())) {
            // Optional catalogue variants can legitimately be absent from the installed MoreMobHeads build.
            // Keep this at FINE so normal startup logs stay clean while the detail remains available for debugging.
            plugin.getLogger().fine("[Kopfsammlung] Kein eindeutiger MoreMobHeads-Kopf für '" + definition.id()
                    + "' (" + definition.textureQuery() + "). Eintrag wird ausgeblendet statt eine falsche Textur zu zeigen.");
        }
        return null;
    }

    /**
     * Rebuilds MoreMobHeads PLAYER_HEAD profiles from their already-loaded skin URL. This makes
     * every catalogue head self-contained and prevents Paper from trying to complete provider
     * UUIDs through Mojang's session service while collection GUIs are being rendered.
     *
     * A provider entry without a locally available texture is skipped instead of retaining an
     * incomplete profile that can create request storms / HTTP 429 warnings. Vanilla skull
     * materials do not need a player profile and pass through unchanged.
     */
    private ItemStack sanitizeProviderHead(String stableKey, ItemStack source) {
        ItemStack clone = source.clone();
        if (clone.getType() != Material.PLAYER_HEAD) return clone;
        if (!(clone.getItemMeta() instanceof SkullMeta meta)) return null;

        PlayerProfile sourceProfile = meta.getOwnerProfile();
        URL skin = sourceProfile == null ? null : sourceProfile.getTextures().getSkin();
        if (skin == null) {
            plugin.getLogger().fine("[Kopfsammlung] Provider-Kopf ohne lokale Textur übersprungen: " + stableKey);
            return null;
        }

        UUID localId = UUID.nameUUIDFromBytes(("novoria-provider-head:" + stableKey + ":" + skin)
                .getBytes(StandardCharsets.UTF_8));
        PlayerProfile localProfile = Bukkit.createPlayerProfile(localId, "NovoriaMob");
        PlayerTextures textures = localProfile.getTextures();
        textures.setSkin(skin);
        localProfile.setTextures(textures);
        meta.setOwnerProfile(localProfile);
        clone.setItemMeta(meta);
        return clone;
    }

    private ItemStack fixedTextureHead(String id, String url) {
        try {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta == null) return null;
            UUID uuid = UUID.nameUUIDFromBytes(("novoria-head:" + id).getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createPlayerProfile(uuid, "");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(URI.create(url).toURL());
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
            return head;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "[Kopfsammlung] Feste Textur konnte nicht geladen werden: " + id, exception);
            return null;
        }
    }

    private ItemStack remember(HeadDefinition definition, ItemStack head) {
        ItemStack clone = head.clone();
        resolved.put(definition.id(), clone);
        return clone;
    }

    private List<String> exactCandidates(HeadDefinition definition) {
        List<String> candidates = new ArrayList<>();
        String type = definition.entityType().toLowerCase(Locale.ROOT);
        String variant = definition.variant().toLowerCase(Locale.ROOT);

        // MoreMobHeads stores the profession-neutral normal Villager under .none,
        // including the seven baby-biome heads. The old generic key did not exist.
        if ("VILLAGER".equals(definition.entityType()) && !"base".equals(variant)) {
            candidates.add(definition.baby()
                    ? "entity.villager.baby." + variant + ".none"
                    : "entity.villager." + variant + ".none");
        }

        // Zombie Villagers are nested by biome and profession in MoreMobHeads. Novoria deliberately
        // collects the seven biome looks (not 100+ biome/profession combinations), so always select
        // the profession-neutral "none" texture. Baby variants use baby.<biome>.none.
        if ("ZOMBIE_VILLAGER".equals(definition.entityType()) && !"base".equals(variant)) {
            // MoreMobHeads calls the adult snowy biome "snow", but the new 26.1 baby entry "snowy".
            String providerVariant = definition.baby() && "snow".equals(variant) ? "snowy" : variant;
            if (definition.baby()) candidates.add("entity.zombie_villager.baby." + providerVariant + ".none");
            else candidates.add("entity.zombie_villager." + providerVariant + ".none");
        }

        if (!definition.baby()) {
            if ("base".equals(variant)) {
                candidates.add("entity." + type);
            } else {
                candidates.add("entity." + type + "." + variant);
                // A few files use 'normal' for their base-looking variant.
                if ("normal".equals(variant)) candidates.add("entity." + type);
            }
        } else {
            for (String alias : BABY_ENTITY_ALIASES.getOrDefault(definition.entityType(), List.of("baby " + type))) {
                String compact = normalizeKey(alias);
                if ("base".equals(variant)) candidates.add("entity." + compact);
                else candidates.add("entity." + compact + "." + variant);
            }
            if ("base".equals(variant)) {
                candidates.add("entity." + type + ".baby");
                candidates.add("entity.baby_" + type);
            } else {
                candidates.add("entity." + type + "." + variant + ".baby");
                candidates.add("entity." + type + ".baby." + variant);
            }
        }
        return candidates;
    }

    private boolean babyCompatible(HeadDefinition definition, Entry entry) {
        boolean entryBaby = entry.tokens().stream().anyMatch(BABY_WORDS::contains);
        if (definition.baby()) return entryBaby;
        return !entryBaby;
    }

    private Set<String> familyTokens(HeadDefinition definition) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String alias : ENTITY_ALIASES.getOrDefault(definition.entityType(),
                List.of(definition.entityType().replace('_', ' ').toLowerCase(Locale.ROOT)))) {
            result.addAll(tokenSet(alias));
        }
        if (definition.baby()) {
            for (String alias : BABY_ENTITY_ALIASES.getOrDefault(definition.entityType(), List.of())) {
                Set<String> aliasTokens = tokenSet(alias);
                aliasTokens.removeAll(BABY_WORDS);
                result.addAll(aliasTokens);
            }
        }
        return result;
    }

    private boolean matchesFamily(HeadDefinition definition, Entry entry) {
        String providerFamily = entry.familyKey();
        if (providerFamily.isEmpty()) return false;
        Set<String> accepted = new LinkedHashSet<>();
        accepted.add(definition.entityType().toLowerCase(Locale.ROOT));
        for (String alias : ENTITY_ALIASES.getOrDefault(definition.entityType(), List.of())) {
            accepted.add(normalizeKey(alias));
        }
        if (definition.baby()) {
            for (String alias : BABY_ENTITY_ALIASES.getOrDefault(definition.entityType(), List.of())) {
                accepted.add(normalizeKey(alias));
            }
        }
        return accepted.contains(providerFamily);
    }

    private Set<String> variantTokens(String variantRaw) {
        if (variantRaw == null || variantRaw.equalsIgnoreCase("BASE") || variantRaw.equalsIgnoreCase("NORMAL")) return Set.of();
        String normalized = normalize(variantRaw);
        LinkedHashSet<String> result = new LinkedHashSet<>(tokenSet(normalized));
        // Bukkit/internal names vs common MoreMobHeads names.
        if (normalized.equals("lucy")) result.add("lucy");
        if (normalized.equals("all black")) result.add("black");
        if (normalized.equals("black and white")) { result.add("black"); result.add("white"); }
        if (normalized.equals("salt and pepper")) { result.add("salty"); }
        if (normalized.equals("creamy")) result.add("cream");
        if (normalized.equals("snow")) result.add("snowy");
        return result;
    }

    private boolean matchesVariant(Set<String> entryTokens, Set<String> wantedVariant) {
        for (String token : wantedVariant) if (entryTokens.contains(token)) return true;
        return false;
    }

    private void index(Entry entry, String raw) {
        String key = normalize(raw);
        if (!key.isBlank()) byExact.putIfAbsent(key, entry);
    }

    private String normalizeKey(String raw) {
        return normalize(raw).replace(' ', '_');
    }

    private Set<String> tokenSet(String raw) {
        String normalized = normalize(raw);
        if (normalized.isBlank()) return new LinkedHashSet<>();
        return new LinkedHashSet<>(List.of(normalized.split("\\s+")));
    }

    private String normalize(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT)
                .replace("entity.", "")
                .replace('ä', 'a').replace('ö', 'o').replace('ü', 'u')
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private record Entry(String mapKey, String langKey, String displayName, ItemStack head) {
        String familyKey() {
            String key = langKey == null ? "" : langKey.trim().toLowerCase(Locale.ROOT);
            if (!key.startsWith("entity.")) return "";
            String rest = key.substring("entity.".length());
            int separator = rest.indexOf('.');
            return separator < 0 ? rest : rest.substring(0, separator);
        }
        Set<String> tokens() {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            add(out, mapKey); add(out, langKey); add(out, displayName);
            return out;
        }
        private static void add(Set<String> target, String raw) {
            if (raw == null) return;
            String normalized = raw.toLowerCase(Locale.ROOT).replace("entity.", "")
                    .replaceAll("[^a-z0-9]+", " ").trim();
            if (!normalized.isBlank()) target.addAll(List.of(normalized.split("\\s+")));
        }
    }
}
