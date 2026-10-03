package de.walahi.novosmp.friends;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import de.walahi.novosmp.NovoSMPPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/** Typed access to the independent friends.yml. */
public final class FriendConfiguration {
    private final NovoSMPPlugin plugin;
    private final YamlConfiguration config;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public FriendConfiguration(NovoSMPPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.configs().friendsFile().yaml();
    }

    public int integer(String path, int fallback) {
        return config.getInt(path, fallback);
    }

    public long longValue(String path, long fallback) {
        return config.getLong(path, fallback);
    }

    public double decimal(String path, double fallback) {
        return config.getDouble(path, fallback);
    }

    public String string(String path, String fallback) {
        return config.getString(path, fallback);
    }

    public List<Integer> integerList(String path, List<Integer> fallback) {
        List<Integer> configured = config.getIntegerList(path);
        return configured.isEmpty() ? List.copyOf(fallback) : List.copyOf(configured);
    }

    public Material material(String path, Material fallback) {
        String configured = config.getString(path);
        if (configured == null || configured.isBlank()) return fallback;
        Material material = Material.matchMaterial(configured.trim());
        if (material != null) return material;
        plugin.getLogger().warning("Ungültiges Material in friends.yml bei '" + path + "': " + configured);
        return fallback;
    }

    public Component component(String path, String fallback) {
        return component(path, fallback, Map.of());
    }

    public Component component(String path, String fallback, Map<String, String> placeholders) {
        return miniMessage.deserialize(string(path, fallback), resolver(placeholders));
    }

    public List<Component> components(String path, List<String> fallback, Map<String, String> placeholders) {
        List<String> values = config.getStringList(path);
        if (values.isEmpty()) values = fallback;
        List<Component> result = new ArrayList<>(values.size());
        TagResolver resolver = resolver(placeholders);
        for (String value : values) result.add(miniMessage.deserialize(value, resolver));
        return result;
    }

    public List<String> strings(String path, List<String> fallback) {
        List<String> values = config.getStringList(path);
        return values.isEmpty() ? List.copyOf(fallback) : List.copyOf(values);
    }

    private TagResolver resolver(Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return TagResolver.empty();
        TagResolver.Builder builder = TagResolver.builder();
        placeholders.forEach((key, value) -> builder.resolver(Placeholder.unparsed(key, value == null ? "" : value)));
        return builder.build();
    }
}
