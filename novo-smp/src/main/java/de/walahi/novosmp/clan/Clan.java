package de.walahi.novosmp.clan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Clan {
    private final UUID id;
    private String name, tag, tagColor;
    private int level;
    private long bank, renamedAt, taggedAt;
    private UUID leader;
    private boolean friendlyFire, bonusHome;
    private final Map<UUID, Member> members = new LinkedHashMap<>();

    Clan(UUID id, String name, String tag, String tagColor, int level, long bank, UUID leader,
         boolean friendlyFire, boolean bonusHome, long renamedAt, long taggedAt) {
        this.id=id; this.name=name; this.tag=tag; this.tagColor=tagColor; this.level=level; this.bank=bank;
        this.leader=leader; this.friendlyFire=friendlyFire; this.bonusHome=bonusHome;
        this.renamedAt=renamedAt; this.taggedAt=taggedAt;
    }
    public UUID id(){return id;} public String name(){return name;} public String tag(){return tag;}
    public String tagColor(){return tagColor;} public int level(){return level;} public long bank(){return bank;}
    public UUID leader(){return leader;} public boolean friendlyFire(){return friendlyFire;}
    public boolean bonusHome(){return bonusHome;} public long renamedAt(){return renamedAt;} public long taggedAt(){return taggedAt;}
    public Map<UUID,Member> members(){return members;}
    void name(String v){name=v;} void tag(String v){tag=v;} void tagColor(String v){tagColor=v;}
    void level(int v){level=v;} void bank(long v){bank=v;} void leader(UUID v){leader=v;}
    void friendlyFire(boolean v){friendlyFire=v;} void bonusHome(boolean v){bonusHome=v;}
    void renamedAt(long v){renamedAt=v;} void taggedAt(long v){taggedAt=v;}
    public record Member(UUID id,String name,ClanRole role,long joinedAt) { }
}
