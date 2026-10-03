# Angler-Loot-Grundlagen – Verifikation

Aktualisierte lokale Prüfung mit Paper **26.2-71** und Java 25 am 01.10.2026;
die vorige Bundle-Grundlagenprüfung lief auf Paper **26.2-124** am 30.09.2026.
Der aktualisierte Probe-Log liegt unter `build/angler-paper-26.2-loot-probe.log`.
Der Test-Probe-Code liegt unter
`novo-smp/src/test/java/de/walahi/novosmp/angler/AnglerBundlePaperProbe.java`.

## Loot-Ausgabegrundlage

`AnglerLootFoundation.LootReward` unterscheidet NORMAL_ITEM, CUSTOM_ITEM,
OVERLEVEL_BOOK, BUNDLE und LUMI. `createPhysicalReward` erzeugt nur physische
Items; ein LUMI in Bundle/Fanglager wird abgewiesen. `deliver` führt spätere
physische Pool-Gewinne über `AnglerFeature.storeCatch` und dessen bestehende
Überlaufbehandlung, während LUMI direkt über `LumiRepository.add(UUID,long)`
gutgeschrieben wird. Es sind noch keine Pool-Inhalte an diesen Einstiegspunkt
verdrahtet. Ungültige IDs/Enchants sowie Ausgabefehler werden protokolliert;
es gibt keine Ersatzitems. Der Reward-Typ-/Defensivkopie-Probe war **PASS**.

## Vanilla-Bundles

Die produktive Methode `AnglerLootFoundation.createBundle` wurde auf dem laufenden
Paper-Server aufgerufen. Danach wurden die Bundle-Inhalte direkt aus der
`BUNDLE_CONTENTS`-Data-Component und nach `ItemStack`-Serialisierung erneut gelesen.

| Fall | Inhalt | Ergebnis |
| --- | --- | --- |
| normal | 16 Stein + 8 Erde | PASS, 2/2 Stacks, 24/24 Items |
| over64 | 64 Stein + 64 Erde | PASS, 2/2 Stacks, 128/128 Items |
| tools | Diamantspitzhacke, -axt, -schaufel | PASS, 3/3 Stacks, 3/3 Items |
| armor | vollständiges Diamantrüstungsset | PASS, 4/4 Stacks, 4/4 Items |
| armor_set | vier verzauberte Netheritrüstungsteile + verzauberte Spitzhacke und Schwert | PASS, 6/6 Stacks, 6/6 Items |

Damit ist die serverseitige Erzeugung und Speicherung trotz normaler
Bundle-Kapazität für diese Fälle belegt. Für alle Fälle wurden außerdem
Material, Anzahl und Item-Metadaten nach `ItemStack`-Serialisierung verglichen.
**Nicht verifiziert** sind Darstellung,
Netzwerkübertragung und Entnahme im Minecraft-Client; dafür sind die fünf
`/customitem bundletest <Spieler> <Fall>`-Tests auf dem Testserver vorgesehen.

## Overlevel-Bücher

Auf derselben Paper-Instanz wurden alle neun konfigurierten Vanilla-
Enchanted-Books mit der gewünschten Stored-Enchantment-Stufe erzeugt:
Lure IV/V, Luck of the Sea IV/V, Unbreaking IV, Fortune IV/V und Efficiency VI/VII.
Alle neun Prüfungen: **PASS**.

Der Reparaturkern-Backendbaustein erzeugt eine reparierte Kopie, bei der nur
die normale Haltbarkeit auf 100 % gesetzt wird; die ursprüngliche Spitzhacke
und ein beispielhafter Custom-Charges-PDC bleiben unverändert. Die konkrete
Benutzung/Verbrauchsaktion ist bewusst noch nicht festgelegt.

`mvn clean verify` sowie der vorhandene eigenständige Angler-/SQLite-Check: **PASS**.
