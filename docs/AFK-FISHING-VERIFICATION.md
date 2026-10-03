# AFK-Angeln: Hook-Lifecycle und Abnahme

## Implementierung

AFK-Fänge entstehen ausschließlich durch `PlayerFishEvent.State.BITE`. Der Listener cancelt
dieses Event, bevor Paper das Vanilla-Fangfenster öffnet, verarbeitet genau einen Fang und
initialisiert anschließend denselben Hook erneut. `CAUGHT_FISH` ist im AFK-Modus gesperrt.
Der laufende Hook-Timer und eine Reentranzsperre verhindern eine zweite Verarbeitung desselben Bisses.

Entfernt wurden `afkCatchTask`, `afkGeneration`, `nextAfkCatchTick`, `scheduleAfkCatch`,
`tickAfk`, `suppressVanillaBite`, `holdVanillaHook` und die normale Warte-auf-AFK-Anzeige.
Der bestehende Minigame-Task bleibt ausschließlich für aktives Angeln erhalten.
Es gibt keinen AFK-Fang-Scheduler und keinen zusätzlichen Actionbar-Scheduler.

Nach jedem `resetFishingState()` wird der zufällige Vanilla-Wartewert überschrieben:
`setWaitTime(0)` und `setTimeUntilBite(intervalSeconds * 20)` starten direkt die echte
Anschwimmphase. Diese dauert 600 / 540 / 480 / 420 / 360 / 300 Serverticks, entsprechend
30 / 27 / 24 / 21 / 18 / 15 Sekunden bei 20 TPS. Unter Serverlag dauern Serverticks länger;
es gibt keine Echtzeituhr und keine nachholenden Fang-Bursts.
Warte-/Lure-Grenzen werden ebenfalls gesetzt. Lure-, Regen- und Himmelseinfluss werden
bei jedem Zyklus am Hook deaktiviert. Die Rod-Verzauberungen selbst bleiben erhalten.

Das Lumi-HUD liest `getTimeUntilBite()` beziehungsweise `getWaitTime()` und rundet
die verbleibenden Serverticks auf Sekunden auf. Es setzt den Timer niemals neu.
Der bestehende Lumi-Task erneuert die Anzeige jede Sekunde; Start, Fang-Reset und Ende
fordern unmittelbar denselben HUD-Renderer an. Im AFK-HUD bleibt der Lumi-Kontostand sichtbar.
Normales Angeln liefert kein AFK-Segment; das aktive Minigame behält seine Actionbar-Priorität.
Die alte `afk.waiting-actionbar`-Einstellung wird auch in vorhandenen Configs nicht mehr gelesen.

Start erfordert globales AFK, aktiven Angler, Survival, gültige Mainhand-Rod und den
eigenen echten Hook im Zustand BOBBING im Wasser. Die Rod-Metadaten müssen weiterhin
zum Cast passen; normale Haltbarkeits- und Mending-Änderungen sind erlaubt.
Eine Mainhand-/Handwechsel- oder Rod-Drop-Aktion beendet AFK-Angeln sofort.
Andere Ungültigkeiten werden vor jedem Fang und im bestehenden HUD geprüft.

Start und Ende senden jeweils eine konfigurierbare Nachricht genau einmal.
Beenden entfernt zuerst die Session und anschließend den Hook, sodass reentrante
Cleanup-Events weder doppelte Meldungen noch weitere Fänge erzeugen.
Bewegung und `/afk` aus verwenden die vorhandene globale AFK-Schnittstelle.
Berufswechsel, Weltwechsel, Tod, Quit, Kick und Disable räumen ebenfalls auf.
Quit, Kick und Disable verzichten auf End-Chat und HUD-Ausgabe.
Der Hook wird direkt entfernt statt `retrieve()` aufzurufen: Retrieve würde erneut
Fishing-Events auslösen und könnte ein Vanilla-Fangfenster auswerten.
Nach AFK-Ende ist ein bewusstes erneutes Auswerfen nötig.

AFK verwendet unverändert die vorhandenen Pool-Rolls mit RED-Quality, Prestige,
Luck-Extra-Roll und Fanglager-/Overflow-Delivery. Die bestehende Preview-only-Regel
für einen FISH-Extra-Roll bleibt erhalten, ohne deren Chat-Preview zu senden.
Nicht-Fisch-Rewards verwenden dieselbe Loot-Foundation ohne neue Fang-Chatmeldungen.
Combo, Meistergriff, Nachfassen, Ruhige Hand und Konzentration werden nicht angewendet.

Berufsfortschritt läuft unverändert über `recordAfkAnglerCatch(player, 25L)` und dessen
Level-/Abgabesperren. Minecraft-XP ist davon unabhängig: pro Fang vanillaübliche 1–6 XP
über `player.giveExp(amount, true)`, wodurch Paper selbst Mending verarbeitet.
`rod.damage(1, player)` verwendet Paper-Damage-Semantik mit Unbreaking und Damage-/Break-Events,
ohne Enchants, Custom-Meta oder PDC zu ersetzen. Bricht die Rod, wird die Session beendet.
Loot-Pools, aktive Angler-Mechaniken, TAB-/Rank-Logik, globale AFK-Regeln und Lumi-Währung
wurden nicht verändert.

