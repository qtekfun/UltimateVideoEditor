import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version: one source of truth, gradle/version.properties. versionCode = major*10000 + minor*100 + patch
// (so 0.1.0 -> 100, 1.2.3 -> 10203); a local or CI override is allowed with -Puveditor.versionCode=<n>.
val appVersionName: String = Properties().apply {
    rootProject.file("gradle/version.properties").inputStream().use { load(it) }
}.getProperty("versionName").trim()
val appVersionCode: Int = providers.gradleProperty("uveditor.versionCode").orNull?.toInt() ?: run {
    val parts = appVersionName.substringBefore('-').split('.').map { it.toInt() }
    require(parts.size == 3 && parts[1] in 0..99 && parts[2] in 0..99) {
        "versionName must be MAJOR.MINOR.PATCH with minor and patch below 100"
    }
    parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

// Release signing is optional and never committed: values come from `keystore.properties` at the repository
// root (ignored by version control) or from UVEDITOR_* environment variables. Without them the release is unsigned.
val signingProps: Map<String, String> = run {
    val file = rootProject.file("keystore.properties")
    val fromFile = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    fun value(key: String, env: String): String? = (fromFile.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
    listOfNotNull(
        value("storeFile", "UVEDITOR_KEYSTORE_FILE")?.let { "storeFile" to it },
        value("storePassword", "UVEDITOR_KEYSTORE_PASSWORD")?.let { "storePassword" to it },
        value("keyAlias", "UVEDITOR_KEY_ALIAS")?.let { "keyAlias" to it },
        value("keyPassword", "UVEDITOR_KEY_PASSWORD")?.let { "keyPassword" to it },
    ).toMap()
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { it in signingProps }

// The About screen shows these documents; they are copied into the APK's assets at build time so the app
// carries exactly what the repository says (no network, no second copy to keep in sync).
abstract class CopyLegalAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val notices: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacy: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val licence: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("legal").apply { deleteRecursively(); mkdirs() }
        notices.get().asFile.copyTo(dir.resolve("THIRD_PARTY_NOTICES.md"))
        privacy.get().asFile.copyTo(dir.resolve("PRIVACY.md"))
        licence.get().asFile.copyTo(dir.resolve("LICENSE.txt"))
    }
}

val copyLegalAssets = tasks.register<CopyLegalAssets>("copyLegalAssets") {
    notices.set(rootProject.file("THIRD_PARTY_NOTICES.md"))
    privacy.set(rootProject.file("docs/PRIVACY.md"))
    licence.set(rootProject.file("LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLegalAssets, CopyLegalAssets::outputDir)
    }
}

android {
    namespace = "com.ultimatevideo.uveditor"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.ultimatevideo.uveditor"
        // Debug-only helper for shared test devices: -PappIdSuffix=.mine installs next to the normal app.
        (findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingProps.getValue("storeFile"))
                storePassword = signingProps.getValue("storePassword")
                keyAlias = signingProps.getValue("keyAlias")
                keyPassword = signingProps.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // `-Puveditor.appIdSuffix=name` installs this build next to others on the same device (separate
            // data), so several people or agents can test at once without replacing each other's app.
            providers.gradleProperty("uveditor.appIdSuffix").orNull?.let { applicationIdSuffix = ".$it" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        prefab = true
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.oboe)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
