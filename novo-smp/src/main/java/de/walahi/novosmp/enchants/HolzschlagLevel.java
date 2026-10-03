package de.walahi.novosmp.enchants;

/**
 * Alle Werte einer einzelnen Holzschlag-Stufe. Vollständig aus der
 * {@code holzschlag.yml} gelesen, damit Balancing ohne Neubau möglich ist.
 *
 * @param maxBlocks          höchste Anzahl Holzblöcke inklusive des selbst abgebauten
 * @param cooldownSeconds    Wartezeit bis zur nächsten Auslösung
 * @param breakLeaves        entfernt zusätzlich die eindeutig zum gefällten Baum gehörenden Blätter
 * @param maxLeaves          Obergrenze der entfernten Blätter, schützt vor Lastspitzen
 * @param durabilityPerBlock Haltbarkeitsverbrauch je zusätzlich abgebautem Holzblock
 * @param sameMaterialOnly   nur Holz derselben Sorte wie der zuerst abgebaute Block
 * @param includeDiagonals   auch diagonal anliegende Holzblöcke gelten als verbunden
 */
public record HolzschlagLevel(
        int maxBlocks,
        int cooldownSeconds,
        boolean breakLeaves,
        int maxLeaves,
        int durabilityPerBlock,
        boolean sameMaterialOnly,
        boolean includeDiagonals
) {
}
