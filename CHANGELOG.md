# Changelog

## [1.0.4] — Reasoning steuerbar (2026-11-13, versionCode 5)

### Neu

- **Eigene Einstellungs-Sektion „Reasoning"**: Denken der Modelle lässt sich jetzt
  gezielt steuern — auf Wunsch aus (deutlich schnellere Antworten auf lokaler Hardware)
  oder an mit einer Denk-Stufe von `minimal` bis `max`. Die beiden Bereiche
  „lokale Modelle" und „Cloud-Modelle (Hermes-Server)" sind voneinander unabhängig;
  welcher greift, entscheidet das Modell, das die Antwort tatsächlich liefert.
- **Pro Bot übersteuerbar**: jeder Bot (Profil) kann für lokale und für Cloud-Modelle
  eigene Werte setzen (Global / An / Aus + eigene Stufe) und erbt sonst die globalen.
- **Pro Cloud-Anbieter übersteuerbar**: im Cloud-Editor hat jeder Eintrag einen eigenen
  Reasoning-Schalter samt Stufe — im anbieterspezifischen Format (OpenRouter, DeepSeek,
  Groq, Mistral, OpenAI).
- **First-Token-Fenster wächst mit**: denkt ein Modell, wird die Wartezeit bis zum ersten
  Token automatisch gestreckt (mindestens 90 s), damit lange Denkphasen die Antwort nicht
  abbrechen.

### Geändert

- Die Reasoning-Regler sind aus „Hermes-KI-Modell" in die neue Sektion gewandert —
  dort steht nur noch die Modellwahl mit Fallback, Schwelle und Test.
- Modelle ohne Reasoning-Unterstützung werden ausgegraut; Anbieter, die das Denken nicht
  abschalten können (z. B. OpenAI), bieten „Aus" nicht mehr an, statt einen wirkungslosen
  Schalter zu zeigen.

### Technik

- Hermes-Kanal: Steuerung pro Anfrage über `model_options.reasoning` (`enabled`, `effort`).
- Cloud-Direktkanal: anbietergerechte Felder (`reasoning`, `thinking`, `reasoning_effort`).
- Reasoning-Einstellungen sind im Konfig-Backup enthalten (global, je Bot, je Anbieter).


## [1.0.3] — Markdown-Tabellen lesbar (2026-11-09, versionCode 4)

### Neu

- **Tabellen als Karten:** Tabellen werden nicht mehr als ausgerichteter
  Monospace-Block dargestellt, sondern Zeile für Zeile: Die erste Spalte steht als
  Überschrift, die übrigen Spalten folgen eingerückt als „Spalte: Wert“. Dadurch
  bleiben die Zuordnungen auch auf schmalen Displays erhalten und nichts bricht
  mitten in der Zeile um.

### Geändert

- **Tabellen ohne führende `|`** werden jetzt ebenfalls erkannt (Modelle lassen die
  äußeren Striche häufig weg); vorher landeten solche Tabellen als Fließtext im Chat.
- **Formatierung in Zellen:** Fett, Code und Links funktionieren jetzt auch innerhalb
  von Tabellenzellen.
- Spaltennamen aus der Kopfzeile werden gedimmt angezeigt; die Trennzeile aus
  `---` entfällt.

### Technik

- Markdown-Formatter: Tabellen-Rendering neu aufgebaut (Spaltenbreiten-Auffüllung
  per Leerzeichen entfernt, Hängeeinzug für die Spaltenzeilen).
- Erstmals automatisierte Tests für den Markdown-Formatter (6 Fälle, inklusive der
  Tabellenformen aus der Praxis).

## [1.0.2] — Update-Funktion (2026-11-04, versionCode 3)

### Neu

- **App-Update-Prüfung:** Die App liest `update.json` vom öffentlichen GitHub-Spiegel
  und vergleicht `versionCode` mit der installierten Version. Prüfung manuell in den
  Einstellungen und automatisch beim App-Start (max. einmal alle 24 h, abschaltbar).
