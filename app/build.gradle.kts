import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Zentral gepflegte Versionsnummer. Die APK-Dateinamen werden daraus
// automatisch gebildet (siehe androidComponents unten): app-debug_<version>.apk
// bzw. app-release_<version>.apk.
// Beim nächsten Release hier hochzählen (z. B. "1.1") - Header und Dateiname
// bleiben dann immer synchron.
val appVersionName = "1.0.3"

// Release-Signing: Zugangsdaten liegen in keystore.properties (NICHT ins Repo
// einchecken - wird von .gitignore ausgeschlossen). Fehlt die Datei, baut das
// Projekt weiterhin, der Release ist dann aber unsigniert (nicht installierbar).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "de.hermi.voice"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.hermi.voice"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

// APK-Dateinamen automatisch versionieren und varianten-abhängig benennen:
// app-debug_<versionName>.apk bzw. app-release_<versionName>.apk.
// Macht die Zusage im Kopf-Kommentar wahr und verhindert, dass eine neue
// Version ohne passenden Dateinamen übersehen wird.
android {
    applicationVariants.all {
        val variantName = buildType.name
        outputs.all {
            (this as com.android.build.gradle.internal.api.ApkVariantOutputImpl)
                .outputFileName = "app-${variantName}_${appVersionName}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // sherpa-onnx offline STT
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")
    // Modell-Entpackung (tar.bz2)
    implementation("org.apache.commons:commons-compress:1.27.1")
    // JSON (Telegram-API)
    implementation("org.json:json:20240303")
    // Markdown-Rendering für den Update-Changelog (TextView-basiert, kein Compose)
    implementation("io.noties.markwon:core:4.6.2")

    // Tests
    // JVM-Unit-Tests für den Markdown-Formatter — brauchen android.text-Klassen,
    // deshalb Robolectric (Spans werden in einer JVM-Sandbox ausgeführt).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
}
