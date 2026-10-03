package de.walahi.novosmp.professions;

public enum BoosterCategory {
    PROFESSION("Berufe"),
    LUMI("Lumi");

    private final String display;

    BoosterCategory(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