- **Update-Download mit Integritätsprüfung:** Download mit SHA-256-Vergleich gegen
  `update.json`, anschließend Installationsdialog. Bei fehlender Berechtigung führt
  die App zur Einstellung „Unbekannte Quellen“.
- **Einstellungen „Update & Backup“:** Update-Block (Toggle, Status, manuelle Prüfung)
  und Backup-Block in einem Screen; die bisherige Backup-Kategorie geht darin auf.
- **Changelog als Markdown:** Der Update-Dialog rendert den Changelog als Markdown
  (Listen, Hervorhebungen, Links).

### Geändert

- Update-Einstellungen werden ins Konfigurations-Backup aufgenommen. Beim Import wird
  der Prüfzeitstempel bewusst zurückgesetzt, damit ein neues Gerät sofort prüft.
- „Später“ merkt sich die weggetippte Version; erst eine neuere Version fragt beim
  Start wieder nach. Pflicht-Updates werden auch bei deaktiviertem Auto-Check
  durchgesetzt.

### Technik

- Neue Update-Infrastruktur (Prüfung, Download, Integritätsprüfung, Installation);
  die Haupt-Activity bleibt schlank.
- Markdown-Darstellung für den Changelog.
- Installationsberechtigung und Dateifreigabe für die APK-Installation.

### English

Adds the in-app update check against `update.json` (manual and automatic at startup,
at most once every 24 h, can be disabled), download with SHA-256 verification and an
install dialog, a combined “Update & Backup” settings screen, and Markdown changelog
rendering. Update preferences are included in the configuration backup; the last-check
timestamp is reset on import so a new device checks immediately. “Later” remembers the
skipped version; forced updates are enforced even when automatic checks are disabled.

## [1.0.1] — Bugfix-Release (2026-11-03, versionCode 2)

Behebt sechs gemeldete Fehlerklassen (Nebenläufigkeit, Netzwerk-Timeout, Recovery
nach Schlüsselverlust, UI-Thread-Last).

### Behoben

- **Abgeschnittene Satzenden bei der Sprachaufnahme:** Der Aufnahme-Stop meldete auch
  nach Ablauf des Zeitbudgets „fertig“; zwei schnelle Stopps erzeugten zwei parallele
  Wartevorgänge. Jetzt gibt es genau einen Wartevorgang, der alle Anfragen sammelt,
  und ein Timeout wird explizit behandelt.
- **Absturzrisiko in der Spracherkennung:** Zugriffe auf den Recognizer waren nicht
  durch dieselbe Sperre geschützt wie die Freigabe. Die nativen Aufrufe laufen jetzt
  unter derselben Sperre und greifen nie auf einen bereits freigegebenen Stream zu.
- **Antwort-Timeout wirkungslos:** Die konfigurierte Antwortzeit wurde faktisch nicht
  angewendet. Sie greift jetzt inklusive First-Token-Watchdog, auch bei einem stummen
  Server; gilt für Gateway- und Cloud-Streams.
- **Stiller Verlust der Zugangsdaten:** Ist der lokale Schlüssel weg (Restore/Werksreset),
  wurden API-Keys stumm zu leeren Strings — die App wirkte „nicht konfiguriert“,
  obwohl ein Backup existiert. Die App erkennt das jetzt beim Start und zeigt einen
  einmaligen Hinweis (API-Key neu eintragen oder Backup importieren).
- **ANR-Risiko beim Modellaufbau:** Das Modell wurde im UI-Thread geladen (Sekunden bei
  Sprachwechsel/Start). Der Aufbau läuft jetzt im Hintergrund mit sichtbarem Zustand
  „Modell wird geladen“; ein Aufnahmestart währenddessen wird erklärt statt still
  ignoriert.
- **Splash startete die App nach Abbruch trotzdem:** Der verzögerte Start wird beim
  Verlassen der Splash-Activity jetzt abgebrochen.
