plugins {
    id("maafw.android.application")
    id("maafw.android.compose")
    id("maafw.pi.assets")
    id("maafw.agent.runtime")
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "com.aliothmoon.maafw"
    signingConfigs.getByName("debug") {
        storeFile = file(providers.gradleProperty("diagnosticKeystore").get())
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    buildTypes.getByName("debug") { isDebuggable = false }
    defaultConfig {
        versionCode = 54
        versionName = "0.54-native-own-state"
        manifestPlaceholders["appLabel"] = "Rewards只读"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    flavorDimensions += "reviewBoundary"
    productFlavors {
        create("captureReview") {
            dimension = "reviewBoundary"
            applicationIdSuffix = ".capturereview"
        }
    }
    externalNativeBuild { cmake { path = file("../app/src/main/native/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += "zip" }
    // Direct source sharing. This is the full actual product and dormant capture closure.
    sourceSets.getByName("main") {
        java.setSrcDirs(listOf(
            "../app/src/main/java/com/aliothmoon/maafw/once",
            "../app/src/main/java/com/aliothmoon/maafw/readonly",
            "../app/src/main/java/com/aliothmoon/maafw/rewards",
            "../app/src/main/java/com/aliothmoon/maafw/theme/Theme.kt",
            "../app/src/main/java/com/aliothmoon/maafw/theme/DesignTokens.kt",
            "../app/src/main/java/com/aliothmoon/maafw/theme/Type.kt",
            "../app/src/main/java/com/aliothmoon/maafw/ui/components/MaaButtons.kt",
            "../app/src/main/java/com/aliothmoon/maafw/ui/components/MaaPressIndication.kt",
            "../app/src/main/java/io/github/klwb360/rewards/core"
        ))
        kotlin.setSrcDirs(java.directories)
        res.srcDir("../app/src/main/res")
        assets.srcDir("../app/src/main/assets")
        jniLibs.srcDir("../app/src/main/jniLibs")
    }
    sourceSets.getByName("test") {
        java.srcDirs("../app/src/test/java/com/aliothmoon/maafw/once", "../app/src/test/java/com/aliothmoon/maafw/readonly")
        kotlin.setSrcDirs(java.directories)
    }
}
// The base task must never implicitly package the separate review identity.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project } && gradle.startParameter.taskNames.any { it == "assembleDebug" }) {
        throw GradleException("Use :app:assembleDebug or :capture-review:assembleCaptureReviewDebug explicitly")
    }
}
dependencies {
    compileOnly(project(":hidden-api"))

    implementation(project(":annotation-api"))
    implementation(project(":semi-icons"))
    // Baseline Profile 的生产者；采集用例在那个模块里

    // MIUI 上系统权限页的跳转差异大，自己拼 Intent 覆盖不全
    implementation(libs.xx.permissions)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)
    // aar 里带 libjnidispatch.so，用 jar 会在设备上找不到 native 分发库
    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)
    // 自定义背景选图时按 EXIF 方向摆正
    implementation(libs.androidx.exifinterface)
    // Legacy Xiaomi notification graph is not part of this product module.
    // 解包与项目加载各圈一段，Perfetto / macrobenchmark 里才归得了因
    implementation(libs.androidx.tracing.ktx)
    // Baseline Profile 在 API 33 以下靠它在启动时装入
    implementation(libs.androidx.profileinstaller)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.timber)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.strikethrough)
    implementation(libs.okhttp)
    implementation(libs.semver)
    implementation(libs.sentry.android.core)
    // Android 没有自带的 mail 实现，三个一起才跑得起 Transport.send
    implementation(libs.angus.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)
    // 前台模式控制层：拖拽/吸边/多屏/返回键拦截都在库里，自己写这几样是纯坑区
    implementation(libs.floatingx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // Perfetto 里把 slice 命名到 composable；只进 debug，release 不带
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.tracing.perfetto.binary)
}





