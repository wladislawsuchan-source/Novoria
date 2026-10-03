package de.walahi.novosmp.orders;

import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** All transient player-specific menu state in one place. */
final class OrderMenuState {
    private final Map<UUID, OrderDraft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, OrderSelectionCatalog.Filter> filters = new ConcurrentHashMap<>();
    private final Map<UUID, OrderSelectionCatalog.Sort> sorts = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pages = new ConcurrentHashMap<>();
    private final Map<UUID, String> searches = new ConcurrentHashMap<>();

    OrderDraft draft(UUID playerId) {
        return drafts.get(playerId);
    }

    OrderDraft createDraft(UUID playerId, ItemStack item) {
        OrderDraft draft = new OrderDraft(item);
        drafts.put(playerId, draft);
        return draft;
    }

    void removeDraft(UUID playerId) {
        drafts.remove(playerId);
    }

    OrderSelectionCatalog.Filter filter(UUID playerId) {
        return filters.getOrDefault(playerId, OrderSelectionCatalog.Filter.ALL);
    }

    void filter(UUID playerId, OrderSelectionCatalog.Filter filter) {
        filters.put(playerId, filter);
    }

    OrderSelectionCatalog.Sort sort(UUID playerId) {
        return sorts.getOrDefault(playerId, OrderSelectionCatalog.Sort.A_TO_Z);
    }

    void sort(UUID playerId, OrderSelectionCatalog.Sort sort) {
        sorts.put(playerId, sort);
    }

    int page(UUID playerId) {
        return pages.getOrDefault(playerId, 0);
    }

    void page(UUID playerId, int page) {
        pages.put(playerId, Math.max(0, page));
    }

    String search(UUID playerId) {
        return searches.getOrDefault(playerId, "");
    }

    void search(UUID playerId, String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) searches.remove(playerId);
        else searches.put(playerId, normalized);
    }

    void clear(UUID playerId) {
        drafts.remove(playerId);
        filters.remove(playerId);
        sorts.remove(playerId);
        pages.remove(playerId);
        searches.remove(playerId);
    }
}
