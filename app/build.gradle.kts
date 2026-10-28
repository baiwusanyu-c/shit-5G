import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val ksProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasKeystore = keystorePropsFile.exists()

android {
    namespace = "com.baiwusanyu.shit5g"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.baiwusanyu.shit5g"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = rootProject.file(ksProps.getProperty("storeFile", "shit5g.keystore"))
                storePassword = ksProps.getProperty("storePassword", "")
                keyAlias = ksProps.getProperty("keyAlias", "shit5g")
                keyPassword = ksProps.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 无 keystore.properties 时退回 debug 签名，保证 CI 仍可产出可安装包
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        aidl = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Shizuku client API（UserService 通道 + shell 命令执行）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
}
