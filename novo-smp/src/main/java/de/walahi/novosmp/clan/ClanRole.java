package de.walahi.novosmp.clan;

public enum ClanRole {
    LEADER, OFFICER, MEMBER;
    public boolean staff() { return this == LEADER || this == OFFICER; }
}
