package de.walahi.smpcore.gui;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared validation for configurable inventory slots. */
public final class SlotLayout {
    private SlotLayout() {
    }

    public static int valid(int configured, int inventorySize, int fallback) {
        if (configured >= 0 && configured < inventorySize) return configured;
        if (fallback >= 0 && fallback < inventorySize) return fallback;
        throw new IllegalArgumentException("Kein gültiger Fallback-Slot für Inventargröße " + inventorySize);
    }

    public static List<Integer> range(int first, int lastInclusive) {
        if (first < 0 || lastInclusive < first) throw new IllegalArgumentException("Ungültiger Slotbereich");
        List<Integer> slots = new ArrayList<>(lastInclusive - first + 1);
        for (int slot = first; slot <= lastInclusive; slot++) slots.add(slot);
        return List.copyOf(slots);
    }

    public static List<Integer> configured(ConfigurationSection section, String path,
                                           int inventorySize, List<Integer> fallback) {
        List<Integer> configured = section == null ? List.of() : section.getIntegerList(path);
        List<Integer> source = configured.isEmpty() ? fallback : configured;
        List<Integer> valid = source.stream()
                .filter(slot -> slot != null && slot >= 0 && slot < inventorySize)
                .distinct()
                .toList();
        if (!valid.isEmpty()) return valid;
        return fallback.stream()
                .filter(slot -> slot != null && slot >= 0 && slot < inventorySize)
                .distinct()
                .toList();
    }

    public static List<Integer> of(int... slots) {
        return Arrays.stream(slots).boxed().toList();
    }
}
