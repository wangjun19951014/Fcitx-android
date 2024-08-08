plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
    id("org.fcitx.fcitx5.android.native-lib-convention")
    id("org.fcitx.fcitx5.android.fcitx-headers")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.libime"

    defaultConfig {
        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // dummy "cmake" target
                    "cmake",
                    // libime
                    "IMECore",
                    "IMEPinyin",
                    "IMETable"
                )
            }
        }
    }
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "25.0.8775105"

    prefab {
        create("cmake") {
            headerOnly = true
            headers = "src/main/cpp/cmake"
        }
        val libimeHeadersPrefix = "build/headers/usr/include/LibIME"
        create("IMECore") {
            libraryName = "libIMECore"
            headers = libimeHeadersPrefix
        }
        create("IMEPinyin") {
            libraryName = "libIMEPinyin"
            headers = libimeHeadersPrefix
        }
        create("IMETable") {
            libraryName = "libIMETable"
            headers = libimeHeadersPrefix
        }
    }
}

dependencies {
    implementation(project(":lib:fcitx5"))
}
