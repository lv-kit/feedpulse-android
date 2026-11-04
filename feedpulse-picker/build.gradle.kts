plugins {
    `maven-publish`
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.feedpulse.android.picker"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin { jvmToolchain(17) }

group = "com.feedpulse"
version = "0.1.0"

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = "feedpulse-picker"
                pom {
                    name.set("feedpulse-picker")
                    description.set("Feedpulse Android SDK: 該当箇所ピッカー")
                    url.set("https://github.com/lv-kit/feedpulse-android")
                }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}
