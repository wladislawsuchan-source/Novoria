package de.walahi.smpcore.messages;

/** Visual message groups used across NovoCore. */
public enum MessageChannel {
    SMP("message-api.prefixes.smp", "<dark_gray>[<green>SMP</green>]</dark_gray> "),
    TEAM("message-api.prefixes.team", "<dark_gray>[<red>Team</red>]</dark_gray> "),
    SPAWN("message-api.prefixes.spawn", "<dark_gray>[<gold>Spawn</gold>]</dark_gray> "),
    AUCTION_HOUSE("message-api.prefixes.auction-house", "<dark_gray>[<gold>AH</gold>]</dark_gray> "),
    ORDER("message-api.prefixes.order", "<dark_gray>[<aqua>Order</aqua>]</dark_gray> "),
    SHOP("message-api.prefixes.shop", "<dark_gray>[<gold>Shop</gold>]</dark_gray> "),
    DUEL("message-api.prefixes.duel", "<dark_gray>[<light_purple>Duel</light_purple>]</dark_gray> "),
    FRIENDS("message-api.prefixes.friends", "<dark_gray>[<light_purple>Freunde</light_purple>]</dark_gray> "),
    CRATE("message-api.prefixes.crate", "<dark_gray>[<gold>Kiste</gold>]</dark_gray> "),
    ENDER_CHEST("message-api.prefixes.ender-chest", "<dark_gray>[<light_purple>EC</light_purple>]</dark_gray> "),
    NOVORIA("message-api.prefixes.novoria", "<dark_gray>[<light_purple>Novoria</light_purple>]</dark_gray> "),
    NONE("", "");

    private final String configPath;
    private final String fallbackPrefix;

    MessageChannel(String configPath, String fallbackPrefix) {
        this.configPath = configPath;
        this.fallbackPrefix = fallbackPrefix;
    }

    public String configPath() { return configPath; }
    public String fallbackPrefix() { return fallbackPrefix; }
}
