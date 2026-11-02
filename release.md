# Release-Ablauf

Kompakte Anleitung zum Veröffentlichen einer neuen HermiVoice-Version (Build, APK-Ablage,
Release-Erstellung mit Assets). Die vollständige Detailhistorie aller Versionen liegt im
Archiv-Repo `HermiVoice-legacy`.

## 1. Version anpassen

- App-Version und `versionCode` werden **zentral in `app/build.gradle.kts`** gepflegt.
- Update-Installation über eine bestehende Version erfordert denselben Signaturschlüssel;
  bei identischem `versionCode` ist die neue APK direkt als Update installierbar
  (Beispiel: 1.0.x über 1.0.0 — identischer versionCode 1, gleicher Keystore `release.jks`).

## 2. Voraussetzungen

- JDK 21, Android SDK, Gradle 8.x; `ANDROID_HOME` gesetzt.
- **Release-Signierung:** der lokale Keystore `release.jks` und
  `keystore.properties` liegen **nicht im Repo** (gitignored). Wird der Signatur-Block
  gefunden, signiert das Build damit. Ohne `keystore.properties` baut das Projekt zwar
  weiter, der Release ist aber **unsigniert** und dadurch auf einem Gerät **nicht installierbar**.

## 3. Build

```bash
export ANDROID_HOME=/pfad/zum/android-sdk
./gradlew assembleDebug      # Debug-APK (signiert mit Debug-Keystore)
./gradlew assembleRelease    # Release-APK (signiert, sofern Keystore vorhanden)
```

Der APK-Name wird automatisch versioniert und **variantenabhängig** vergeben:

- Debug: `app-debug_<version>.apk`
- Release: `app-release_<version>.apk`

## 4. APK-Ablage

- Die APK der veröffentlichten Version liegt unter **`releases/`** im Projektordner
  (lokal, gitignored — die Dateien werden nicht ins Repo committet). Namensschema und
  Ablageort bleiben bei jedem Release gleich (Beispiel: `releases/app-release_1.0.0.apk`
  — signierte Release-Variante, Keystore `release.jks`).
- Die Datei ist damit zugleich der direkte Download-/Sideload-Pfad (APK auf dem Gerät
  ausführen bzw. per Dateimanager installieren; „Unbekannte Quellen“ für Sideload erlauben).

## 5. Release-Erstellung

- Neues Release mit der Release-APK als **Asset** erstellen (z. B. Tag der neuen Version
  im Repo, APK anhängen). Der Repo-Stand umfasst vollständig: Quellcode, README,
  CHANGELOG und LICENSE (Apache-2.0); die Release-APK aus `releases/` wird als Asset
  angehängt.
- **Doku mitschreiben:** Eintrag in `CHANGELOG.md` nach dem Schema
  `## [Version] — Datum (versionCode N)` mit Kategorien (Neu / Geändert / Behoben /
  Technik); Detailänderungen seit dem letzten Release stammen aus dem Commit-Verlauf.
  Die Dokumentation (README / CHANGELOG / Release-Ablauf) bei größeren Releases
  aktualisieren.
- **Konfigurations-Backup:** Die App exportiert die Konfiguration als passphrase-geschützt
  verschlüsseltes JSON (AES-256-GCM); bei größeren Updates kann ein Backup vor dem Upgrade
  sinnvoll sein — die App erzwingt das nicht.

## 6. Freigabe / Sideload

- APK auf einem Android-Gerät (7.0+; empfohlen: aktuelles S-Release) aus `releases/`
  installieren.
- Standard-Konfiguration nach Installation: eigenen Hermes-Agent-Endpunkt und API-Key in
  den App-Einstellungen hinterlegen (Werte liegen ausschließlich verschlüsselt im
  privaten App-Speicher).
- Der Transport läuft über HTTP (Klartext); für den produktiven Betrieb ist ein
  HTTPS-Reverse-Proxy vor dem Gateway empfohlen.

## English — Release Process

English version of the guide above.

1. **Version bump** — app version and `versionCode` are maintained centrally in
   `app/build.gradle.kts`. Updating over an existing install requires the same signing
   key; with an identical `versionCode` the new APK installs directly as an update
   (e.g. 1.0.x over 1.0.0 — same versionCode 1, same keystore `release.jks`).

2. **Prerequisites** — JDK 21, Android SDK, Gradle 8.x, `ANDROID_HOME` set.
   The local keystore `release.jks` and `keystore.properties` are **not in the repo**
   (gitignored). If `keystore.properties` is found, the build signs with it. Without it
   the project still builds, but the release is **unsigned** and therefore **cannot be
   installed** on a device.

3. **Build** — `./gradlew assembleDebug` (debug APK, signed with the debug keystore);
   `./gradlew assembleRelease` (release APK, signed if a keystore is present). The APK
   name is versioned automatically and assigned per variant: `app-debug_<version>.apk`
   / `app-release_<version>.apk`.

4. **APK storage** — the APK of the released version lives under `releases/` in the
   project folder (local, gitignored — the files are never committed). Naming and
   location stay the same for every release (e.g. `releases/app-release_1.0.0.apk`).
   The file doubles as the direct download/sideload path (run the APK on the device or
   install via file manager; allow “unknown sources” for sideloading).

5. **Creating a release** — create a new release with the release APK as **asset**
   (e.g. tag the new version in the repo, attach the APK). The repo fully contains:
   source code, README, CHANGELOG and LICENSE (Apache-2.0); the release APK from
   `releases/` is attached as asset. Keep the docs in sync: add a `CHANGELOG.md` entry
   following `## [Version] — date (versionCode N)` with categories (Added / Changed /
   Fixed / Technical); update the documentation (README / CHANGELOG / Release guide) for
   larger releases. **Configuration backup:** the app exports its configuration as
   passphrase-protected encrypted JSON (AES-256-GCM); a backup before bigger updates can
   be sensible — the app does not enforce it.

6. **Release / sideload** — install the APK from `releases/` on an Android device
   (7.0+; a current S release is recommended). Default configuration after install:
   enter your own Hermes agent endpoint and API key in the app settings (values are
   stored exclusively encrypted in the private app storage). Transport is plain HTTP;
   for production an HTTPS reverse proxy in front of the gateway is recommended.
