plugins {
    `maven-publish`
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.feedpulse.android.compose"
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
version = "0.2.0"

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/lv-kit/feedpulse-android")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = "feedpulse-compose"
                pom {
                    name.set("feedpulse-compose")
                    description.set("Feedpulse Android SDK: Jetpack Compose 向け Modifier.feedpulseId")
                    url.set("https://github.com/lv-kit/feedpulse-android")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}

dependencies {
    api(project(":feedpulse-picker"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ui-test-manifest は debug のみ。release の単体テストは回さない。
androidComponents {
    beforeVariants { it.enableUnitTest = it.buildType == "debug" }
}
