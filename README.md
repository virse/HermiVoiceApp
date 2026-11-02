# HermiVoice

**English Version below.**

Persönliche Android-App (Kotlin nativ, Sideload) zur Sprachsteuerung des **Hermes-Agent**.
Push-to-talk: Taste halten, sprechen, loslassen → Transkription (deutsch oder englisch, je
UI-Sprache) **vollständig offline** (sherpa-onnx, Zipformer-Kroko-Modell) → Nachricht an die
Hermes-API → Chat-Antwort in der App.
Neben der Sprache kannst du jede Nachricht auch per **Tastatur** eingeben – der Modus ist
jederzeit umschaltbar (Startmodus wählbar).

## Funktionen

- **Push-to-Talk** mit Live-Zustandsfeedback: Bereit → Hört → Erkennt → Sendet → Gesendet / Fehler
- **Tastatureingabe**: jede Nachricht auch getippt statt gesprochen — Eingabefeld mit
  Tastatur-Button, jederzeit umschaltbar (Startmodus wählbar)
- **Offline-Spracherkennung**: deutsche und englische Modelle parallel (je einmaliger
  Download ~57 MB, danach rein lokal); das aktive Modell folgt der UI-Sprache, Wechsel ohne
  Download und ohne Neustart
- **Zweisprachige Oberfläche (DE/EN)**: komplette englische Übersetzung (`values-en`),
  Sprachwahl App-intern (Automatisch / Deutsch / English), System-Sprache unangetastet
- Mehrere **Chat-Sessions** mit Favoriten (Stern), durchsuchbarer Historie (Suchfeld mit
  Snippet und Trefferzahl) und Markdown-Export/Teilen
- **Mehrere Hermes-Bots (Profile)**: eigener API-Key + eigene Modellwahl je Bot, Wechsel
  über das Topbar-Dropdown (aktiv persistent); Aufruf über den Profil-Prefix-Pfad
  `…/p/<botname>/v1/chat/completions`
- **Cloud-KI-Anbieter**: Chat direkt über OpenAI-kompatible Cloud-Dienste (OpenRouter,
  OpenAI, Groq, Mistral, DeepSeek), eigener verschlüsselter API-Key je Anbieter,
  primäres + Fallback-Modell; zustandslos (keine Session-Verwaltung)
- **Konfigurierbare Zeitfenster**: First-Token 10 s–1 min (Standard 30 s, Live-Countdown),
  Gesamtantwortzeit 1–15 min (Standard 5 min), Reset-Button
- **Fehlerbehandlung**: 30-s-First-Token-Timeout, Failed-Turn-Erkennung (`hermes.failed`),
  Fallback-Notiz („Primärmodell nicht erreichbar…“), Retry „Erneut senden“
- **Streaming tokenweise (SSE)** mit „Denkt…“-Block für Reasoning und Markdown-Rendering
  (Code-Blöcke mit Tap-zu-Kopieren, Tabellen, Überschriften, Listen, Zitate)
- **Session-Polling (Gateway-Sync)**: alle 20 s im Vordergrund; Gateway ist Source of Truth
  (Backfill, Import, Zwei-Wege-Sync); Löschen einer Session entfernt den Verlauf auch über
  die Server-API (`DELETE /api/sessions/{id}`, best-effort)
- **Darstellung & Personalisierung**: dunkles UI, 6 Akzentfarben, Chat-Hintergrund
  (Standard / 4 Farbverläufe / eigenes Bild), eigene Avatare und Anzeigenamen für
  „Du“ und Hermes, Start-Eingabemodus (Tastatur/Sprache)
- **Benachrichtigung bei fertiger Antwort** (nur im Hintergrund, Ton + Textvorschau),
  konfigurierbares Haptik-Feedback, TTS-Vorlesen, Teilen-Schleuse (Share), Zwischenablage-Button
- **Konfig-Backup**: komplette Konfiguration als verschlüsseltes JSON exportieren/
  wiederherstellen (API-Keys passphrase-geschützt AES-256-GCM)

## Architektur / Ablauf

```
AudioSource (Mikrofon)
   → sherpa-onnx (offline STT, Modell je UI-Sprache: de oder en)
   → Text
   → HermesApiSender → Hermes-Gateway API (Chat-Completions)
   → Antwort → Chat-UI
```

