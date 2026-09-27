plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.eee312.ppgmonitor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.eee312.ppgmonitor"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("androidTest") {
            // MigrationTestHelper reads the exported schema JSON files to
            // build a database at an OLD version before migrating it.
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

room {
    // Room schema export (Rule Zero: non-destructive migrations need a
    // captured prior-version schema to test against) - version 1's JSON
    // was captured here BEFORE any Phase 3 entity change, from the exact
    // schema already running on the phone.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.usbSerial)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    // Real org.json (not the Android SDK's test-stub, which throws on
    // every call) - needed so a JVM unit test can actually exercise
    // ReadingPostProcessor.qualityReportToJson() and catch bugs like the
    // NaN-in-JSONObject.put() crash this project hit on a real device.
    testImplementation(libs.orgJson)
    // Test-only: a real XmlPullParser for the .xlsx reader's JVM tests (the
    // android.jar stub throws). On the phone the platform parser is used.
    testImplementation(libs.kxml2)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}