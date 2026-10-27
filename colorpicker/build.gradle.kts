plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.flask.colorpicker"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.androidx.appcompat.colorpicker)
    api(libs.androidx.preference)
}