Die Audio-Schicht ist über `AudioSource` abstrahiert — ein Wakeword-Modus („hey hermi“) ist
architektonisch vorbereitet. Alternativ gibst du die Nachricht direkt per **Tastatur** ein —
dann entfällt der Audio-/STT-Pfad.

## API & Transport

Die App spricht eine OpenAI-kompatible `Chat-Completions`-API des Hermes-Gateways an.
Die Verbindung läuft über **HTTP im Klartext** (das Gateway unterstützt kein HTTPS); der
API-Key wird als `Bearer`-Header mitgesendet. **Empfohlen:** HTTPS von der App bis zu einem
Reverse-Proxy (nginx / nginx-Proxy-Manager), der per HTTP an das Gateway weiterleitet:

```
https://<DEINE-DOMAIN>/v1/chat/completions
https://<DEINE-DOMAIN>/p/<bot>/v1/chat/completions   # bei Multiplex-Profilen
```

## Build

Voraussetzungen: JDK 21, Android SDK, Gradle 8.x.

```bash
export ANDROID_HOME=/pfad/zum/android-sdk
./gradlew assembleDebug      # Debug-APK (signiert mit Debug-Keystore)
./gradlew assembleRelease    # Release-APK (signiert, sofern Keystore vorhanden)
```

- **Release-Signierung:** `release.jks` und `keystore.properties` liegen **nicht im Repo**
  (gitignored). Fehlt `keystore.properties`, baut das Projekt trotzdem — der Release ist
  dann **unsigniert** (nicht installierbar).
- **APK-Name** wird automatisch versioniert und variantenabhängig vergeben:
  `app-debug_<version>.apk` bzw. `app-release_<version>.apk`.
- Die APK der veröffentlichten Version liegt unter `releases/`.

## Modell

Deutsches Offline-Modell, einmaliger Download beim ersten Start:
`sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06` (zipformer2, 16 kHz).
Englisches Pendant parallel unter `models/en/`.

## Tests

Instrumented-Tests auf einem laufenden Emulator (`connectedDebugAndroidTest`):

- `SherpaRecognizerInstrumentedTest` — verifiziert sherpa-onnx offline (Kroko-Modell →
  erwartetes Transkript „Alles hat ein Ende, nur“).
- `MicrophoneSourceInstrumentedTest` — echter Mikrofon-Smoke-Test (`AudioRecord`).

Modell und Test-WAV liegen als Test-Assets in `app/src/androidTest/assets/`.

## Sicherheit

- Kein Secret im Repo: `keystore.properties`, `release.jks`, `local.properties` sind gitignored.
- API-Key liegt verschlüsselt (AES-256-GCM, Android-Keystore) im privaten App-Speicher.
- `allowBackup = false` — keine Backup-/Cloud-Extraktion sensibler Daten.
- Warnung (Toast), wenn eine `http://`-Adresse außerhalb des lokalen Netzes eingetragen wird.

## Lizenz

Apache License 2.0 — siehe [LICENSE](LICENSE).

---

## English — README

English version of the guide above.

**HermiVoice** is a personal Android app (native Kotlin, sideload) for voice-controlling the
**Hermes agent**. Push-to-talk: hold the button, speak, release → transcription (German or
English, following the UI language) **fully offline** (sherpa-onnx, Zipformer-Kroko model) →
message to the Hermes API → chat answer in the app.
Besides voice, you can type any message with the **keyboard** – you can switch modes at any
time (start mode selectable).

### Features

- **Push-to-Talk** with live state feedback: Ready → Listening → Recognizing → Sending → Sent / Error
- **Keyboard input**: type any message instead of speaking — input field with keyboard
  button, switchable at any time (start mode selectable)
- **Offline speech recognition**: German and English models in parallel (one-time download
  of ~57 MB each, then fully local); the active model follows the UI language, switching
  without download and without restart
- **Bilingual UI (EN/DE)**: complete English translation (`values-en`), in-app language
  selection (Auto / German / English), system language untouched
- Multiple **chat sessions** with favourites (star), searchable history (search field with
  snippet and hit count) and Markdown export/share
