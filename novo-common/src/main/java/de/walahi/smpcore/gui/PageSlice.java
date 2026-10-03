package de.walahi.smpcore.gui;

import java.util.List;
import java.util.Objects;

/** Immutable, clamped page view over an existing list. */
public record PageSlice<T>(List<T> entries, int page, int pageCount, int totalEntries) {
    public PageSlice {
        entries = List.copyOf(entries);
        if (page < 0) throw new IllegalArgumentException("page darf nicht negativ sein");
        if (pageCount < 1) throw new IllegalArgumentException("pageCount muss mindestens 1 sein");
        if (totalEntries < 0) throw new IllegalArgumentException("totalEntries darf nicht negativ sein");
    }

    public static <T> PageSlice<T> of(List<T> source, int requestedPage, int pageSize) {
        Objects.requireNonNull(source, "source");
        if (pageSize < 1) throw new IllegalArgumentException("pageSize muss mindestens 1 sein");
        int pageCount = Math.max(1, (source.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        int from = page * pageSize;
        int to = Math.min(source.size(), from + pageSize);
        return new PageSlice<>(source.subList(from, to), page, pageCount, source.size());
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < pageCount;
    }
}
