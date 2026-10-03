package de.walahi.novosmp.professions;

public final class ProfessionProgress {
    private final String professionId;
    private int prestige;
    private int level;
    private double xp;
    private int completedMilestone;
    private String activeToolSerial;
    private boolean dirty;

    public ProfessionProgress(String professionId, int prestige, int level, double xp,
                              int completedMilestone, String activeToolSerial) {
        this.professionId = professionId;
        this.prestige = Math.max(0, prestige);
        this.level = Math.max(1, level);
        this.xp = Math.max(0D, xp);
        this.completedMilestone = Math.max(0, completedMilestone);
        this.activeToolSerial = activeToolSerial;
    }

    public String professionId() { return professionId; }
    public int prestige() { return prestige; }
    public int level() { return level; }
    public double xp() { return xp; }
    public int completedMilestone() { return completedMilestone; }
    public String activeToolSerial() { return activeToolSerial; }
    public boolean dirty() { return dirty; }

    public void prestige(int prestige) { this.prestige = Math.max(0, prestige); dirty = true; }
    public void level(int level) { this.level = Math.max(1, level); dirty = true; }
    public void xp(double xp) { this.xp = Math.max(0D, xp); dirty = true; }
    public void completedMilestone(int milestone) { this.completedMilestone = Math.max(0, milestone); dirty = true; }
    public void activeToolSerial(String serial) { this.activeToolSerial = serial; dirty = true; }
    public void markDirty() { dirty = true; }
    public void markClean() { dirty = false; }

    public ProfessionProgress copy() {
        ProfessionProgress copy = new ProfessionProgress(professionId, prestige, level, xp,
                completedMilestone, activeToolSerial);
        copy.dirty = dirty;
        return copy;
    }
}
