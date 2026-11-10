import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin 3D renderer. Talks to OpenGL ES 3.0 only through the `GL` interface, so it runs on
// Android (GLES30) and headlessly on the desktop JVM in tests (LWJGL + Mesa EGL, software GL).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

val lwjgl = "3.3.4"
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.lwjgl:lwjgl:$lwjgl")
    testImplementation("org.lwjgl:lwjgl-egl:$lwjgl")
    testImplementation("org.lwjgl:lwjgl-opengles:$lwjgl")
    testRuntimeOnly("org.lwjgl:lwjgl:$lwjgl:natives-linux")
    testRuntimeOnly("org.lwjgl:lwjgl-opengles:$lwjgl:natives-linux")
}

tasks.test {
    maxHeapSize = "2g"
    environment("EGL_PLATFORM", "surfaceless")
    systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