- **Hängender TTS-Puffer nach Freigabe der Engine:** Gepufferter Text wurde nie mehr
  gesprochen; die Sprachausgabe lehnt das jetzt sauber ab.
- **Parallele Antwort-Streams:** Ein zweiter Stream wurde bisher nur über den UI-Button
  abgelehnt. Der Schutz greift jetzt auch für programmatische Aufrufe (Sprache,
  Teilen-Funktion) mit Hinweis statt stillem Start.

### Technik

- Verschlüsselte Werte sind jetzt eindeutig erkennbar; Altbestand bleibt lesbar.
- Neue Zustandsanzeige beim Modell-Laden, Strings DE/EN.

### English

Fixes six reported issue classes (concurrency, network timeouts, recovery after
key loss, main-thread load): microphone stop now reports one coordinated
completion, recognizer access is protected by the same lock as release, the
configured answer timeout actually applies (plus a first-token watchdog),
undecryptable credentials produce a one-time notice, the model loads off the UI
thread with a visible loading state, the splash no longer starts the app after
being dismissed, TTS no longer buffers after shutdown, and a second answer
stream is rejected instead of silently starting. Encrypted values are now
clearly identifiable.

## [1.0.0] — Initial-Release (2026-11-02, versionCode 1)

Konsolidierter Neustart der Projektversionierung: Die Version 1.0.0 übernimmt den
vollständigen Funktionsumfang des bisherigen Entwicklungsstands (2.0.2) als Basis. Die
Detailhistorie aller früheren Versionen ist archiviert.

### Neu

- **Push-to-Talk-Sprachsteuerung** mit Live-Zustandsfeedback (Bereit → Hört → Erkennt →
  Sendet → Gesendet / Fehler)
- **Offline-Spracherkennung** (sherpa-onnx, Zipformer-Kroko): deutsche und englische
  Modelle parallel, Sprachwechsel ohne Download und ohne Neustart
- **Zweisprachige Oberfläche (DE/EN)** mit App-interner Sprachwahl
  (Automatisch / Deutsch / English)
- **Mehrere Hermes-Bots (Profile)** mit eigenem API-Key und Modellwahl je Bot
  (Profil-Prefix-Pfad `…/p/<botname>/v1/chat/completions`)
- **Cloud-KI-Anbieter** (OpenRouter, OpenAI, Groq, Mistral, DeepSeek) mit verschlüsseltem
  API-Key, primär + Fallback
- **Chat-Sessions**: Favoriten, durchsuchbare Historie, Markdown-Export/Teilen,
  Streaming-Antworten (SSE)

### Geändert

- Einstellungen neu strukturiert (Kategorien-Screens), Gateway als Source of Truth
  (Zwei-Wege-Sync)

### Behoben

- Live-Sprachvorschau (Doppel-Blasen), Cloud-Websuche (Server-Tools je Anbieter),
  proaktive Benachrichtigungs-Berechtigung (Android 13+), Tastatur verdeckt
  Eingabefelder nicht mehr

### Technik

- Kotlin nativ; Android 8.0+ (minSdk 26).

### English

Consolidated restart: version 1.0.0 is the full feature set of the previous development
state (2.0.2) under a fresh versioning scheme. The history of all earlier versions is
archived.

- **Added:** push-to-talk voice control with live state feedback; fully offline speech
  recognition (German + English models in parallel); bilingual UI (DE/EN) with in-app
  language switch; multiple Hermes bots (profiles) with per-bot API key and model choice;
  cloud AI providers (OpenRouter, OpenAI, Groq, Mistral, DeepSeek) with encrypted keys,
  primary + fallback; chat sessions with favorites, searchable history, Markdown export
  and streaming responses (SSE).
- **Changed:** restructured settings screens; gateway as source of truth (two-way sync).
- **Fixed:** live speech preview, cloud web search (native server tools per provider),
  proactive notification permission on Android 13+, keyboard no longer covers input
  fields.
- **Technical:** native Kotlin; Android 8.0+ (minSdk 26).
