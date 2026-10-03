package de.walahi.novosmp.heads;

import de.walahi.novosmp.professions.HunterListener;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataHolder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Curated mob-head catalogue. Exact skin hashes are resolved separately at runtime. */
public final class HeadCatalog {
    private final Map<String, HeadDefinition> byId = new LinkedHashMap<>();
    private final Map<String, List<HeadDefinition>> byFamily = new LinkedHashMap<>();
    private final Map<String, List<HeadDefinition>> byEntityType = new HashMap<>();

    private static final Set<String> EXCLUDED = Set.of(
            "PLAYER", "ARMOR_STAND", "MARKER", "UNKNOWN",
            // Zoglins and tropical fish are intentionally not part of the collectible head list.
            "ZOGLIN", "TROPICAL_FISH",
            // Not legitimately collectible in normal Survival gameplay.
            "GIANT", "ILLUSIONER", "MANNEQUIN",
            // Modern Bukkit exposes MOOSHROOM; keep the legacy alias from creating a duplicate family entry.
            "MUSHROOM_COW");
    private static final Set<String> HOSTILE_BABY_NOT_SEPARATE = Set.of(
            "PIGLIN", "ZOMBIFIED_PIGLIN", "PIGLIN_BRUTE", "ZOGLIN", "CAMEL_HUSK", "PARCHED");
    private static final Set<String> NETHER = Set.of(
            "BLAZE", "GHAST", "MAGMA_CUBE", "PIGLIN", "PIGLIN_BRUTE", "HOGLIN", "ZOGLIN",
            "STRIDER", "WITHER_SKELETON", "ZOMBIFIED_PIGLIN", "WITHER");
    private static final Set<String> END = Set.of("ENDERMAN", "ENDERMITE", "SHULKER", "ENDER_DRAGON");
    private static final Set<String> WATER = Set.of(
            "COD", "SALMON", "PUFFERFISH", "SQUID", "GLOW_SQUID", "DOLPHIN",
            "TURTLE", "AXOLOTL", "TADPOLE", "GUARDIAN", "ELDER_GUARDIAN", "NAUTILUS", "ZOMBIE_NAUTILUS");
    private static final Set<String> SPECIAL = Set.of(
            "WITHER", "WARDEN", "ELDER_GUARDIAN", "RAVAGER", "EVOKER", "IRON_GOLEM",
            "SNOW_GOLEM", "COPPER_GOLEM", "ALLAY", "SNIFFER",
            "VILLAGER", "WANDERING_TRADER");
    private static final Set<String> BABY_BASE = Set.of(
            "ZOMBIE", "HUSK", "DROWNED",
            "GOAT", "CAMEL", "BEE", "DOLPHIN", "SQUID", "GLOW_SQUID", "TURTLE", "ARMADILLO",
            "SNIFFER", "STRIDER", "HOGLIN", "OCELOT", "NAUTILUS", "DONKEY", "MULE", "POLAR_BEAR");

    public HeadCatalog() {
        build();
    }

    public Collection<HeadDefinition> all() { return List.copyOf(byId.values()); }
    public HeadDefinition byId(String id) { return byId.get(id); }
    public List<HeadDefinition> family(String familyId) { return byFamily.getOrDefault(familyId, List.of()); }
    public List<HeadDefinition> category(HeadCategory category) {
        return byId.values().stream().filter(def -> def.category() == category)
                .sorted(Comparator.comparing(HeadDefinition::familyId).thenComparing(HeadDefinition::displayName))
                .toList();
    }

