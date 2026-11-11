import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ad unit ids and the privacy-policy link come from app/ads.properties and nowhere else.
val ads = Properties().apply { file("ads.properties").inputStream().use { load(it) } }
fun ad(key: String): String = ads.getProperty(key) ?: error("ads.properties is missing $key")

android {
    namespace = "com.endlessdrive.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.endlessdrive.game"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["admobAppId"] = ad("admob.appId")
        buildConfigField("String", "AD_INTERSTITIAL", "\"${ad("admob.interstitialId")}\"")
        buildConfigField("String", "AD_REWARDED", "\"${ad("admob.rewardedId")}\"")
        buildConfigField("String", "PRIVACY_URL", "\"${ad("privacy.policyUrl")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Left unsigned on purpose; see README for signing.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
                it.systemProperty("shotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
                it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
}
