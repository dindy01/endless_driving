import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent renderer: talks to OpenGL ES 2.0 only through the small Gl interface,
// so the same code runs on Android (GLES20) and on the desktop preview harness (LWJGL).
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
    }
}

dependencies {
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging { events("passed", "failed", "skipped") }
}
