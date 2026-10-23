# Mediathek TV (Android TV)

Android-TV-App mit Filmen, Serien, Krimis und Dokus aus allen frei verfügbaren öffentlich-rechtlichen
Mediatheken: ARD, ZDF, ARTE, 3sat, ORF, SRF und die Dritten. Die App zeigt keine Sender-Logos.

## Was drin ist
Stand des täglichen Katalogs, abspielbar in der Schweiz (in Deutschland mehr):

| Reihe | Umfang |
|---|---|
| Filme | ~2.800 |
| Krimis (Tatort, Polizeiruf …) | ~1.300 |
| Serien | ~480, rund 24.000 Folgen insgesamt |
| Film-Reihen (Traumschiff, Inga Lindström, Wilsberg …) | ~155 |
| Dokumentationen | ~2.000 |
| Doku-Reihen (Terra X, 37 Grad …) | ~300 |

## Funktionen
- **Startseite** mit sechs Reihen, jeweils mit „Alle anzeigen“ (Raster mit Filtern nach Sender und Genre sowie Sortierung)
- **Serien:** Staffeln zusammengeführt, Folgen sortiert, Staffel-Filter
- **Detailansicht** mit Genre, Jahr, Dauer, Sender und Sendedatum
- **Sprachfassungen** (Originalton, Hörfassung …) wählbar, **Untertitel** an/aus
- **Ländersperren:** Die App erkennt das Land des Internetanschlusses und zeigt nur, was dort abspielbar ist.
  Ist eine Sendung in mehreren Fassungen mit unterschiedlicher Freigabe vorhanden, wird automatisch
  die passende genommen. Das Land lässt sich oben über „Land: …“ wechseln.
- **Suche** sofort im Katalog und zusätzlich live in allen Mediatheken
- **Automatische Updates** der App über GitHub Releases

## Wie es funktioniert
```
MediathekView-Filmliste (~700.000 Einträge)
        │  täglich, GitHub Actions: tools/build_catalog.py
        ▼
Branch "catalog":  index.json.gz  (Startseite, ~1,6 MB)
                   s/<id>.json.gz (Folgen je Serie, beim Öffnen geladen)
        │
        ▼
App: lädt den Katalog, speichert ihn auf dem Gerät und lädt nur bei Änderungen neu
```

**Einteilung:** Filme, Krimis und Dokus werden über die Rubriken der Sender erkannt. Ob eine Reihe eine
Serie oder eine Doku-Reihe ist, entscheidet ein kleiner Text-Klassifikator. Er lernt bei jedem Lauf aus
den Reihen, deren Kategorie bekannt ist (ZDF, ARTE, 3sat), und ordnet damit die übrigen ein (ARD, Dritte,
SRF, ORF). Bei Tests lag er in rund 95 % der Fälle richtig. Unsichere Fälle lässt er weg, Nachrichten,
Talk, Sport, Kochen und Kinderprogramm werden aussortiert.

**Genre und Jahr** stammen aus Titel und Beschreibung und erscheinen nur, wenn sie dort stehen.

## Bedienung (Fernbedienung)
| Wo | Taste | Aktion |
|---|---|---|
| Überall | Steuerkreuz / OK | navigieren / öffnen |
| Überall | Zurück | eine Ebene zurück |
| Player | OK | Pause / weiter |
| Player | Links / Rechts | 10 Sekunden zurück / vor |
| Player | Untertitel-Taste | Untertitel an / aus |

## Workflows
| Workflow | Wann | Was |
|---|---|---|
| Katalog aktualisieren | täglich, bei Änderungen am Generator, manuell | baut den Katalog, Branch `catalog` |
| Build & Release APK | bei Änderungen an der App, manuell | baut die APK, Release `v1.<Nummer>` |

Die App prüft beim Start auf neue Releases und bietet die Installation an.

## Erstinstallation
APK aus dem neuesten Release auf einen USB-Stick kopieren und am Fernseher mit einem Dateimanager
(z.B. „File Commander“) installieren. Alternativ: `adb connect <IP-des-TVs>` und `adb install MediathekTV.apk`.

## Projektaufbau
| Datei | Inhalt |
|---|---|
| `tools/build_catalog.py` | Katalog-Generator (Python, scikit-learn) |
| `Model.kt` | Datenmodell, Geo-Regeln, Titel-/Folgenerkennung |
| `CatalogRepository.kt` | Katalog laden, zwischenspeichern, einlesen; Ländererkennung |
| `Api.kt` | Live-Suche |
| `MainActivity.kt` | Oberfläche |
| `PlayerActivity.kt` | Player mit Untertiteln |
| `Updater.kt` | App-Updates |

Katalog lokal bauen: `pip install scikit-learn && python3 tools/build_catalog.py --out catalog`
