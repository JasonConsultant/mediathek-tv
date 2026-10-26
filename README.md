# Mediathek TV (Android TV)

Android-TV-App mit Filmen, Serien, Krimis und Dokus aus allen frei verfügbaren öffentlich-rechtlichen
Mediatheken: ARD, ZDF, ARTE, 3sat, ORF, SRF und die Dritten. Die App zeigt keine Sender-Logos.

## Was drin ist
Stand des Katalogs, abspielbar in der Schweiz (in Deutschland mehr):

| Reihe | Umfang |
|---|---|
| Filme | ~2.800 |
| Krimis (Tatort, Polizeiruf …) | ~1.300 |
| Serien | ~480, rund 24.000 Folgen insgesamt |
| Film-Reihen (Traumschiff, Inga Lindström, Wilsberg …) | ~155 |
| Dokumentationen | ~2.000 |
| Doku-Reihen (Terra X, 37 Grad …) | ~300 |

## Aufbau
Seitenleiste links (wie TiviMate): **Start · TV-Sender · Serien · Filme · Krimis · Dokus · Meine Liste · Suche · Einstellungen**.
Jede Rubrik hat eigene Reihen (z.B. Filme nach Genre und Sender). **TV-Sender** zeigt die Senderliste links,
rechts den gewählten Sender mit Live-Stream und seinen Sendungen aus der Mediathek.

**Land** (Einstellungen): zeigt das Senderangebot dieses Landes – Schweiz mit SRF zuerst, Deutschland mit
ARD/ZDF zuerst, Österreich mit ORF zuerst. Ob ein Sender startet, entscheidet der Internetanschluss;
Sender, die über den eigenen Anschluss nicht laufen, sind mit „nur CH/AT/DE“ markiert.

## Funktionen
- **Weiterschauen:** Die App merkt sich, wo du aufgehört hast. Gesehene Sendungen bekommen einen Haken,
  nach einer Folge startet die nächste mit 10 Sekunden Countdown (OK = sofort, Zurück = beenden)
- **Meine Liste:** Filme und Serien für später merken
- **Vorschaubilder** auf allen Kacheln, in der Vorschau und in der Detailansicht (über 90 % der Sendungen)
- **Live-TV:** über 40 Sender, darunter SRF 1, SRF zwei, SRF info, Das Erste, ZDF, 3sat, ARTE, ORF und die Dritten
- **Neu:** NEU-Abzeichen seit dem letzten Besuch sowie die Reihen „Neu seit gestern“ und „Neue Folgen“
- **Automatisch aktuell:** Der Katalog wird alle 3 Stunden erneuert und von der App im Hintergrund geladen
- **Startseite** mit sechs Reihen, jeweils mit „Alle anzeigen“ (Raster mit Filtern nach Sender und Genre sowie Sortierung)
- **Serien:** Staffeln zusammengeführt, Folgen sortiert, Staffel-Filter
- **Detailansicht** mit Genre, Jahr, Dauer, Sender und Sendedatum
- **Sprachfassungen** (Originalton, Hörfassung …) wählbar, **Untertitel** an/aus
- **Ländersperren:** Die App erkennt das Land des Internetanschlusses und zeigt nur, was dort abspielbar ist.
  Ist eine Sendung in mehreren Fassungen mit unterschiedlicher Freigabe vorhanden, wird automatisch
  die passende genommen. Das Land lässt sich oben über „Land: …“ wechseln.
- **Suche** sofort im Katalog und zusätzlich live in allen Mediatheken
- **App-Updates** werden automatisch angeboten, zusätzlich gibt es den Knopf „Nach Updates suchen“

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

**Korrekturen:** Landet eine Reihe in der falschen Gruppe, trägt man sie in `tools/corrections.json` ein
(`serie`, `filmreihe`, `dokureihe` oder `ausblenden`). Direkt auf GitHub bearbeiten genügt, der Katalog
wird danach automatisch neu gebaut und ist nach wenigen Minuten in der App.

**Vorschaubilder:** Der Generator liest von jeder Sendungsseite nur den Seitenkopf (32 KB) und nimmt die
kleine Bildfassung (~480 px). Gefundene Bilder werden gemerkt (`images.json.gz`), neu gesucht wird nur für
Neuzugänge, höchstens 7.000 pro Lauf.

**Genre und Jahr** stammen aus Titel und Beschreibung und erscheinen nur, wenn sie dort stehen.

## Bedienung (Fernbedienung)
| Wo | Taste | Aktion |
|---|---|---|
| Überall | Steuerkreuz / OK | navigieren / öffnen |
| Überall | Zurück | eine Ebene zurück |
| Player | OK | Pause / weiter |
| Player, Bedienleiste ausgeblendet | Links / Rechts | 10 Sekunden zurück / vor; gehalten ab 2 Sekunden größere Schritte |
| Player | Hoch, Menü- oder Untertitel-Taste | Untertitel wählen / ausschalten |
| Live-TV | Hoch / Runter oder Programm + / − | Sender wechseln (nur hier empfangbare) |
| Live-TV | OK | Pause / weiter; Bedienleiste anzeigen |
| Live-TV | Links / Rechts | Bedienleiste öffnen – dort Untertitel mit Steuerkreuz und OK wählen |
| Live-TV | Info / Guide | Infoleiste: Jetzt und Danach (Programmführer) |
| Live-TV | Menü- oder Untertitel-Taste | Zusätzliche Abkürzung zur Untertitelauswahl (standardmäßig aus) |

**Programmführer (EPG):** `tools/build_epg.py` holt alle 3 Stunden die frei verfügbaren XMLTV-Listen von
epgshare01.online (Deutschland + Schweiz) und legt `epg.json.gz` in den Branch `catalog`.

