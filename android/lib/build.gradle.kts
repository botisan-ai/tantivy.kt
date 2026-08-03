plugins {
    id("com.android.library")
    id("maven-publish")
}

val rustDir = layout.projectDirectory.dir("../../rust")
val jniLibsDir = rustDir.dir("target/jniLibs")
val generatedKotlinDir = rustDir.dir("target/uniffi-kotlin")
val hostLibName =
    if (System.getProperty("os.name").startsWith("Mac")) "libtantivy.dylib" else "libtantivy.so"
val androidSdkDir =
    System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"

// Host build feeds uniffi-bindgen (the Android .so is stripped, which removes the
// ELF .symtab uniffi reads; the host dylib keeps its symbols) and the JVM unit tests.
val cargoBuildHost = tasks.register<Exec>("cargoBuildHost") {
    workingDir(rustDir)
    commandLine("cargo", "build", "--release", "-p", "tantivy-kt")
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    workingDir(rustDir)
    environment("ANDROID_HOME", androidSdkDir)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "x86_64",
        "--platform", "24",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release", "-p", "tantivy-kt",
    )
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    dependsOn(cargoBuildHost)
    workingDir(rustDir)
    commandLine(
        "cargo", "run", "--release", "-p", "uniffi-bindgen", "--",
        "generate", "--library", "target/release/$hostLibName",
        "--language", "kotlin",
        "--out-dir", generatedKotlinDir.asFile.absolutePath,
        "--no-format",
    )
}

android {
    namespace = "ai.botisan.tantivy"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    publishing {
        singleVariant("release")
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir(generatedKotlinDir)
            jniLibs.srcDir(jniLibsDir)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
    .configureEach { dependsOn(generateUniffiBindings) }
tasks.matching { it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(cargoNdkBuild) }
tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    systemProperty("jna.library.path", rustDir.dir("target/release").asFile.absolutePath)
}

dependencies {
    api("net.java.dev.jna:jna:5.19.1@aar")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.java.dev.jna:jna:5.19.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ai.botisan"
            artifactId = "tantivy-android"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name = "tantivy-android"
                description = "Tantivy full-text search with UniFFI Kotlin bindings for Android"
                url = "https://github.com/botisan-ai/tantivy.kt"
                licenses { license { name = "MIT License" } }
            }
        }
    }
    repositories {
        maven {
            name = "buildDir"
            url = uri(rootProject.layout.buildDirectory.dir("maven-repo"))
        }
    }
}
