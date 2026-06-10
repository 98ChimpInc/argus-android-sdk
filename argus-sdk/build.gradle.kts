plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "cloud.projectargus"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // JSON
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // HTTP (cold-start / fallback resolveFlags fetch)
    implementation(libs.okhttp)

    // Firebase — real-time push primary channel (#215).
    // BoM pins compatible Auth + Firestore versions; consumers use Argus's
    // Firebase project via a scoped custom token, never their own project.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // Bridges Firebase Task → coroutine .await() (signInWithCustomToken).
    implementation(libs.kotlinx.coroutines.play.services)

    // Logging
    implementation(libs.timber)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json on the JVM test classpath. The android.jar stub throws
    // "not mocked" for every org.json call; this shadows it so JSON-parsing
    // getters + the resolver's value stringification are actually exercised.
    testImplementation(libs.json)
}
