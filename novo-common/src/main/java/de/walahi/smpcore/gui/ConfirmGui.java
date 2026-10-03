package de.walahi.smpcore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public final class ConfirmGui {
    private ConfirmGui() {
    }

    public static void open(Player player, Component title, ItemStack confirm, ItemStack cancel,
                            Runnable onConfirm, Runnable onCancel) {
        open(player, title, confirm, cancel, null, onConfirm, onCancel);
    }

    public static void open(Player player, Component title, ItemStack confirm, ItemStack cancel,
                            ItemStack summary, Runnable onConfirm, Runnable onCancel) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(onConfirm, "onConfirm");
        Gui gui = new Gui(3, title)
                .button(11, GuiButton.of(cancel, event -> {
                    player.closeInventory();
                    if (onCancel != null) onCancel.run();
                }))
                .button(15, GuiButton.of(confirm, event -> {
                    player.closeInventory();
                    onConfirm.run();
                }));
        if (summary != null) gui.item(13, summary);
        gui.open(player);
    }
}
