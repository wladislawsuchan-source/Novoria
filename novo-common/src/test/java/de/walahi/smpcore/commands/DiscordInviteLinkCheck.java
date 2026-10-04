package de.walahi.smpcore.commands;

public final class DiscordInviteLinkCheck {
    public static void main(String[] args) {
        expect("https://discord.gg/test", "https://discord.gg/test");
        expect("https://discord.com/invite/test", "https://discord.com/invite/test");
        expect("discord.gg/test", "https://discord.gg/test");
        expect("", null);
        expect("google.de", null);
        expect("javascript:alert(1)", null);
        expect("http://discord.gg/test", null);
        expect("https://discord.gg.evil.test/test", null);
        expect("https://discord.com/other/test", null);
        expect("https://discord.gg/", null);
        expect("https://discord.gg/test/extra", null);
        expect("https://discord.gg/test?next=evil", null);
        expect("https://discord.gg@evil.test/test", null);
    }

    private static void expect(String input, String expected) {
        String actual = DiscordInviteLink.normalize(input);
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Unexpected result for " + input + ": " + actual);
        }
    }
}
