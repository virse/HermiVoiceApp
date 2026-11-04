# Build & Release

Kurze Anleitung zum Bauen und Veröffentlichen einer HermiVoice-Version.

## 1. Versionierung

- `versionName` und `versionCode` werden zentral im Projekt gepflegt.
- Der `versionCode` muss bei jedem Release **höher** sein als in der installierten
  Version; sonst bietet Android das Update nicht an.
- Updates über eine bestehende Installation erfordern denselben Signaturschlüssel
  wie die Vorgängerversion.

## 2. Voraussetzungen

- JDK 21
- Android SDK
- Gradle (Wrapper ist im Projekt enthalten)

## 3. Build

```bash
export ANDROID_HOME=/pfad/zum/android-sdk
./gradlew assembleDebug      # Debug-APK
./gradlew assembleRelease    # Release-APK (signiert, sofern ein Signaturschlüssel konfiguriert ist)
```

Die APK-Dateinamen werden automatisch versioniert:

- Debug: `app-debug_<version>.apk`
- Release: `app-release_<version>.apk`

## 4. Veröffentlichung

- Die Release-APK wird als Asset an das Release angehängt.
- Die Versionshinweise werden aus dem Changelog übernommen.
- Die Update-Metadaten (`update.json`) werden zusammen mit dem Release
  veröffentlicht, damit die App neue Versionen findet.

## 5. Installation / Sideload

- APK auf einem Android-Gerät (8.0+) installieren; „Unbekannte Quellen“ für die
  Installation erlauben.
- Nach der Installation den eigenen Hermes-Agent-Endpunkt und API-Key in den
  App-Einstellungen hinterlegen (Werte liegen ausschließlich verschlüsselt im
  privaten App-Speicher).
- Der Transport läuft über HTTP (Klartext); für den produktiven Betrieb ist ein
  HTTPS-Reverse-Proxy vor dem Gateway empfohlen.

## English — Build & Release

Short guide for building and publishing a HermiVoice version.

1. **Versioning** — `versionName` and `versionCode` are maintained centrally in the
   project. The `versionCode` must be **higher** than the installed one for every
   release; otherwise Android will not offer the update. Updating over an existing
   install requires the same signing key as the previous version.

2. **Prerequisites** — JDK 21, Android SDK, Gradle (the wrapper is included in the
   project).

3. **Build** — `./gradlew assembleDebug` (debug APK); `./gradlew assembleRelease`
   (release APK, signed if a signing key is configured). APK names are versioned
   automatically: `app-debug_<version>.apk` / `app-release_<version>.apk`.

4. **Publishing** — attach the release APK as an asset to the release, take the
   release notes from the changelog, and publish the update metadata (`update.json`)
   together with the release so the app can find new versions.

5. **Installation / sideload** — install the APK on an Android device (8.0+); allow
   “unknown sources” for the installation. After installation, enter your own Hermes
   agent endpoint and API key in the app settings (values are stored exclusively
   encrypted in the private app storage). Transport is plain HTTP; for production an
   HTTPS reverse proxy in front of the gateway is recommended.
