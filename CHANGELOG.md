# Changelog

## [1.0.2] — Update-Funktion (2026-11-04, versionCode 3)

### Neu

- **App-Update-Prüfung:** Die App liest `update.json` vom öffentlichen GitHub-Spiegel
  und vergleicht `versionCode` mit der installierten Version. Prüfung manuell in den
  Einstellungen und automatisch beim App-Start (max. einmal alle 24 h, abschaltbar).
- **Update-Download mit Integritätsprüfung:** Download über den System-DownloadManager,
  SHA-256-Vergleich gegen `update.json`, anschließend Installationsdialog über den
  bestehenden FileProvider. Bei fehlender Berechtigung führt die App zur Einstellung
  „Unbekannte Quellen“.
- **Einstellungen „Update & Backup“:** Update-Block (Toggle, Status, manuelle Prüfung)
  und Backup-Block in einem Screen; die bisherige Backup-Kategorie geht darin auf.
- **Changelog als Markdown:** Der Update-Dialog rendert den Changelog mit Markwon
  (Listen, Hervorhebungen, Links).

### Geändert

- Update-Einstellungen (`update_auto_check`, `update_skipped_version`,
  `update_pending_force_version`, `update_last_success_millis`) werden ins
  Konfigurations-Backup aufgenommen. Beim Import wird der Prüfzeitstempel bewusst
  zurückgesetzt, damit ein neues Gerät sofort prüft.
- „Später“ merkt sich die weggetippte Version; erst eine neuere Version fragt beim
  Start wieder nach. Pflicht-Updates (`forceUpdate`/`minSupportedVersionCode`) werden
  auch bei deaktiviertem Auto-Check durchgesetzt.

### Technik

- Neues Paket `de.hermi.voice.update` (Checker, Version, SHA-256, Downloader,
  Installer, UI, Manager); `MainActivity` bleibt schlank.
- Markwon-Abhängigkeit für die Changelog-Darstellung.
- `REQUEST_INSTALL_PACKAGES` und `external-files-path` für die APK-Installation.

### English

Adds the in-app update check against `update.json` (manual and automatic at startup,
at most once every 24 h, can be disabled), download via DownloadManager with SHA-256
verification and an install dialog through the existing FileProvider, a combined
“Update & Backup” settings screen, and Markdown changelog rendering via Markwon.
Update preferences are included in the configuration backup; the last-check timestamp
is reset on import so a new device checks immediately. “Later” remembers the skipped
version; forced updates are enforced even when automatic checks are disabled.

## [1.0.1] — Bugfix-Release (2026-11-03, versionCode 2)

Behebt die sechs bei einer externen Code-Durchsicht gemeldeten Fehlerklassen
(Nebenläufigkeit, Netzwerk-Timeout, Recovery nach Schlüsselverlust, UI-Thread-Last).

### Behoben

- **Abgeschnittene Satzenden bei der Sprachaufnahme:** Der Aufnahme-Stop wartete auf
  einen Hintergrund-Thread und meldete auch nach Ablauf des 12-s-Budgets „fertig“;
  zwei Stopps erzeugten zwei Wartethreads und zwei Callbacks. Jetzt gibt es genau
  einen Wartevorgang, der alle Anfragen sammelt, und ein Timeout wird explizit
  protokolliert statt still als Erfolg behandelt.
- **Absturzrisiko (Use-after-Free) in der Spracherkennung:** `feed()`/`partialResult()`
  liefen auf dem Audio-Thread ohne das Lock, unter dem Recognizer und Stream
  freigegeben werden (Erkennungsende, `onDestroy`). Die nativen Aufrufe laufen jetzt
  unter demselben Lock und greifen nie auf einen bereits freigegebenen Stream zu.
- **Antwort-Timeout wirkungslos:** Der Socket-Read-Timeout wurde erst nach dem Öffnen
  des Streams gesetzt und blieb dadurch ohne Wirkung (faktisch fest 120 s). Er wird
  jetzt vor dem Verbindungsaufbau gesetzt; die konfigurierte Antwortzeit (1–15 min)
  wird als Deadline im Leseloop geprüft. Ein First-Token-Watchdog hält die kurze
  First-Token-Frist auch bei einem völlig stummen Server ein. Gilt für Gateway- und
  Cloud-Streams.
- **Stiller Verlust der Zugangsdaten:** Ist der Keystore-Key weg (Restore/Werksreset),
  wurden API-Keys stumm zu leeren Strings — die App wirkte „nicht konfiguriert“,
  obwohl ein Backup existiert. Die App erkennt das jetzt beim Start und zeigt einen
  einmaligen Hinweis (API-Key neu eintragen oder Backup importieren).
- **ANR-Risiko beim Modellaufbau:** Die ONNX-Modelle wurden im UI-Thread geladen
  (Sekunden bei `onResume`/Sprachwechsel). Der Aufbau läuft jetzt im Hintergrund mit
  sichtbarem Zustand „Modell wird geladen“; ein Aufnahmestart währenddessen wird
  erklärt statt still ignoriert.
- **Splash startete die App nach Abbruch trotzdem:** Der verzögerte Start wird beim
  Verlassen der Splash-Activity jetzt abgebrochen.
- **Hängender TTS-Puffer nach Freigabe der Engine:** Nach `shutdown()` gepufferter
  Text wurde nie mehr gesprochen; die Sprachausgabe lehnt das jetzt sauber ab.
- **Parallele Antwort-Streams:** `sendText` lehnte einen zweiten Stream bisher nur über
  den UI-Button ab. Der Schutz greift jetzt auch für programmatische Aufrufe (Sprache,
  Teilen-Funktion) mit Hinweis statt stillem Start.

### Technik

- Verschlüsselte Werte tragen jetzt den expliziten Marker `enc:v1:`. „Ist das
  verschlüsselt?“ wird damit ohne teuren Decrypt-Versuch beantwortet; Altbestand ohne
  Marker bleibt lesbar.
- Neue Zustandsanzeige `MODEL_LOADING`, neue Strings DE/EN.

### English

Fixes the six issue classes reported by an external code review (concurrency,
network timeouts, recovery after key loss, main-thread load): microphone stop
now reports one coordinated completion, native recognizer calls are protected by
the release lock, the configured answer timeout actually applies (plus a
first-token watchdog), undecryptable credentials produce a one-time notice,
the ONNX model loads off the UI thread with a visible loading state, the splash
no longer starts the app after being dismissed, TTS no longer buffers after
shutdown, and a second answer stream is rejected instead of silently starting.
Encrypted values now carry an `enc:v1:` marker.

## [1.0.0] — Initial-Release (2026-11-02, versionCode 1)

Konsolidierter Neustart der Projektversionierung: Die Version 1.0.0 übernimmt den
vollständigen Funktionsumfang des bisherigen Entwicklungsstands (2.0.2) als Basis. Die
komplette Detailhistorie aller früheren Versionen liegt unverändert im Archiv-Repo
`HermiVoice-legacy`.

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

- Kotlin nativ, Gradle 8.x, JDK 21; minSdk 24 (Android 7.0+)
- Signiert mit Keystore `release.jks`; Versionierung zentral in `app/build.gradle.kts`

### English

Consolidated restart: version 1.0.0 is the full feature set of the previous development
state (2.0.2) under a fresh versioning scheme. The complete history of all earlier
versions lives in the archive repo `HermiVoice-legacy`.

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
- **Technical:** native Kotlin, Gradle 8.x, JDK 21, minSdk 24 (Android 7.0+); signed with
  `release.jks`; versioning centralized in `app/build.gradle.kts`.
