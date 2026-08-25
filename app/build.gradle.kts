import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mightykatun.speedometer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mightykatun.speedometer.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "1.4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // --- SIGNING CONFIGURATION START ---
    // Reads keystore.properties from the root project folder
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        create("release") {
            // Only try to load if properties exist to avoid build errors during dev
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }
    // --- SIGNING CONFIGURATION END ---

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Apply the signing config defined above
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        compose = true
    }
    
    composeOptions {
        // This version must match your Kotlin version. 
        // If you get a "Compose Compiler Compatibility" error, check the compatibility map.
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    
    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("stageReleaseSbomInputs") {
    dependsOn("assembleRelease")
    val outputDirectory = layout.buildDirectory.dir("sbom-inputs")
    outputs.dir(outputDirectory)
    doLast {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration
            .resolvedArtifacts
            .forEach { artifact ->
                val id = artifact.moduleVersion.id
                val artifactDirectory = output.resolve("${id.group}/${id.name}/${id.version}")
                artifactDirectory.mkdirs()
                copy {
                    from(artifact.file)
                    into(output.resolve("resolved-artifacts/${id.group}/${id.name}/${id.version}"))
                }
                artifactDirectory.resolve("pom.xml").writeText(
                    """<?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>${id.group}</groupId>
                      <artifactId>${id.name}</artifactId>
                      <version>${id.version}</version>
                    </project>
                    """.trimIndent()
                )
            }
        copy {
            from(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
            into(output.resolve("application"))
        }
    }
}
