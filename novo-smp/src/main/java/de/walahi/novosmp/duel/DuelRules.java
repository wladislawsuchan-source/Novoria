package de.walahi.novosmp.duel;

public record DuelRules(boolean crystalsAllowed, boolean crystalDamage, boolean tntAllowed, boolean tntDamage) {
    public DuelRules toggleCrystals() { return new DuelRules(!crystalsAllowed, crystalDamage, tntAllowed, tntDamage); }
    public DuelRules toggleCrystalDamage() { return new DuelRules(crystalsAllowed, !crystalDamage, tntAllowed, tntDamage); }
    public DuelRules toggleTnt() { return new DuelRules(crystalsAllowed, crystalDamage, !tntAllowed, tntDamage); }
    public DuelRules toggleTntDamage() { return new DuelRules(crystalsAllowed, crystalDamage, tntAllowed, !tntDamage); }
}
