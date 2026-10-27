plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.openintents.openpgp"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    // Do not abort build if lint finds errors
    lint {
        abortOnError = false
    }

    buildTypes {
        getByName("release")
        getByName("debug")
    }

    buildFeatures {
        aidl = true
    }
}
