import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: path given by MINILAN_KEYSTORE_PROPS (or D:/android-tools/keys/keystore.properties); never committed.
val keyProps = Properties().apply {
    val f = file(System.getenv("MINILAN_KEYSTORE_PROPS") ?: "D:/android-tools/keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.minilan"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.minilan"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.1"
    }
    signingConfigs {
        if (keyProps.containsKey("storeFile")) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    // The UI is shared with the desktop client: client/web/index.html
    sourceSets["main"].assets.srcDir("../../client/web")
    sourceSets["main"].assets.srcDir("../../shared") // games.json for the phone-hosted server
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