    public List<String> familyIds(HeadCategory category) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (HeadDefinition def : category(category)) ids.add(def.familyId());
        return List.copyOf(ids);
    }

    public HeadDefinition resolve(LivingEntity entity) {
        if (entity == null) return null;
        String runtimeType = entity.getType().name();
        String type = canonicalEntityType(runtimeType);
        List<HeadDefinition> candidates = byEntityType.get(type);
        if (candidates == null || candidates.isEmpty()) return null;
        boolean baby = HunterListener.isBaby(entity) && !HOSTILE_BABY_NOT_SEPARATE.contains(runtimeType);
        String variant = variantOf(entity);

        HeadDefinition exact = find(candidates, variant, baby);
        if (exact != null) return exact;
        if (baby) {
            HeadDefinition adult = find(candidates, variant, false);
            if (adult != null) return adult;
        }
        HeadDefinition base = find(candidates, "BASE", baby);
        if (base != null) return base;
        base = find(candidates, "BASE", false);
        return base != null ? base : candidates.get(0);
    }

    private HeadDefinition find(List<HeadDefinition> defs, String variant, boolean baby) {
        String wanted = normalizeVariant(variant);
        for (HeadDefinition def : defs) {
            if (def.baby() == baby && normalizeVariant(def.variant()).equals(wanted)) return def;
        }
        return null;
    }

    private void build() {
        Set<String> explicitlyVariant = new LinkedHashSet<>();
        addColorFamily("SHEEP", "Schaf", "Sheep", dyeColors(), true, explicitlyVariant);
        addSimpleVariants("AXOLOTL", "Axolotl", Map.of(
                "LUCY", "Leuzistisch", "WILD", "Wild", "GOLD", "Gold", "CYAN", "Cyan", "BLUE", "Blau"), true, explicitlyVariant);
        addSimpleVariants("CAT", "Katze", orderedMap(
                "TABBY","Getigert", "BLACK","Schwarz", "RED","Rot", "SIAMESE","Siam",
                "BRITISH_SHORTHAIR","Britisch Kurzhaar", "CALICO","Calico", "PERSIAN","Perser",
                "RAGDOLL","Ragdoll", "WHITE","Weiß", "JELLIE","Jellie", "ALL_BLACK","Tiefschwarz"), true, explicitlyVariant);
        addSimpleVariants("WOLF", "Wolf", orderedMap(
                "PALE","Blass", "SPOTTED","Gefleckt", "SNOWY","Schnee", "BLACK","Schwarz",
                "ASHEN","Aschgrau", "RUSTY","Rostrot", "WOODS","Wald", "CHESTNUT","Kastanie", "STRIPED","Gestreift"), true, explicitlyVariant);
        addTemperatureFamily("COW", "Kuh", true, explicitlyVariant);
        addTemperatureFamily("PIG", "Schwein", true, explicitlyVariant);
        addTemperatureFamily("CHICKEN", "Huhn", true, explicitlyVariant);
        addSimpleVariants("MOOSHROOM", "Mooshroom", orderedMap("RED","Rot", "BROWN","Braun"), true, explicitlyVariant);
        addSimpleVariants("FOX", "Fuchs", orderedMap("RED","Rot", "SNOW","Schnee"), true, explicitlyVariant);
        addSimpleVariants("FROG", "Frosch", orderedMap("TEMPERATE","Gemäßigt", "COLD","Kalt", "WARM","Warm"), false, explicitlyVariant);
        addSimpleVariants("RABBIT", "Kaninchen", orderedMap(
                "BROWN","Braun", "WHITE","Weiß", "BLACK","Schwarz", "BLACK_AND_WHITE","Schwarz-Weiß",
                "GOLD","Gold", "SALT_AND_PEPPER","Salz & Pfeffer"), true, explicitlyVariant);
        addSimpleVariants("PANDA", "Panda", orderedMap(
                "NORMAL","Normal", "LAZY","Faul", "WORRIED","Besorgt", "PLAYFUL","Verspielt",
                "BROWN","Braun", "WEAK","Schwach", "AGGRESSIVE","Aggressiv"), true, explicitlyVariant);
        addSimpleVariants("PARROT", "Papagei", orderedMap(
                "RED","Rot", "BLUE","Blau", "GREEN","Grün", "CYAN","Cyan", "GRAY","Grau"), false, explicitlyVariant);
        addSimpleVariants("HORSE", "Pferd", orderedMap(
                "WHITE","Weiß", "CREAMY","Creme", "CHESTNUT","Fuchs", "BROWN","Braun",
                "BLACK","Schwarz", "GRAY","Grau", "DARK_BROWN","Dunkelbraun"), true, explicitlyVariant);
        addSimpleVariants("LLAMA", "Lama", orderedMap(
                "CREAMY","Creme", "WHITE","Weiß", "BROWN","Braun", "GRAY","Grau"), true, explicitlyVariant);
        addSimpleVariants("TRADER_LLAMA", "Händlerlama", orderedMap(
                "CREAMY","Creme", "WHITE","Weiß", "BROWN","Braun", "GRAY","Grau"), false, explicitlyVariant);
        addSimpleVariants("VILLAGER", "Dorfbewohner", orderedMap(
                "DESERT","Wüste", "JUNGLE","Dschungel", "PLAINS","Ebene", "SAVANNA","Savanne",
                "SNOW","Schnee", "SWAMP","Sumpf", "TAIGA","Taiga"), true, explicitlyVariant);
        addSimpleVariants("ZOMBIE_VILLAGER", "Zombiedorfbewohner", orderedMap(
                "DESERT","Wüste", "JUNGLE","Dschungel", "PLAINS","Ebene", "SAVANNA","Savanne",
                "SNOW","Schnee", "SWAMP","Sumpf", "TAIGA","Taiga"), true, explicitlyVariant);
        addSimpleVariants("CREEPER", "Creeper", orderedMap("NORMAL","Normal", "CHARGED","Aufgeladen"), false, explicitlyVariant);
        addSimpleVariants("COPPER_GOLEM", "Kupfergolem", orderedMap(
                "UNAFFECTED","Normal", "EXPOSED","Angelaufen", "WEATHERED","Verwittert", "OXIDIZED","Oxidiert"), false, explicitlyVariant);
        addSimpleVariants("ZOMBIE_NAUTILUS", "Zombie-Nautilus", orderedMap("NORMAL","Normal", "CORAL","Koralle"), false, explicitlyVariant);

        for (EntityType type : EntityType.values()) {
            String name = type.name();
            if (EXCLUDED.contains(name) || explicitlyVariant.contains(name)) continue;
            Class<?> entityClass;
            try { entityClass = type.getEntityClass(); }
            catch (Throwable ignored) { continue; }
            if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) continue;
            String display = germanName(name);
            String query = englishName(name);
            add(new HeadDefinition(id(name, "BASE", false), name.toLowerCase(Locale.ROOT), name, "BASE",
                    false, display, query, categoryFor(name, entityClass)));
            if (BABY_BASE.contains(name) && !HOSTILE_BABY_NOT_SEPARATE.contains(name)) {
                add(new HeadDefinition(id(name, "BASE", true), name.toLowerCase(Locale.ROOT), name, "BASE",
                        true, "Baby-" + display, "Baby " + query, categoryFor(name, entityClass)));
            }
        }
    }

    private void addTemperatureFamily(String type, String german, boolean baby, Set<String> explicit) {
        addSimpleVariants(type, german, orderedMap("TEMPERATE","Gemäßigt", "COLD","Kalt", "WARM","Warm"), baby, explicit);
    }

    private void addColorFamily(String type, String german, String english, List<String> variants,
                                boolean baby, Set<String> explicit) {
        explicit.add(type);
        for (String variant : variants) {
            String colorGerman = germanColor(variant);
            String colorEnglish = title(variant);
            add(new HeadDefinition(id(type, variant, false), type.toLowerCase(Locale.ROOT), type, variant,
                    false, colorGerman + "s " + german, colorEnglish + " " + english, categoryFor(type, null)));
            if (baby) add(new HeadDefinition(id(type, variant, true), type.toLowerCase(Locale.ROOT), type, variant,
                    true, "Baby-" + colorGerman + "s " + german, "Baby " + colorEnglish + " " + english, categoryFor(type, null)));
        }
    }

    private void addSimpleVariants(String type, String german, Map<String,String> variants,
                                   boolean baby, Set<String> explicit) {
        if (!entityTypeExists(type)) return;
        explicit.add(type);
        String english = englishName(type);
        for (Map.Entry<String,String> entry : variants.entrySet()) {
            String variant = entry.getKey();
            String descriptor = entry.getValue();
            String queryDescriptor = englishVariantDescriptor(type, variant);
            String adultQuery = queryDescriptor.isBlank() ? english : queryDescriptor + " " + english;
            add(new HeadDefinition(id(type, variant, false), type.toLowerCase(Locale.ROOT), type, variant,
                    false, descriptor + " • " + german, adultQuery, categoryFor(type, null)));
            if (baby) {
                String babyQuery = babyTextureQuery(type, variant, queryDescriptor, english);
                add(new HeadDefinition(id(type, variant, true), type.toLowerCase(Locale.ROOT), type, variant,
                        true, "Baby • " + descriptor + " • " + german, babyQuery, categoryFor(type, null)));
            }
        }
    }

    private boolean entityTypeExists(String type) {
        try { EntityType.valueOf(type); return true; }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private void add(HeadDefinition definition) {
        String familyId = canonicalFamilyId(definition.entityType(), definition.familyId());
        HeadDefinition normalized = familyId.equals(definition.familyId()) ? definition
                : new HeadDefinition(definition.id(), familyId, definition.entityType(), definition.variant(),
                definition.baby(), definition.displayName(), definition.textureQuery(), definition.category());
        byId.put(normalized.id(), normalized);
        byFamily.computeIfAbsent(normalized.familyId(), ignored -> new ArrayList<>()).add(normalized);
        byEntityType.computeIfAbsent(normalized.entityType(), ignored -> new ArrayList<>()).add(normalized);
    }

    private String canonicalEntityType(String runtimeType) {
        if ("MUSHROOM_COW".equals(runtimeType)) return "MOOSHROOM";
        return runtimeType;
    }

    /** Groups obvious visual mob variants into one collection family instead of duplicating them. */
    private String canonicalFamilyId(String type, String fallback) {
        return switch (type) {
            case "HORSE", "ZOMBIE_HORSE", "SKELETON_HORSE" -> "horse";
            case "COW", "MOOSHROOM", "MUSHROOM_COW" -> "cow";
            case "CAMEL", "CAMEL_HUSK" -> "camel";
            case "ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED" -> "zombie";
            case "SKELETON", "STRAY", "BOGGED", "PARCHED" -> "skeleton";
            case "GHAST", "HAPPY_GHAST", "GHASTLING" -> "ghast";
            case "SPIDER", "CAVE_SPIDER" -> "spider";
            case "PIGLIN", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN" -> "piglin";
            case "LLAMA", "TRADER_LLAMA" -> "llama";
            case "NAUTILUS", "ZOMBIE_NAUTILUS" -> "nautilus";
            default -> fallback;
        };
    }

    private String variantOf(LivingEntity entity) {
        String type = entity.getType().name();
        if ("CREEPER".equals(type)) {
            Object powered = invoke(entity, "isPowered");
            return Boolean.TRUE.equals(powered) ? "CHARGED" : "NORMAL";
        }
        Object value = switch (type) {
            case "SHEEP" -> invoke(entity, "getColor");
            case "CAT" -> first(entity, "getCatType", "getVariant");
            case "FOX" -> first(entity, "getFoxType", "getVariant");
            case "RABBIT" -> first(entity, "getRabbitType", "getVariant");
            case "HORSE" -> first(entity, "getColor", "getVariant");
            case "LLAMA", "TRADER_LLAMA" -> first(entity, "getColor", "getVariant");
            case "VILLAGER", "ZOMBIE_VILLAGER" -> first(entity, "getVillagerType", "getVariant");
            case "PANDA" -> first(entity, "getMainGene", "getVariant");
            case "COPPER_GOLEM" -> first(entity, "getWeatherState", "getOxidationLevel", "getVariant");
            default -> first(entity, "getVariant", "getType");
        };
        return normalizeVariant(value);
    }

    private Object first(Object target, String... methods) {
        for (String method : methods) {
            Object result = invoke(target, method);
            if (result != null) return result;
        }
        return null;
    }

    private Object invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) { return null; }
    }

    private String normalizeVariant(Object value) {
        if (value == null) return "BASE";
        if (value instanceof Enum<?> enumValue) return enumValue.name();
        if (value instanceof org.bukkit.Keyed keyed) return normalizeVariant(keyed.getKey().getKey());
        return normalizeVariant(value.toString());
    }

    private String normalizeVariant(String value) {
        if (value == null || value.isBlank()) return "BASE";
        String result = value.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "")
                .replace('-', '_').replace(' ', '_');
        int colon = result.indexOf(':');
        if (colon >= 0) result = result.substring(colon + 1);
        return result;
    }

    private HeadCategory categoryFor(String type, Class<?> entityClass) {
        // Family/category cleanup: these are visually/semantically grouped with their base mob.
        if (Set.of("BAT", "CAMEL_HUSK", "ZOMBIE_HORSE", "SKELETON_HORSE", "SULFUR_CUBE").contains(type))
            return HeadCategory.ANIMALS;
        if (Set.of("HAPPY_GHAST", "GHASTLING").contains(type)) return HeadCategory.NETHER;
        // End mobs (especially the Ender Dragon) belong to the End collection, not Bosses/Special.
        if (END.contains(type)) return HeadCategory.END;
        if (SPECIAL.contains(type)) return HeadCategory.SPECIAL;
        if (NETHER.contains(type)) return HeadCategory.NETHER;
        if (WATER.contains(type)) return HeadCategory.WATER;
        if (entityClass != null) {
            try {
                Class<?> animals = Class.forName("org.bukkit.entity.Animals");
                if (animals.isAssignableFrom(entityClass)) return HeadCategory.ANIMALS;
            } catch (ClassNotFoundException ignored) { }
        }
        if (Set.of("COW","PIG","CHICKEN","SHEEP","CAT","WOLF","FOX","RABBIT","HORSE","LLAMA","TRADER_LLAMA",
                "CAMEL","GOAT","PANDA","PARROT","MOOSHROOM","MUSHROOM_COW","BEE","ARMADILLO","POLAR_BEAR",
                "FROG").contains(type))
            return HeadCategory.ANIMALS;
        return HeadCategory.MONSTERS;
    }

    private String id(String type, String variant, boolean baby) {
        return type.toLowerCase(Locale.ROOT) + ("BASE".equals(variant) ? "" : ":" + variant.toLowerCase(Locale.ROOT))
                + (baby ? ":baby" : "");
    }

    private static List<String> dyeColors() {
        return List.of("WHITE","ORANGE","MAGENTA","LIGHT_BLUE","YELLOW","LIME","PINK","GRAY",
                "LIGHT_GRAY","CYAN","PURPLE","BLUE","BROWN","GREEN","RED","BLACK");
    }

    private static Map<String,String> orderedMap(String... values) {
        Map<String,String> result = new LinkedHashMap<>();
        for (int i=0; i+1<values.length; i+=2) result.put(values[i], values[i+1]);
        return result;
    }

    private String babyTextureQuery(String type, String variant, String descriptor, String english) {
        if (Set.of("COW", "PIG", "CHICKEN").contains(type) && "TEMPERATE".equals(variant)) {
            return "Baby " + english;
        }
        if ("WOLF".equals(type) && "SNOWY".equals(variant)) {
            return "Baby Snow Wolf";
        }
        return descriptor == null || descriptor.isBlank()
                ? "Baby " + english
                : "Baby " + descriptor + " " + english;
    }

    private String englishVariantDescriptor(String type, String variant) {
        if ("AXOLOTL".equals(type) && "LUCY".equals(variant)) return "Lucy";
        if ("CAT".equals(type) && "RED".equals(variant)) return "Orange Tabby";
        if ("CAT".equals(type) && "BLACK".equals(variant)) return "Tuxedo";
        if ("CAT".equals(type) && "ALL_BLACK".equals(variant)) return "Black";
        if ("RABBIT".equals(type) && "SALT_AND_PEPPER".equals(variant)) return "Salty";
        if ("RABBIT".equals(type) && "BLACK_AND_WHITE".equals(variant)) return "White Splotched";
        if ("WOLF".equals(type) && "SNOWY".equals(variant)) return "Snowy";
        if ("HORSE".equals(type) && "CREAMY".equals(variant)) return "Cream";
        if ("HORSE".equals(type) && "DARK_BROWN".equals(variant)) return "Dark Brown";
        if ("PANDA".equals(type) && "NORMAL".equals(variant)) return "";
        if ("CAT".equals(type) && "BRITISH_SHORTHAIR".equals(variant)) return "British Shorthair";
        if ("COPPER_GOLEM".equals(type)) return switch (variant) {
            case "UNAFFECTED" -> "";
            case "EXPOSED" -> "Exposed";
            case "WEATHERED" -> "Weathered";
            case "OXIDIZED" -> "Oxidized";
            default -> title(variant);
        };
        if ("CREEPER".equals(type) && "NORMAL".equals(variant)) return "";
        if ("CREEPER".equals(type) && "CHARGED".equals(variant)) return "Charged";
        if ("ZOMBIE_NAUTILUS".equals(type) && "NORMAL".equals(variant)) return "";
        if ("ZOMBIE_NAUTILUS".equals(type) && "CORAL".equals(variant)) return "Coral";
        return title(variant);
    }

    private static String englishName(String type) {
        return switch (type) {
            case "MUSHROOM_COW", "MOOSHROOM" -> "Mooshroom";
            case "ZOMBIFIED_PIGLIN" -> "Zombified Piglin";
            case "PIGLIN_BRUTE" -> "Piglin Brute";
            case "WITHER_SKELETON" -> "Wither Skeleton";
            case "ELDER_GUARDIAN" -> "Elder Guardian";
            case "ENDER_DRAGON" -> "Ender Dragon";
            case "CAVE_SPIDER" -> "Cave Spider";
            case "GLOW_SQUID" -> "Glow Squid";
            case "SNOW_GOLEM" -> "Snow Golem";
            case "IRON_GOLEM" -> "Iron Golem";
            case "WANDERING_TRADER" -> "Wandering Trader";
            case "ZOMBIE_VILLAGER" -> "Zombie Villager";
            case "TRADER_LLAMA" -> "Trader Llama";
            case "CAMEL_HUSK" -> "Camel Husk";
            case "ZOMBIE_NAUTILUS" -> "Zombie Nautilus";
            case "COPPER_GOLEM" -> "Copper Golem";
            case "HAPPY_GHAST" -> "Happy Ghast";
            case "SULFUR_CUBE" -> "Sulfur Cube";
            default -> title(type);
        };
    }

    private static String germanName(String type) {
        return switch (type) {
            case "ZOMBIE" -> "Zombie"; case "SKELETON" -> "Skelett"; case "CREEPER" -> "Creeper";
            case "SPIDER" -> "Spinne"; case "CAVE_SPIDER" -> "Höhlenspinne"; case "ENDERMAN" -> "Enderman";
            case "DROWNED" -> "Ertrunkener"; case "HUSK" -> "Wüstenzombie"; case "STRAY" -> "Eiswanderer";
            case "BOGGED" -> "Bogged"; case "WITCH" -> "Hexe"; case "PHANTOM" -> "Phantom";
            case "PILLAGER" -> "Plünderer"; case "VINDICATOR" -> "Diener"; case "EVOKER" -> "Magier";
            case "RAVAGER" -> "Verwüster"; case "VEX" -> "Plagegeist"; case "SLIME" -> "Schleim";
            case "MAGMA_CUBE" -> "Magmawürfel"; case "BLAZE" -> "Lohe"; case "GHAST" -> "Ghast";
            case "WITHER_SKELETON" -> "Witherskelett"; case "WITHER" -> "Wither"; case "WARDEN" -> "Warden";
            case "ENDER_DRAGON" -> "Enderdrache"; case "GUARDIAN" -> "Wächter"; case "ELDER_GUARDIAN" -> "Großer Wächter";
            case "SHULKER" -> "Shulker"; case "BREEZE" -> "Breeze"; case "PIGLIN" -> "Piglin";
            case "PIGLIN_BRUTE" -> "Piglin-Barbar"; case "HOGLIN" -> "Hoglin"; case "ZOGLIN" -> "Zoglin";
            case "ZOMBIFIED_PIGLIN" -> "Zombifizierter Piglin"; case "STRIDER" -> "Schreiter";
            case "COW" -> "Kuh"; case "PIG" -> "Schwein"; case "CHICKEN" -> "Huhn"; case "SHEEP" -> "Schaf";
            case "RABBIT" -> "Kaninchen"; case "WOLF" -> "Wolf"; case "CAT" -> "Katze"; case "FOX" -> "Fuchs";
            case "HORSE" -> "Pferd"; case "DONKEY" -> "Esel"; case "MULE" -> "Maultier"; case "LLAMA" -> "Lama";
            case "CAMEL" -> "Kamel"; case "GOAT" -> "Ziege"; case "PANDA" -> "Panda"; case "PARROT" -> "Papagei";
            case "BEE" -> "Biene"; case "ARMADILLO" -> "Gürteltier"; case "TURTLE" -> "Schildkröte";
            case "DOLPHIN" -> "Delfin"; case "SQUID" -> "Tintenfisch"; case "GLOW_SQUID" -> "Leuchttintenfisch";
            case "AXOLOTL" -> "Axolotl"; case "COD" -> "Kabeljau"; case "SALMON" -> "Lachs"; case "PUFFERFISH" -> "Kugelfisch";
            case "VILLAGER" -> "Dorfbewohner"; case "WANDERING_TRADER" -> "Fahrender Händler";
            case "IRON_GOLEM" -> "Eisengolem"; case "SNOW_GOLEM" -> "Schneegolem"; case "ALLAY" -> "Allay";
            case "SNIFFER" -> "Schnüffler"; case "CREAKING" -> "Knarrender"; case "PARCHED" -> "Parched";
            case "CAMEL_HUSK" -> "Kamelhülle"; case "ZOMBIE_NAUTILUS" -> "Zombie-Nautilus"; case "NAUTILUS" -> "Nautilus";
            case "SULFUR_CUBE" -> "Sulfur Cube"; case "COPPER_GOLEM" -> "Kupfergolem"; case "HAPPY_GHAST" -> "Happy Ghast";
            case "GHASTLING" -> "Ghastling"; default -> title(type);
        };
    }

    private static String germanColor(String color) {
        return switch (color) {
            case "WHITE" -> "Weiße"; case "ORANGE" -> "Orange"; case "MAGENTA" -> "Magenta";
            case "LIGHT_BLUE" -> "Hellblaue"; case "YELLOW" -> "Gelbe"; case "LIME" -> "Hellgrüne";
            case "PINK" -> "Rosa"; case "GRAY" -> "Graue"; case "LIGHT_GRAY" -> "Hellgraue";
            case "CYAN" -> "Türkise"; case "PURPLE" -> "Violette"; case "BLUE" -> "Blaue";
            case "BROWN" -> "Braune"; case "GREEN" -> "Grüne"; case "RED" -> "Rote"; case "BLACK" -> "Schwarze";
            default -> title(color);
        };
    }

    private static String title(String value) {
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
