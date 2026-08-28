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
            // 密码从 gradle property 读取，不进仓库、不写死：
            //   本地：放 ~/.gradle/gradle.properties
            //   云端：GitHub Secret 通过 ORG_GRADLE_PROJECT_* 环境变量注入
            storePassword = (project.findProperty("CMB_STORE_PASSWORD") as String?) ?: ""
            keyAlias = "cmb"
            keyPassword = (project.findProperty("CMB_KEY_PASSWORD") as String?) ?: ""
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
