# Mediathek TV (Android TV)

Eine Android-TV-App, die ARD, ZDF, die Dritten, ARTE, 3sat, phoenix und SRF in einer
Oberfläche zeigt. Datenquelle: öffentliche MediathekViewWeb-API. Keine Sender-Logos.

## Bauen
1. Android Studio (Koala oder neuer) öffnen → "Open" → diesen Ordner.
2. Gradle-Sync abwarten (beim ersten Mal lädt es Abhängigkeiten).
3. Build → Build APK(s). Die APK liegt dann unter `app/build/outputs/apk/debug/`.

## Auf den Fernseher bringen
- **Kabellos:** Auf dem TV Entwickleroptionen + USB-Debugging aktivieren
  (Einstellungen → Über → 7× auf Build-Nummer tippen), dann am PC:
  `adb connect <IP-des-TVs>` und `adb install app-debug.apk`
- **Einfach:** APK auf einen USB-Stick, am TV mit "File Commander" o.ä. installieren.

## Bedienung
- Steuerkreuz: durch Reihen und Kacheln navigieren, OK = abspielen
- Oben: Suchfeld (Fernbedienung/Bildschirmtastatur), Enter = suchen
- Im Player: OK = Pause, Links/Rechts = 10 s springen, Zurück = beenden

## Updates
Bei jedem Push auf `main` baut GitHub Actions automatisch eine neue APK und legt sie als
Release ab (Tag `v1.<Laufnummer>`). Die App prüft beim Start GitHub Releases und bietet
die neue Version zum Installieren an – ein Klick auf der Fernbedienung genügt.
Beim allerersten Mal muss am TV "Unbekannte Apps installieren" für Mediathek TV erlaubt werden.

Das Repo ist privat: Der Build braucht das Secret `GH_UPDATE_TOKEN` (GitHub → Settings →
Secrets and variables → Actions), ein Fine-grained-Token mit "Contents: Read-only" nur für
dieses Repo. Es wird beim Bauen in die App eingebettet, damit sie Updates laden kann.
Die Release-APK ist mit R8 verschleiert.

Manuell auslösen: GitHub → Actions → "Build & Release APK" → "Run workflow".

## Anpassen
- Reihen auf der Startseite: `Theme.kt` → `HOME_ROWS`
- Farben: `Theme.kt` → `TvColors`
- API-Filter (z.B. Mindestdauer): `Api.kt`

## Noch offen (nächste Schritte)
- Vorschaubilder (MediathekViewWeb liefert keine – dafür die ARD-/ZDF-APIs nutzen)
- Merkliste / "Weiterschauen" lokal speichern
- Sendungen nach Thema gruppieren (z.B. alle Tatort-Folgen)
