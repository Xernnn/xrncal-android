import java.util.Properties

plugins {
    // Kotlin is built into AGP 9: no kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The xrn release key (xrn-release.jks beside the repos), chosen once for this app and never to
// change: a different signer means uninstalling, which wipes the phone's calendars and its sealed
// login. Read from ../xrn-signing.properties, which resolves only from the main checkout; elsewhere
// (a worktree, CI) the debug build takes the machine's debug key and the release build is unsigned.
val xrnSigning: Properties? = rootProject.file("../xrn-signing.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "com.xern.xrncal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xern.xrncal"
        // Android 12: exact alarms under the rules this app follows (USE_EXACT_ALARM from 13,
        // SCHEDULE_EXACT_ALARM on 12). The phone this is for runs 15.
        minSdk = 31
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0"
    }

    signingConfigs {
        if (xrnSigning != null) {
            create("xrn") {
                storeFile = rootProject.file("..").resolve(xrnSigning.getProperty("storeFile"))
                storePassword = xrnSigning.getProperty("storePassword")
                keyAlias = xrnSigning.getProperty("keyAlias")
                keyPassword = xrnSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("xrn")?.let { signingConfig = it }
        }
        // Not shrunk yet: R8 hasn't been checked against a round trip of stored calendars.
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("xrn")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric draws the widgets with the app's own layouts and drawables.
            isIncludeAndroidResources = true
            // AgendaWidgetTest's pictures: -Pshots=<dir>, or build/shots.
            val shots = (findProperty("shots") ?: layout.buildDirectory.dir("shots").get().asFile.path).toString()
            all { it.systemProperty("xrncal.shots", shots) }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric's Android jar, resolved by Gradle (so CI's Gradle cache keeps it) and handed to
// Robolectric offline: its own download from Maven Central failed CI runs now and then.
val robolectricJars: Configuration = configurations.create("robolectricJars")
val robolectricDir = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars = tasks.register<Copy>("copyRobolectricJars") {
    from(robolectricJars)
    into(robolectricDir)
}

// The sync tests start a real Radicale (XRNCAL_RADICALE, or `radicale` on the PATH); without one
// they are skipped.
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.path)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work)
    implementation(libs.kotlinx.coroutines.android)
    // WebDAV needs PROPFIND and REPORT, which HttpURLConnection refuses to send (on Android and the JDK).
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // The widgets drawn off a phone (AgendaWidgetTest): RemoteViews applied by the real framework.
    testImplementation(libs.robolectric)
    robolectricJars(libs.robolectric.android.all) { isTransitive = false }
    testImplementation(libs.kotlinx.coroutines.core)
}
