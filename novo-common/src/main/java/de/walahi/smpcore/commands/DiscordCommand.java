package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

public final class DiscordCommand extends BaseCommand {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public DiscordCommand(SMPCorePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String permission() {
        return "smpcore.discord";
    }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        String link = plugin.configs().server().getString("discord.invite-link", "").trim();
        if (link.isEmpty()) {
            return messageOrDefault(sender, "discord.messages.not-configured",
                    "<dark_gray>[<light_purple>Novoria</light_purple>]</dark_gray> <red>Der Discord-Link ist noch nicht eingerichtet.</red>");
        }

        String text = plugin.configs().server().getString("discord.messages.invite",
                "<dark_gray>[<light_purple>Novoria</light_purple>]</dark_gray> <gray>Discord: <light_purple><u>%link%</u></light_purple></gray>")
                .replace("%link%", link);
        Component component = miniMessage.deserialize(text)
                .clickEvent(ClickEvent.openUrl(link))
                .hoverEvent(HoverEvent.showText(miniMessage.deserialize("<gray>Klicken, um dem Discord beizutreten.</gray>")));
        sender.sendMessage(component);
        return true;
    }
}
