package de.walahi.smpcore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Zentrale, holder-basierte GUI. Klicks werden nicht anhand des Titels erkannt,
 * wodurch gleichnamige Vanilla-Inventare oder andere Plugins nicht kollidieren.
 */
public class Gui {
    protected final Component title;
    protected final int rows;
    protected final Map<Integer, GuiButton> buttons = new HashMap<>();
    protected ItemStack filler;
    protected String openSound;
    protected String clickSound;
    protected float soundVolume = 0.7F;
    protected float openSoundPitch = 1.0F;
    protected float clickSoundPitch = 1.2F;

    public Gui(int rows, Component title) {
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("rows muss zwischen 1 und 6 liegen");
        this.rows = rows;
        this.title = Objects.requireNonNull(title, "title");
    }

    public Gui button(int slot, GuiButton button) {
        validateSlot(slot);
        buttons.put(slot, Objects.requireNonNull(button, "button"));
        return this;
    }

    public Gui item(int slot, ItemStack item) {
        return button(slot, new GuiButton(item, null));
    }

    public Gui remove(int slot) {
        validateSlot(slot);
        buttons.remove(slot);
        return this;
    }

    public Gui filler(Material material) {
        Objects.requireNonNull(material, "material");
        filler = ItemBuilder.of(material).name(Component.empty()).build();
        return this;
    }

    public Gui filler(ItemStack item) {
        filler = Objects.requireNonNull(item, "item").clone();
        return this;
    }

    /** Optional per-GUI sounds. Other menus remain silent unless they opt in. */
    public Gui sounds(String openSound, String clickSound, float volume, float openPitch, float clickPitch) {
        this.openSound = normalizeSound(openSound);
        this.clickSound = normalizeSound(clickSound);
        this.soundVolume = Math.max(0F, volume);
        this.openSoundPitch = Math.max(0.01F, openPitch);
        this.clickSoundPitch = Math.max(0.01F, clickPitch);
        return this;
    }

    private String normalizeSound(String sound) {
        if (sound == null || sound.isBlank()) return null;
        String value = sound.trim().toLowerCase(java.util.Locale.ROOT);
        return value.indexOf(':') >= 0 ? value : "minecraft:" + value.replace('.', '_');
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * 9;
    }

    protected void validateSlot(int slot) {
        if (slot < 0 || slot >= rows * 9) throw new IllegalArgumentException("Ungültiger Slot: " + slot);
    }

    public Inventory createInventory() {
        GuiHolder holder = new GuiHolder(this);
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, title);
        holder.setInventory(inventory);

        if (filler != null) {
            for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler.clone());
        }
        buttons.forEach((slot, button) -> inventory.setItem(slot, button.item().clone()));
        return inventory;
    }

    public void open(Player player) {
        Objects.requireNonNull(player, "player").openInventory(createInventory());
        if (openSound != null) player.playSound(player.getLocation(), openSound, soundVolume, openSoundPitch);
    }

    void click(int slot, org.bukkit.event.inventory.InventoryClickEvent event) {
        GuiButton button = buttons.get(slot);
        if (button != null && button.action() != null) {
            if (clickSound != null && event.getWhoClicked() instanceof Player player) {
                player.playSound(player.getLocation(), clickSound, soundVolume, clickSoundPitch);
            }
            button.action().accept(event);
        }
    }
}
