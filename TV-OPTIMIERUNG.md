# TV- und Fernbedienungsoptimierung

Stand: 24.10.2026. Grundlage ist das hochgeladene Quellcode-ZIP, nicht ein nachgebautes Projekt aus der APK.

## Umgesetzt

| Bereich | Änderung |
|---|---|
| Tastenrouting | Eigene `RemoteInput`-Klasse. Nur tatsächlich übernommene Player-Aktionen werden abgefangen; Lautstärke, Home und normale Navigation bleiben beim System beziehungsweise Player. |
| Gedrückthalten | OK, Untertitel und Senderwechsel werden nicht unkontrolliert wiederholt. Gedrückte Navigation innerhalb der Bedienleiste bleibt möglich. |
| Spulen | Ein gehaltener Tastendruck bleibt Spulen, auch wenn die Bedienleiste erscheint. Maximal vier Wiederholungen pro Sekunde; nach zwei Sekunden 30 statt 10 Sekunden pro Schritt. Nicht suchbare Medien werden nicht übersprungen. |
| Einfache Fernbedienungen | Links/Rechts öffnet bei Live-TV die vorhandene Media3-Bedienleiste mit Untertitelknopf. Menü- und Untertiteltaste bleiben zusätzliche Abkürzungen. OK pausiert/setzt fort. |
| Fokus | Die Bedienleiste erhält einen konkreten Startfokus. Untertiteldialoge geben den Fokus zurück. Gemerkte Kacheln und der Abspielknopf fordern den Fokus auch nach einer Lifecycle-Rückkehr wieder an. |
| Zurück | Schließt zuerst den Folge-Countdown beziehungsweise sichtbare Player-/Infoleiste, anschließend den Player. Verwendung des Activity-Back-Dispatchers. |
| Medientasten | Play und Pause sind getrennte, eindeutige Aktionen. Eine Media3-MediaSession unterstützt zusätzlich die Systemintegration. Keine Hintergrund-Player-Service-Erweiterung. Jede Session bekommt eine eigene Kennung, damit sich zwei Player-Instanzen kurzzeitig überlappen dürfen (sonst Absturz „Session ID must be unique“). |
| Decoder | Decoder-Fallback aktiviert; bei ausgewählten Decoder-/Formatfehlern wird die vorhandene Ersatzadresse versucht, ohne den Titel deshalb als gelöscht zu markieren. |
| Senderwechsel | Alte SRG-Aufträge werden abgebrochen; eine Generationsnummer verhindert, dass verspätete Antworten den aktuellen Sender überschreiben. |
| Home / Standby | Wiedergabe und Timer pausieren im Hintergrund. Bei Rückkehr bleibt die Wiedergabe pausiert; Live-Adressen werden erneut aufgelöst. Ein unterbrochener Folgen-Countdown wartet auf Bestätigung mit OK. |
| Hintergrundkatalog | Nach ausgeschöpften Wiederholungen wird ein Abruffehler als Fehler gemeldet, nicht als Erfolg. |
| Vorschau | Eigenes Paket `com.example.mediathektv.preview`, eigener Name und eigenes Deep-Link-Schema. Kein Release-Updateangebot für das Originalpaket. Verlauf und Einstellungen sind getrennt. |
| Build-Workflow | Gradle 8.7 explizit festgelegt. Vorschau und Tests laufen unabhängig vom Vorhandensein eines Release-Schlüssels; APK wird als Workflow-Artefakt bereitgestellt. Release-Veröffentlichung weiterhin nur mit vorhandenen Secrets. |

Der Quellcode enthielt bereits HTTPS-Regeln, Backup-Ausschlüsse, Update-Prüfungen und einen anfänglichen Player-Fokus. Diese sind nicht als neue Leistungen dieser Überarbeitung zu verstehen.

## Bedienung

- In Listen: Steuerkreuz und OK wie bisher.
- Im Video bei ausgeblendeter Bedienleiste: OK = Pause/Weiter; Links/Rechts = Spulen, sofern suchbar; Hoch = Untertitel.
- Bei Live-TV und ausgeblendeter Bedienleiste: Hoch/Runter = Senderwechsel; Links/Rechts = Bedienleiste; OK = Pause/Weiter; Info/Guide = Jetzt/Danach.
- Bei sichtbarer Bedienleiste: Steuerkreuz navigiert zwischen den normalen Player-Funktionen; OK aktiviert die fokussierte Funktion, etwa Untertitel.
- Lautstärke, Home und Systemtasten werden nicht pauschal abgefangen.
- Zurück schließt zuerst eine sichtbare Player-/Infoleiste und verlässt dann den Player. Der Folgen-Countdown wird mit Zurück beendet.

