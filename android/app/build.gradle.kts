import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val ksProps = Properties().apply { val f = rootProject.file("keystore/keystore.properties"); if (f.exists()) f.inputStream().use { load(it) } }
android {
    namespace = "na.gideon.vehiclelog"
    compileSdk = 35
    defaultConfig {
        applicationId = "na.gideon.vehiclelog"
        minSdk = 26; targetSdk = 35
        versionCode = 1; versionName = "1.0"
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/" + (ksProps.getProperty("storeFile") ?: "release.jks"))
            storePassword = ksProps.getProperty("storePassword"); keyAlias = ksProps.getProperty("keyAlias"); keyPassword = ksProps.getProperty("keyPassword")
        }
    }
    buildTypes { getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false; htmlReport = true; xmlReport = false }
}