## Workflows
| Workflow | Wann | Was |
|---|---|---|
| Katalog aktualisieren | alle 3 Stunden, bei Änderungen am Generator, manuell | baut den Katalog, Branch `catalog` |
| Build & Release APK | bei Änderungen an der App, manuell | baut und signiert die APK, Release `v2-<Nummer>` |

Der Signierschlüssel liegt nur in den GitHub-Secrets `KEYSTORE_BASE64` und `KEYSTORE_PASSWORD`, nicht im Repo.

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
| `Api.kt` → `Availability` | prüft beim Öffnen einer Sendung, ob die Datei beim Sender noch da ist (HEAD vom Gerät aus) |
| `CrashReport.kt` | Absturzbericht: wird beim nächsten Start angezeigt (abfotografieren und melden) |
| `Library.kt` | Weiterschauen, Gesehen, Merkliste (auf dem Gerät) |
| `LiveTv.kt` | SRF-Livestreams (Freigabe über die SRG-Schnittstelle) |
| `CatalogRefreshWorker.kt` | Nachladen im Hintergrund |
| `Updater.kt` | App-Updates |

Katalog lokal bauen: `pip install scikit-learn && python3 tools/build_catalog.py --out catalog`

## Tests
`gradle :app:testDebugUnitTest` – neben den Parser-Tests drückt `NavigationSmokeTest` die Fernbedienungstasten
auf der Hauptansicht durch (Robolectric, ohne Gerät): Links ins Menü, durch alle Rubriken, OK, Hoch/Runter in
Reihen und Senderliste. Jeder Fokus-Fehler, der auf dem Fernseher abstürzen würde, fällt hier auf.

## Bilder
Vorschaubilder aus der Sendungsseite (og:image, SRF über die SRG-Datenbank, phoenix über dessen Daten-Schnittstelle);
ist die Seite verschwunden, über die Suche der ARD bzw. von ZDF/3sat (nur eindeutige Titel-Treffer).

## Gelöschte Sendungen
Die MediathekView-Filmliste enthält auch Sendungen, die der Sender längst entfernt hat. Dagegen zwei Stufen:
1. **Katalogbau** (`validate` in `build_catalog.py`): HEAD auf die Videodatei; SRF über die SRG-Datenbank (URN) oder
   die Untertitel-Datei, weil die Videodatei dort nach dem Löschen liegen bleibt. Nur 404/410 gilt als weg – 403 ist
   mehrdeutig (Ländersperre vom Build-Server aus oder nur die HD-Datei fehlt). ARD-Datenbank und ZDF-Sendungsseite
   wurden probiert und verworfen: die Web-Adressen der Filmliste sind oft veraltet und melden 404 für laufende
   Sendungen. Ergebnis 3 Tage gültig (`valid.json.gz`), max. 4000 Prüfungen je Lauf.
3. **HD-Datei weg, Sendung da** (beim ZDF häufig): Die Filmliste nennt die 1080p-Datei, das ZDF bietet sie später nicht
   mehr an. Der Katalog führt deshalb die Normalqualität als Ersatz (`u2`); die App prüft beide und spielt notfalls die
   Ersatzdatei, auch mitten im Player nach einem 403/404.
2. **App**: beim Öffnen der Details eine HEAD-Anfrage vom Gerät aus (richtiges Land). Weg → Hinweis statt „Abspielen“,
   die Sendung wird lokal gemerkt (`Library.broken`) und aus allen Reihen ausgeblendet; ebenso nach HTTP 403/404 im Player.

## Datenschutz und Sicherheit
- **Verbindungen:** nur HTTPS; Klartext ist allein für zwei Sender-Server erlaubt, die kein TLS können
  (`res/xml/network_security_config.xml`). Der Katalogbau stellt alle anderen Adressen der Filmliste auf https um.
- **Wohin die App Daten schickt:** Katalog, Programmführer und Updates von GitHub; Suchbegriffe an
  MediathekViewWeb (Live-Suche); einmal pro Start eine Anfrage an Cloudflare (`cdn-cgi/trace`), um das Land der
  Internetverbindung zu erkennen (abschaltbar durch feste Landeswahl in den Einstellungen). Keine Konten, keine Kennungen,
  keine Auswertung.
- **Update:** nur die Datei `MediathekTV.apk` aus dem eigenen Release, nur von GitHub-Hosts, höchstens 40 MB; vor dem
  Installer werden Paketname, Versionsnummer und Signierzertifikat mit der installierten App verglichen.
- **Sicherung:** Fortschritt, Merkliste und Einstellungen gehen ins Android-Backup, Absturzbericht und Zwischenspeicher nicht.
- **Lokale Daten:** `files/library.json` (Fortschritt, Gesehen, Merkliste, nicht mehr verfügbare Sendungen),
  `files/catalog/` (Katalog, EPG), `files/crash.txt` (nur nach einem Absturz, wird nach dem Anzeigen gelöscht).
- **Fernbedienung:** Gedrückthalten wiederholt nur das Spulen; OK, Senderwechsel und Untertitel reagieren einmal pro Druck.
- **Downloads** sind größenbegrenzt (Katalog 64 MB, Serie 16 MB, EPG 8 MB), fehlgeschlagene Hintergrund-Abrufe werden
  bis zu dreimal wiederholt, Deep-Links (`mediathektv://play?key=…`) werden auf Host und Schlüsselform geprüft.


## Fernbedienung im Player
Tastenrouting, Spulen, Live-TV-Belegung, Zurück-Verhalten und Home/Standby sind in `TV-OPTIMIERUNG.md` beschrieben
(`RemoteInput.kt`, getestet in `RemoteInputTest`).

Bauen mit JDK 17, Android SDK 37 und dem beiliegenden Gradle-Wrapper (9.8.2):

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
```
