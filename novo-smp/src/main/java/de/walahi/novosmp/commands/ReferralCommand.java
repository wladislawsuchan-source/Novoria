package de.walahi.novosmp.commands;

import de.walahi.novosmp.referral.ReferralManager;
import de.walahi.novosmp.referral.ReferralMenu;
import de.walahi.smpcore.SMPCorePlugin;
import de.walahi.smpcore.commands.framework.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ReferralCommand extends BaseCommand {
    private final ReferralManager manager;
    private final ReferralMenu menu;

    public ReferralCommand(SMPCorePlugin plugin, ReferralManager manager, ReferralMenu menu) {
        super(plugin);
        this.manager = manager;
        this.menu = menu;
    }

    @Override protected String permission() { return "smpcore.ref.use"; }

    @Override
    protected boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return messageOrDefault(sender, "referral.messages.player-only", "<red>Nur Spieler können /ref verwenden.</red>");
        }
        if (args.length == 0) {
            menu.open(player);
            return true;
        }
        if (args.length != 1) {
            player.sendRichMessage("<red>Verwendung: /ref [Code]</red>");
            return true;
        }
        ReferralManager.RedeemOutcome outcome = manager.redeem(player, args[0]);
        switch (outcome) {
            case PENDING -> player.sendRichMessage(manager.config("referral.messages.redeem-pending",
                    "<green>Referral-Code gespeichert.</green> <gray>Die Empfehlung zählt, sobald du <white>%blocks% Blöcke</white>, <white>%mobs% Mobs</white> und <white>%advancements% Advancements</white> erreicht hast.</gray>")
                    .replace("%blocks%", Integer.toString(manager.requiredBlocks()))
                    .replace("%mobs%", Integer.toString(manager.requiredMobKills()))
                    .replace("%advancements%", Integer.toString(manager.requiredAdvancements())));
            case VERIFIED -> { /* tryVerify() hat Belohnung und Erfolgsmeldung bereits ausgeliefert. */ }
            case INVALID_CODE -> player.sendRichMessage(manager.config("referral.messages.invalid-code",
                    "<red>Dieser Referral-Code existiert nicht.</red>"));
            case OWN_CODE -> player.sendRichMessage(manager.config("referral.messages.own-code",
                    "<red>Du kannst deinen eigenen Referral-Code nicht verwenden.</red>"));
            case ALREADY_REDEEMED -> player.sendRichMessage(manager.config("referral.messages.already-redeemed",
                    "<red>Du hast bereits einen Referral-Code verwendet.</red>"));
            case TOO_OLD -> {
                long max = manager.maxRedeemPlaytimeSeconds();
                player.sendRichMessage(manager.config("referral.messages.too-old",
                                "<red>Referral-Codes können nur in deiner ersten Spielstunde verwendet werden.</red>")
                        .replace("%hours%", Long.toString(Math.max(1L, max / 3600L))));
            }
            case DISABLED -> player.sendRichMessage("<red>Das Refer-a-Friend-System ist deaktiviert.</red>");
            case STORAGE_ERROR -> player.sendRichMessage("<red>Der Referral-Code konnte nicht gespeichert werden.</red>");
        }
        return true;
    }
}