## Installation und Build

Die Vorschau ist eine Testversion mit Debug-Signatur. Sie lässt sich neben der bisherigen App installieren; sie ist kein signiertes Update der Original-App und übernimmt deren Verlauf nicht automatisch.

Mit JDK 17, Android SDK 35 und Gradle 8.7 im Projektordner:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`.

Unter Windows lautet der Aufruf `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`.
Der beiliegende Gradle-Wrapper lädt die festgelegte Version 8.7 und prüft deren SHA-256-Prüfsumme.

Für ein Release muss `BUILD_NUMBER` größer als der bereits verteilte versionCode sein. Der ursprüngliche Signierschlüssel bleibt erforderlich, wenn vorhandene Installationen aktualisiert werden sollen. Schlüssel und Passwörter gehören nicht in das ZIP.

## Prüfung

- Neun gezielte `RemoteInputTest`-Regressionstests wurden separat gegen das Android-SDK kompiliert und auf der JVM erfolgreich ausgeführt.
- Vollständiger Gradle-Build `:app:assembleDebug :app:testDebugUnitTest`: erfolgreich mit JDK 17.0.21 und Gradle 8.7.
- Alle 23 Tests bestanden, keine Fehler und keine übersprungenen Tests. Darin enthalten: neun Fernbedienungs-Regressionstests und der vorhandene Robolectric-Oberflächentest mit D-Pad-Navigation. Die separate Ausführung der neun Tests wird nicht zusätzlich zur Gesamtzahl gezählt.
- APK-Signatur mit `apksigner verify` erfolgreich geprüft (APK v2, Debug-Signierer).
- ZIP-Integrität der APK und `zipalign -c -P 16 4` erfolgreich geprüft. Diese Prüfungen ersetzen keinen Laufzeittest auf einem 16-KB-Gerät.
- Maschinenlesbare Ergebnisse: `pruefung/Build-und-Tests.json`; punktuelle Erreichbarkeit: `pruefung/Erreichbarkeit.json`.
- Keine Prüfung auf einem echten Fernseher und keine Garantie für alle Hersteller, Fernbedienungen oder Medienformate.

Die feste Katalogindex-, EPG- und GitHub-Release-Adresse lieferte bei der Prüfung am 24.10.2026 HTTP 200. Bei den beiden gzip-Dateien wurde zusätzlich der gzip-Header der Antwort geprüft. Das ist eine punktuelle Erreichbarkeitsprüfung, keine vollständige Validierung sämtlicher Sendungs-, Untertitel-, Bild- oder Streamadressen. Medien können ablaufen oder geografisch gesperrt sein. Die Original-Serveradressen wurden nicht auf eigene Dienste umgestellt.

## Noch auf Hardware abzunehmen

| Prüfung | Erwartetes Ergebnis |
|---|---|
| Fernbedienung ohne Menütaste | Live-TV öffnen, Links/Rechts drücken, Untertitelknopf per D-Pad/OK erreichen. |
| OK zwei Sekunden halten | Keine mehrfachen Play/Pause-Wechsel durch Wiederholungsereignisse. |
| Lautstärke halten | Lautstärke ändert sich weiter, ohne versehentlichen Senderwechsel. |
| Links/Rechts beim Film halten | Kontrolliertes Spulen; kein Wechsel zur Fokusnavigation mitten im selben Tastendruck. |
| Navigation in sichtbarer Leiste halten | Fokus/Zeitleiste reagieren weiter. |
| Schnell zwischen SRG-Sendern wechseln | Kein Zurückspringen auf eine verspätete vorherige Stream-Antwort. |
| Home / Standby / Rückkehr | Kein Ton im Hintergrund; Rückkehr mit erreichbaren Bedienelementen; explizites Fortsetzen. |
| Zurück vom Player | Fokus wieder in der passenden Detailansicht oder auf der gemerkten Kachel. |
| Bluetooth / HDMI-CEC | OK, Zurück, Play/Pause und Lautstärke mit der tatsächlich verwendeten Hardware prüfen. |
| Schwaches Gerät / Netzwerkfehler | Decoder- und Stream-Ersatz prüfen; kein unkontrolliertes Autoplay im Hintergrund. |

Zielplattform bleibt Android TV / Google TV ab API 24. Android-basierte Fire-TV-Geräte brauchen eigene Abnahmetests. Samsung Tizen und LG webOS benötigen eigene Anwendungen oder einen kompatiblen externen Zuspieler.

## Technische Referenzen

- https://developer.android.com/media/media3/ui/androidtv
- https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory
- https://developer.android.com/docs/quality-guidelines/tv-app-quality
- https://developer.android.com/media/media3/exoplayer/supported-formats