- **Multiple Hermes bots (profiles)**: own API key and own model choice per bot, switch via
  the top-bar dropdown (active choice persisted); calls use the profile prefix path
  `…/p/<botname>/v1/chat/completions`
- **Cloud AI providers**: chat directly via OpenAI-compatible cloud services (OpenRouter,
  OpenAI, Groq, Mistral, DeepSeek), own encrypted API key per provider, primary + fallback
  model; stateless (no session management)
- **Configurable timeouts**: first token 10 s–1 min (default 30 s, live countdown), total
  answer time 1–15 min (default 5 min), reset button
- **Error handling**: 30 s first-token timeout, failed-turn detection (`hermes.failed`),
  fallback note (“primary model unreachable…”), “Resend” retry
- **Token-by-token streaming (SSE)** with a “Thinking…” block for reasoning and Markdown
  rendering (code blocks with tap-to-copy, tables, headings, lists, quotes)
- **Session polling (gateway sync)**: every 20 s in the foreground; the gateway is the
  source of truth (backfill, import, two-way sync); deleting a session also removes the
  history via the server API (`DELETE /api/sessions/{id}`, best-effort)
- **Appearance & personalisation**: dark UI, 6 accent colours, chat background
  (default / 4 gradients / custom image), custom avatars and display names for “You” and
  Hermes, start input mode (keyboard/voice)
- **Notification when the answer is ready** (background only, sound + text preview),
  configurable haptics, TTS read-aloud, share funnel, clipboard button
- **Config backup**: export/restore the whole configuration as encrypted JSON (API keys
  protected by passphrase, AES-256-GCM)

### Architecture / Flow

```
AudioSource (microphone)
   → sherpa-onnx (offline STT, model per UI language: de or en)
   → text
   → HermesApiSender → Hermes Gateway API (chat completions)
   → reply → chat UI
```

The audio layer is abstracted via `AudioSource` — a wake-word mode (“hey hermi”) is
architecturally prepared. Alternatively, you can type the message directly with the
**keyboard** — the audio/STT path is then skipped.

### API & Transport

The app talks to an OpenAI-compatible `chat-completions` API of the Hermes gateway. The
connection is **plain HTTP** (the gateway does not support HTTPS); the API key is sent as a
`Bearer` header. **Recommended:** HTTPS from the app to a reverse proxy (nginx /
nginx-proxy-manager) that forwards plain HTTP to the gateway:

```
https://<YOUR-DOMAIN>/v1/chat/completions
https://<YOUR-DOMAIN>/p/<bot>/v1/chat/completions   # with multiplex profiles
```

### Build

Prerequisites: JDK 21, Android SDK, Gradle 8.x.

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug      # debug APK (signed with debug keystore)
./gradlew assembleRelease    # release APK (signed, if a keystore is present)
```

- **Release signing:** `release.jks` and `keystore.properties` are **not in the repo**
  (gitignored). Without `keystore.properties` the project still builds, but the release is
  then **unsigned** (not installable).
- **APK name** is versioned automatically per variant: `app-debug_<version>.apk` /
  `app-release_<version>.apk`.
- The APK of the published version lives under `releases/`.

### Model

German offline model, one-time download on first start:
`sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06` (zipformer2, 16 kHz).
English counterpart in parallel under `models/en/`.

### Tests

Instrumented tests on a running emulator (`connectedDebugAndroidTest`):

- `SherpaRecognizerInstrumentedTest` — verifies sherpa-onnx offline (Kroko model →
  expected transcript “Alles hat ein Ende, nur”).
- `MicrophoneSourceInstrumentedTest` — real microphone smoke test (`AudioRecord`).

Model and test WAV are test assets in `app/src/androidTest/assets/`.

### Security

- No secret in the repo: `keystore.properties`, `release.jks`, `local.properties` are gitignored.
- The API key is stored encrypted (AES-256-GCM, Android Keystore) in the private app storage.
- `allowBackup = false` — no backup/cloud extraction of sensitive data.
- Warning (toast) when a `http://` address outside the local network is entered.

### License

Apache License 2.0 — see [LICENSE](LICENSE).
