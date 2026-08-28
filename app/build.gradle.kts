plugins {
    id("com.android.application")
}

android {
    namespace = "com.csy.cmbspend"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.csy.cmbspend"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/release.jks")
            storePassword = "csy123456"
            keyAlias = "cmb"
            keyPassword = "csy123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 仅用于 POST_NOTIFICATIONS 运行时权限兼容（ActivityCompat/ContextCompat）
    implementation("androidx.core:core:1.13.1")
}
