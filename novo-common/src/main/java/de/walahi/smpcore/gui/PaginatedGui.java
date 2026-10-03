package de.walahi.smpcore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Reusable holder-based pagination. Existing range/navigation methods remain
 * compatible, while arbitrary content and navigation slots are supported.
 */
public final class PaginatedGui {
    private final int rows;
    private final Component title;
    private final List<GuiButton> entries = new ArrayList<>();
    private List<Integer> contentSlots;
    private GuiButton previousButton;
    private GuiButton nextButton;
    private int previousSlot;
    private int nextSlot;
    private ItemStack filler;
    private final Map<Integer, GuiButton> fixedButtons = new LinkedHashMap<>();

    public PaginatedGui(int rows, Component title) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("rows muss zwischen 1 und 6 liegen");
        }
        this.rows = rows;
        this.title = Objects.requireNonNull(title, "title");
        this.contentSlots = SlotLayout.range(9, rows * 9 - 10);
        this.previousSlot = rows * 9 - 9;
        this.nextSlot = rows * 9 - 1;
    }

    public PaginatedGui range(int firstSlot, int lastSlot) {
        validateSlot(firstSlot);
        validateSlot(lastSlot);
        if (lastSlot < firstSlot) {
            throw new IllegalArgumentException("Ungültiger Seitenbereich: " + firstSlot + "-" + lastSlot);
        }
        this.contentSlots = SlotLayout.range(firstSlot, lastSlot);
        return this;
    }

    public PaginatedGui slots(int... slots) {
        Objects.requireNonNull(slots, "slots");
        List<Integer> validated = Arrays.stream(slots)
                .peek(this::validateSlot)
                .boxed()
                .distinct()
                .toList();
        if (validated.isEmpty()) throw new IllegalArgumentException("Mindestens ein Inhaltsslot ist erforderlich");
        this.contentSlots = validated;
        return this;
    }

    public PaginatedGui add(GuiButton button) {
        entries.add(Objects.requireNonNull(button, "button"));
        return this;
    }

    public PaginatedGui navigation(GuiButton previous, GuiButton next) {
        return navigation(rows * 9 - 9, previous, rows * 9 - 1, next);
    }

    public PaginatedGui navigation(int previousSlot, GuiButton previous,
                                   int nextSlot, GuiButton next) {
        validateSlot(previousSlot);
        validateSlot(nextSlot);
        this.previousSlot = previousSlot;
        this.previousButton = previous;
        this.nextSlot = nextSlot;
        this.nextButton = next;
        return this;
    }

    public PaginatedGui filler(ItemStack item) {
        this.filler = Objects.requireNonNull(item, "item").clone();
        return this;
    }

    public PaginatedGui fixedButton(int slot, GuiButton button) {
        validateSlot(slot);
        fixedButtons.put(slot, Objects.requireNonNull(button, "button"));
        return this;
    }

    public int pageCount() {
        return Math.max(1, (entries.size() + contentSlots.size() - 1) / contentSlots.size());
    }

    public void open(Player player, int requestedPage) {
        PageSlice<GuiButton> page = PageSlice.of(entries, requestedPage, contentSlots.size());
        Gui gui = new Gui(rows, title);
        if (filler != null) gui.filler(filler);

        for (int index = 0; index < page.entries().size(); index++) {
            gui.button(contentSlots.get(index), page.entries().get(index));
        }
        fixedButtons.forEach(gui::button);
        if (previousButton != null && page.hasPrevious()) {
            gui.button(previousSlot, new GuiButton(previousButton.item(),
                    event -> open(player, page.page() - 1)));
        }
        if (nextButton != null && page.hasNext()) {
            gui.button(nextSlot, new GuiButton(nextButton.item(),
                    event -> open(player, page.page() + 1)));
        }
        gui.open(player);
    }

    private void validateSlot(int slot) {
        if (slot < 0 || slot >= rows * 9) {
            throw new IllegalArgumentException("Ungültiger Slot: " + slot);
        }
    }
}
