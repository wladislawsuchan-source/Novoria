package de.walahi.smpcore.gui;

import java.util.Map;

/** Clamped metadata for repository-backed pagination without loading every row. */
public record PageWindow(int page, int pageCount, int totalEntries, int pageSize, int offset) {
    public PageWindow {
        if (page < 0) throw new IllegalArgumentException("page darf nicht negativ sein");
        if (pageCount < 1) throw new IllegalArgumentException("pageCount muss mindestens 1 sein");
        if (totalEntries < 0) throw new IllegalArgumentException("totalEntries darf nicht negativ sein");
        if (pageSize < 1) throw new IllegalArgumentException("pageSize muss mindestens 1 sein");
        if (offset < 0) throw new IllegalArgumentException("offset darf nicht negativ sein");
    }

    public static PageWindow of(int requestedPage, int totalEntries, int pageSize) {
        int safeTotal = Math.max(0, totalEntries);
        int safeSize = Math.max(1, pageSize);
        int pages = Math.max(1, (safeTotal + safeSize - 1) / safeSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        return new PageWindow(page, pages, safeTotal, safeSize, page * safeSize);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < pageCount;
    }

    public Map<String, String> placeholders() {
        return Map.of(
                "%page%", Integer.toString(page + 1),
                "%pages%", Integer.toString(pageCount)
        );
    }
}
