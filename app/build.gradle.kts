plugins {
    id("com.android.application")
}

val telegramBotToken = System.getenv("TELEGRAM_BOT_TOKEN") ?: ""
val telegramAdminChatId = System.getenv("TELEGRAM_ADMIN_CHAT_ID") ?: ""
val androidKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.webtoonmap.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.webtoonmap.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 52
        versionName = "1.5.27"
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"$telegramBotToken\"")
        buildConfigField("String", "TELEGRAM_ADMIN_CHAT_ID", "\"$telegramAdminChatId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        androidKeystorePath?.let { keyPath ->
            create("stableDebug") {
                storeFile = file(keyPath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            androidKeystorePath?.let {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }

        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jsoup:jsoup:1.17.2")
}








