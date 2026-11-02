# Changelog

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
