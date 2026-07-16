import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use(::load)
        }
    }

/**
 * Short-lived DEBUG bearer token, preferring the current shell over local.properties.
 *
 * The environment override lets a developer mint a token from an external service-account JSON
 * for one build without copying the JSON or token into the repository. The resulting value is
 * still injected only into DEBUG BuildConfig; release/default variants remain empty.
 */
val debugGcpSttAccessToken =
    System.getenv("GCP_STT_ACCESS_TOKEN")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: localProperties.getProperty("GCP_STT_ACCESS_TOKEN").orEmpty()
val debugGcpSttApiKey = localProperties.getProperty("GCP_STT_API_KEY").orEmpty()
val debugGcpProjectId = localProperties.getProperty("GCP_STT_PROJECT_ID").orEmpty()
/** DEBUG cloud-first TTS override; absent or malformed local values retain the default true. */
val debugCloudTtsEnabled =
    localProperties
        .getProperty("CLOUD_TTS_ENABLED")
        ?.trim()
        ?.toBooleanStrictOrNull()
        ?: true
// Keep the region developer-selectable because Chirp availability changes independently of code.
val debugGcpSttLocation =
    localProperties
        .getProperty("GCP_STT_LOCATION")
        .orEmpty()
        .trim()
        .ifEmpty { "asia-southeast1" }

/**
 * Escapes a local string as a Java source literal for a generated BuildConfig field.
 *
 * @receiver machine-local value read from `local.properties`.
 * @return a quoted Java string literal with backslashes and quotes escaped.
 *
 * This configuration-time helper performs no network or credential logging, has no coroutine or
 * cancellation behavior, and throws only normal JVM allocation failures. Escaping prevents a local
 * value from breaking generated source; the generated debug output remains gitignored.
 */
fun String.asBuildConfigStringLiteral(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.foxconn.seeandsay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.foxconn.seeandsay"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Release and unqualified variants are safe by default. Only debug overrides these values
        // from the gitignored local file below, so developer credentials cannot enter a release.
        buildConfigField("String", "GCP_STT_ACCESS_TOKEN", "\"\"")
        buildConfigField("String", "GCP_STT_API_KEY", "\"\"")
        buildConfigField("String", "GCP_STT_PROJECT_ID", "\"\"")
        buildConfigField("String", "GCP_STT_LOCATION", "\"\"")
        buildConfigField("boolean", "CLOUD_TTS_ENABLED", "true")
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "GCP_STT_ACCESS_TOKEN",
                debugGcpSttAccessToken.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "GCP_STT_API_KEY",
                debugGcpSttApiKey.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "GCP_STT_PROJECT_ID",
                debugGcpProjectId.asBuildConfigStringLiteral(),
            )
            buildConfigField(
                "String",
                "GCP_STT_LOCATION",
                debugGcpSttLocation.asBuildConfigStringLiteral(),
            )
            buildConfigField("boolean", "CLOUD_TTS_ENABLED", debugCloudTtsEnabled.toString())
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    val grpcBom = platform("io.grpc:grpc-bom:1.76.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(grpcBom)
    testImplementation(grpcBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.api.grpc:grpc-google-cloud-speech-v1:4.74.0")
    implementation("com.google.api.grpc:grpc-google-cloud-speech-v2:4.74.0")
    implementation("com.google.api.grpc:grpc-google-cloud-texttospeech-v1:2.92.0")
    implementation("com.google.protobuf:protobuf-java:3.25.8")
    implementation("io.grpc:grpc-okhttp")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.grpc:grpc-inprocess")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
