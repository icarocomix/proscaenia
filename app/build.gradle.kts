import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Leggo l'URL base della piattaforma da gradle.properties: in questo modo il codice non contiene
// domini cablati e posso puntare un ambiente diverso con -Pproscaenia.baseUrl=... senza toccarlo.
val proscaeniaBaseUrl: String = providers.gradleProperty("proscaenia.baseUrl")
    .getOrElse("https://proscaenia.com/backoffice/api/")
    .let { if (it.endsWith("/")) it else "$it/" }

/**
 * Versione letta da version.properties alla radice del progetto. Incremento il build number solo
 * quando la build produce davvero un pacchetto (assemble, bundle, install, build, package) e non
 * durante il sync dell'IDE o task come clean e test: così ogni APK ha un versionCode distinto e
 * crescente e si installa sempre sopra il precedente. Riscrivo solo la riga del build number per
 * preservare commenti e ordine del file. Se la compilazione fallisce il numero resta comunque
 * consumato: preferisco una numerazione con qualche salto a due APK diversi con lo stesso codice.
 */
val appVersion: AppVersion = run {
    val file = rootProject.file("version.properties")
    val props = Properties().apply { file.inputStream().use(::load) }
    val packagingTasks = listOf("assemble", "bundle", "install", "build", "package")
    val packaging = gradle.startParameter.taskNames.any { task ->
        val name = task.substringAfterLast(':')
        packagingTasks.any { name.startsWith(it) }
    }
    var build = props.getProperty("VERSION_BUILD", "0").toInt()
    if (packaging) {
        build += 1
        val updated = file.readLines().joinToString("\n", postfix = "\n") { line ->
            if (line.startsWith("VERSION_BUILD=")) "VERSION_BUILD=$build" else line
        }
        file.writeText(updated)
    }
    AppVersion(
        major = props.getProperty("VERSION_MAJOR", "1").toInt(),
        minor = props.getProperty("VERSION_MINOR", "0").toInt(),
        patch = props.getProperty("VERSION_PATCH", "0").toInt(),
        build = build,
    )
}

data class AppVersion(val major: Int, val minor: Int, val patch: Int, val build: Int) {
    val name: String get() = "$major.$minor.$patch.$build"
}

/**
 * Firma di produzione letta da variabili d'ambiente, mai da file versionati: percorso del keystore,
 * alias e relative chiavi d'accesso restano fuori dal repository. Se non sono tutte presenti ripiego
 * sulla chiave di debug locale, adatta all'installazione diretta ma non alla pubblicazione su store;
 * lo script build.sh segnala quale delle due è stata usata.
 */
val releaseSigningEnv: Map<String, String>? = run {
    val names = listOf("PROSCAENIA_KEYSTORE_FILE", "PROSCAENIA_KEYSTORE_PASSWORD", "PROSCAENIA_KEY_ALIAS", "PROSCAENIA_KEY_PASSWORD")
    val values = names.associateWith { providers.environmentVariable(it).orNull }
    if (values.values.all { !it.isNullOrBlank() }) values.mapValues { it.value!! } else null
}

android {
    namespace = "com.icarocomix.proscaenia"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.icarocomix.proscaenia"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.build
        versionName = appVersion.name
        buildConfigField("String", "PROSCAENIA_BASE_URL", "\"$proscaeniaBaseUrl\"")
    }

    signingConfigs {
        if (releaseSigningEnv != null) {
            create("release") {
                storeFile = file(releaseSigningEnv.getValue("PROSCAENIA_KEYSTORE_FILE"))
                storePassword = releaseSigningEnv.getValue("PROSCAENIA_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnv.getValue("PROSCAENIA_KEY_ALIAS")
                keyPassword = releaseSigningEnv.getValue("PROSCAENIA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Build di produzione: R8 rimuove codice inutilizzato e offusca i nomi, lo shrinker
            // elimina le risorse non referenziate (soprattutto le icone Material non usate), niente
            // codice di debug né log di Compose tooling.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Non incorporo il blocco cifrato con l'elenco delle dipendenze che AGP aggiunge per Google Play:
    // per la distribuzione diretta non serve, appesantisce il pacchetto e il task che lo produce
    // (collectReleaseDependencies) fallisce in modo riproducibile su questa combinazione Gradle/AGP
    // con l'errore "Problems reading data from Binary store".
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            // File di metadati delle librerie inutili a runtime: li escludo per alleggerire l'APK.
            excludes += setOf("META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Tengo i test JVM leggeri e in un solo processo per non saturare la macchina.
            it.maxHeapSize = "512m"
            it.maxParallelForks = 1
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.cast)
    implementation(libs.androidx.mediarouter)
    implementation(libs.play.services.cast.framework)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
}
