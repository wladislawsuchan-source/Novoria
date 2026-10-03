package de.walahi.smpcore.friends;
public record FriendSettings(boolean glow, boolean chatMark, boolean joinLeave, boolean friendlyFire) {
    public static FriendSettings defaults() { return new FriendSettings(true, true, true, false); }
}
