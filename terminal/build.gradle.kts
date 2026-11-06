plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.terminal"

    defaultConfig {
        // Match the app's shipped ABIs; keep both phone and emulator support.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        // 上游单测会碰到 android.util.Base64 等 Android 类, 沿用上游的设置让它们返回默认值
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