API-Referenz: [Paper 26.2 FishHook](https://jd.papermc.io/paper/26.2/org/bukkit/entity/FishHook.html).

## Automatisierte Prüfungen

Ergebnis vom 03.10.2026 auf Paper `26.2-129` / Java 25:
Build und Packaging-Prüfung bestanden. Alle sechs echten Hook-Intervalle über jeweils
drei native Zyklen bestanden; gleiche UUID und tickgenaue HUD-/Hook-Werte.
Unbreaking III: 243 von 1000 Schadensversuchen verursachten einen Haltbarkeitspunkt;
Enchant-/PDC-Erhalt, Minecraft-XP/Mending, Rod-Break, Hook-Entfernung und
Fishing-Activity-Klassifizierung bestanden. Vollständige Ingame-Abnahme noch offen.

`mvn clean verify` baut sämtliche Module und prüft die Vollständigkeit der schattierten JAR.
Die vorhandenen Standalone-/Paper-Probes sind keine automatisch von Surefire ausgeführten JUnit-Tests.

`AfkHookPaperProbe` ist ein Test-Plugin für eine **isolierte** Paper-26.2-Instanz.
Es darf nicht auf dem Spielserver installiert werden: es verwendet einen künstlichen Testspieler,
verändert seine Testwelt und beendet seine eigene Serverinstanz nach den Prüfungen.
Die Test-JAR besteht aus der gebauten NovoSMP-JAR plus der Probe-Klasse und einem
Test-`plugin.yml` mit Main `de.walahi.novosmp.angler.AfkHookPaperProbe` ohne Plugin-Dependencies.
Produktionscode verwendet keine NMS-Reflection; Reflection existiert nur in dieser Probe.

Die Probe verwendet die echte Paper-FishingHook-Entity und führt deren native
`catchingFish`-Schritte synchron aus. Sie prüft für jede Ausdauer-Stufe drei vollständige
Zyklen, echten BITE-Abstand, dieselbe UUID, Hook-Gültigkeit, Timer-/HUD-Übereinstimmung,
Lure V sowie Regen-/Dachvarianten. Das testet native Fishing-Schritte, keine Echtzeitdauer
und keine echte Client-Interaktion. Zusätzlich prüft sie Paper-Damage mit 1000 Unbreaking-III-
Versuchen, Enchant-/PDC-Erhalt, echtes Paper-Mending und Rod-Break.

Die vollständige Session inklusive globalem AFK, Berufs-XP, Chat und Lager muss zusätzlich
mit einem verbundenen Spieler anhand der folgenden Liste abgenommen werden.

## Ingame-Abnahme

Voraussetzungen: Paper 26.2, NovoSMP und dessen Pflichtplugins LuckPerms, ProtocolLib,
WorldEdit und Citizens; gültige NovoSMP-Konfiguration/Datenbank; Survival-Testspieler
mit aktivem Angler. Der vorhandene Testserver enthält diese Pflichtplugins derzeit nicht.

| Prüfung | Schritte | Erwartung |
| --- | --- | --- |
| Normal | Ohne AFK auswerfen, Biss abwarten, Minigame spielen | Normales BITE/Minigame und aktive Enchants, kein AFK-HUD und keine Warteanzeige |
| Ungültiger Start | `/afk` ohne Hook, mit falschem Beruf oder Hook an Land | Nur globales AFK; keine AFK-Fishing-Session, Startmeldung oder Fishing-Anzeige |
| Manueller Start | Rod einmal ins Wasser auswerfen, `/afk` | Genau eine Startmeldung; HUD sofort, Session AFK |
| Automatischer Start | Mit gültigem Wasser-Hook globales Auto-AFK abwarten | Gleicher Übergang und einmalige Startmeldung |
| Ausdauer | Alle Stufen 0–V prüfen; V mit Lure V, Regen und Dach wiederholen | 30/27/24/21/18/15 Hook-Sekunden; V bleibt 15; echte Werte entsprechen HUD |
| Fang | Ohne Rechtsklick BITE abwarten | Ein Hauptfang plus bestehender zulässiger Luck-Extra-Roll; RED; kein Minigame/Combo; einmal 25 Berufs-XP soweit entsperrt; 1–6 Minecraft-XP und Paper-Haltbarkeit |
| Mending | Beschädigte Mending-Rod halten; Berufs-XP optional sperren | Minecraft-XP repariert nach Paper-Regeln; Berufs-XP allein repariert nicht |
| Reset/Langzeit | Mindestens 10 Fänge, danach längere Session | Dieselbe Hook-UUID; HUD springt zum Intervall; keine neuen Fang-Tasks, keine Startmeldung pro Fang, kein doppelter Loot |
| Volles Lager | Lager füllen, weiter AFK angeln | Vorhandener Overflow-Drop; Session läuft weiter; Lageranzeige stimmt |
| Bewegung | Während AFK bewegen bzw. Blick verändern | Hook entfernt, HUD-Segment sofort weg, genau eine Endmeldung, keine späteren Fänge |
| AFK aus | Während AFK erneut `/afk` | Gleiches Ende; anschließend neu auswerfen für normales Angeln |
| Rod ungültig | Hotbar wechseln, Hände tauschen, Rod droppen oder aus Inventar entfernen | Sauberes Ende, kein weiterer Fang; neue Rod erhält keine alten AFK-Modifikationen |
| Rod-Break | Fast zerbrochene Rod ohne Mending/Unbreaking verwenden | Letzter Fang normal verarbeitet, Rod bricht, Hook entfernt, keine weiteren Fänge |
| Cleanup | Berufs-/Weltwechsel, Tod, Logout, Kick, Plugin-Disable | Session/Hook entfernt; genau eine Endmeldung wenn passend; keine bei Quit/Kick/Shutdown |
| Regression | Aktives Angeln mit allen Custom-Enchants, Rank/TAB und Lumi-Zone prüfen | Bisherige aktive Mechaniken und unveränderte Währungs-/AFK-Regeln |

Automatische LURED-/BITE-Events und Hook-Resets dürfen den globalen AFK-Status niemals
beenden. Selbst auswerfen/einziehen ist dagegen echte Spieleraktivität.
