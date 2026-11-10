import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for ad unit IDs (see app/ads.properties).
val adsProps = Properties().apply { file("ads.properties").inputStream().use { load(it) } }
fun adProp(key: String): String = requireNotNull(adsProps.getProperty(key)) { "missing $key in ads.properties" }

android {
    namespace = "com.endlessdrive.game"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.endlessdrive.game"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["admobAppId"] = adProp("admob.appId")
        buildConfigField("String", "AD_INTERSTITIAL_ID", "\"${adProp("admob.interstitialId")}\"")
        buildConfigField("String", "AD_REWARDED_ID", "\"${adProp("admob.rewardedId")}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${adProp("privacy.policyUrl")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned by default. Sign with your own key (see README) for distribution.
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
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
                it.systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
                it.testLogging {
                    events("passed", "failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStandardStreams = true
                }
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Ad SDK pulls in newer versions than we pin; not actionable offline.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
}
