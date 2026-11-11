import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop preview harness (not part of the APK): renders the real engine with LWJGL so frames can
// be inspected headlessly, e.g.  xvfb-run -a ./gradlew :desktop:shots
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val lwjgl = "3.3.4"
dependencies {
    implementation(project(":engine"))
    implementation("org.lwjgl:lwjgl:$lwjgl")
    implementation("org.lwjgl:lwjgl-glfw:$lwjgl")
    implementation("org.lwjgl:lwjgl-opengl:$lwjgl")
    runtimeOnly("org.lwjgl:lwjgl:$lwjgl:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjgl:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjgl:natives-linux")
}

tasks.register<JavaExec>("shots") {
    group = "verification"
    description = "Renders gameplay frames to desktop/build/shots (needs an X display, e.g. xvfb-run)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.endlessdrive.desktop.ShotsKt")
    args(layout.buildDirectory.dir("shots").get().asFile.absolutePath)
}
