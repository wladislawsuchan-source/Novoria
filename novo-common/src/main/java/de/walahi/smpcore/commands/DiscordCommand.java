package de.walahi.smpcore.commands;

import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

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
        if (args.length > 0 && args[0].equalsIgnoreCase("set")) {
            return setLink(sender, args);
        }

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

    private boolean setLink(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcore.discord.admin")) {
            return messageOrDefault(sender, noPermissionMessagePath(),
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Dafür hast du keine Berechtigung.</red>");
        }
        if (args.length == 1) {
            return plugin.messages().sendConfiguredAuto(sender, plugin.configs().server(),
                    "discord.messages.set-usage", "<gray>Benutzung: <yellow>/discord set <Link></yellow></gray>");
        }

        String link = args.length == 2 ? DiscordInviteLink.normalize(args[1]) : null;
        if (link == null) {
            return plugin.messages().sendConfiguredAuto(sender, plugin.configs().server(),
                    "discord.messages.invalid-link",
                    "<dark_gray>[<red>Team</red>]</dark_gray> <red>Bitte gib einen gültigen Discord-Einladungslink an.</red>");
        }

        plugin.configs().server().set("discord.invite-link", link);
        plugin.configs().serverFile().save();
        return plugin.messages().sendConfiguredAuto(sender, plugin.configs().server(),
                "discord.messages.link-updated",
                "<dark_gray>[<red>Team</red>]</dark_gray> <green>Discord-Link aktualisiert: <light_purple>%link%</light_purple></green>",
                "%link%", link);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("smpcore.discord.admin")
                && "set".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return List.of("set");
        }
        return List.of();
    }
}
