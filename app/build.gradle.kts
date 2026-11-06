plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signRelease = providers.environmentVariable("SUPERSCANNER_SIGN_RELEASE").orNull == "1"
val uploadKeyEnvironment = mapOf(
    "SUPERSCANNER_UPLOAD_KEYSTORE" to providers.environmentVariable("SUPERSCANNER_UPLOAD_KEYSTORE").orNull,
    "SUPERSCANNER_UPLOAD_STORE_PASSWORD" to providers.environmentVariable("SUPERSCANNER_UPLOAD_STORE_PASSWORD").orNull,
    "SUPERSCANNER_UPLOAD_KEY_ALIAS" to providers.environmentVariable("SUPERSCANNER_UPLOAD_KEY_ALIAS").orNull,
    "SUPERSCANNER_UPLOAD_KEY_PASSWORD" to providers.environmentVariable("SUPERSCANNER_UPLOAD_KEY_PASSWORD").orNull,
)
if (signRelease) {
    val missing = uploadKeyEnvironment.filterValues { it.isNullOrBlank() }.keys
    check(missing.isEmpty()) {
        "Signed release requested, but required signing environment variables are missing: ${missing.joinToString()}."
    }
    check(file(requireNotNull(uploadKeyEnvironment["SUPERSCANNER_UPLOAD_KEYSTORE"])).isFile) {
        "Signed release requested, but the configured upload keystore file does not exist."
    }
}

android {
    namespace = "tools.supernote.scan"
    compileSdk = 36

    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.yukti.superscanner"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (signRelease) {
        signingConfigs {
            create("upload") {
                storeFile = file(requireNotNull(uploadKeyEnvironment["SUPERSCANNER_UPLOAD_KEYSTORE"]))
                storePassword = requireNotNull(uploadKeyEnvironment["SUPERSCANNER_UPLOAD_STORE_PASSWORD"])
                keyAlias = requireNotNull(uploadKeyEnvironment["SUPERSCANNER_UPLOAD_KEY_ALIAS"])
                keyPassword = requireNotNull(uploadKeyEnvironment["SUPERSCANNER_UPLOAD_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (signRelease) signingConfigs.getByName("upload") else null
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { animationsDisabled = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":clipboardbridge"))
    implementation(project(":nativelink"))
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
