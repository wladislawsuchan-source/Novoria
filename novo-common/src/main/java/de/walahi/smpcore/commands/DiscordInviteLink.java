package de.walahi.smpcore.commands;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class DiscordInviteLink {
    private DiscordInviteLink() {
    }

    static String normalize(String input) {
        if (input == null || input.isBlank()) return null;
        String link = input.trim();
        if (link.regionMatches(true, 0, "discord.gg/", 0, "discord.gg/".length())) {
            link = "https://" + link;
        }

        try {
            URI uri = new URI(link);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            String host = uri.getHost();
            if (host == null) return null;
            String path = uri.getRawPath();
            if (path == null) return null;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            String code;
            if (lowerHost.equals("discord.gg")) {
                code = path.startsWith("/") ? path.substring(1) : "";
            } else if (lowerHost.equals("discord.com") && path.startsWith("/invite/")) {
                code = path.substring("/invite/".length());
            } else {
                return null;
            }
            return code.matches("[A-Za-z0-9_-]+") ? link : null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }
}
