plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.magpie.filer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.magpie.filer"
        // All-files access (MANAGE_EXTERNAL_STORAGE) needs API 30.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true

            // CI is the only place these run, so a failure has to be legible
            // from the log alone. Without this Gradle prints the exception
            // class and a line number and nothing else — not the message,
            // which is usually the whole answer.
            all {
                it.testLogging {
                    events("failed")
                    exceptionFormat =
                        org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStackTraces = true
                    showCauses = true
                    showExceptions = true
                }
            }
        }
    }

    // The Anthropic SDK brings Jackson and OkHttp, whose licence and notice
    // files collide when several jars are merged into one APK.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/notice.txt",
                "META-INF/INDEX.LIST",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    // Reads a photo's taken-at time, which is what "that afternoon" really
    // means when grouping a backlog.
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.anthropic.java)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    // Robolectric runs the real Android framework on the JVM, which is the only
    // way to exercise SafDocumentStore against a genuine DocumentsProvider
    // without a device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Unit tests get a stubbed android.jar whose org.json throws on every call.
    // The real one on the test classpath lets Suggester's parsing be tested.
    testImplementation(libs.org.json)
    // A real HTTP server to point the Anthropic SDK at, so the request it puts
    // on the wire can be read back and checked. The JDK's own com.sun.net
    // .httpserver is not on the Android unit-test compile classpath.
    testImplementation(libs.mockwebserver)

    debugImplementation(libs.androidx.ui.tooling)
}
