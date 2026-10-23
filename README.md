# Mediathek TV (Android TV)

Android-TV-App für Filme, Serien, Krimis und Dokus aus allen frei verfügbaren
öffentlich-rechtlichen Mediatheken: ARD, ZDF, ARTE, 3sat, ORF, SRF und die Dritten.
Datenquelle ist die öffentliche MediathekViewWeb-API. Die App zeigt keine Sender-Logos.

## Funktionen
- **Startseite** mit den Reihen Filme, Serien, Krimi und Dokumentationen sowie einer Suche über alle Sender
- **Serien**: eine Kachel pro Serie, Staffeln zusammengeführt. Die Folgenübersicht ist nach Staffel/Folge sortiert, mit Staffel-Filter
- **Detailansicht** mit Genre, Jahr, Dauer, Sender, Sendedatum und Beschreibung
- **Sprachfassungen**: Originalton, Englisch, Hörfassung usw. sind gebündelt und vor dem Abspielen wählbar
- **Untertitel**, falls vorhanden, an- und abschaltbar
- **Automatische Updates** über GitHub Releases

Genre und Jahr werden aus Titel und Beschreibung gelesen. Sie erscheinen nur, wenn sie dort stehen
(bei ARTE fast immer, bei ARD/ZDF selten). Eigene Genre- oder Jahresfelder hat die Datenquelle nicht.

## Bedienung (Fernbedienung)
| Wo | Taste | Aktion |
|---|---|---|
| Überall | Steuerkreuz / OK | navigieren / öffnen |
| Überall | Zurück | eine Ebene zurück |
| Player | OK | Pause / weiter |
| Player | Links / Rechts | 10 Sekunden zurück / vor |
| Player | Untertitel-Taste | Untertitel an / aus |

## Updates
Jeder Push auf `main` baut über GitHub Actions eine neue APK und legt sie als Release `v1.<Nummer>` ab.
Die App prüft beim Start auf neue Releases und bietet die Installation an. Beim ersten Update fragt der
Fernseher einmal, ob „Mediathek TV“ Apps installieren darf.

Manuell auslösen: GitHub → Actions → „Build & Release APK“ → „Run workflow“.

## Erstinstallation
APK aus dem neuesten Release auf einen USB-Stick kopieren und am Fernseher mit einem Dateimanager
(z.B. „File Commander“) installieren. Alternativ: `adb connect <IP-des-TVs>` und `adb install MediathekTV.apk`.

## Projektaufbau
| Datei | Inhalt |
|---|---|
| `Model.kt` | Datenmodell; erkennt Sprache, Jahr, Genre und Folgennummer |
| `Api.kt` | Abfrage der Datenquelle und Zusammenstellung der Reihen (`Catalog`) |
| `MainActivity.kt` | Oberfläche: Startseite, Serien, Details |
| `PlayerActivity.kt` | Vollbild-Player mit Untertiteln |
| `Updater.kt` | Update-Prüfung und Installation |
| `Theme.kt` | Farben |

Lokal bauen: in Android Studio öffnen, oder `gradle :app:assembleRelease`.
