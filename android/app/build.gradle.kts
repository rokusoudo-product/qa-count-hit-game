import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// リリース署名鍵の受け口（Issue #46）。
// 鍵本体・パスワードはリポジトリにコミットしない前提で、次の優先順位で読み込む。
//   1. android/keystore.properties（.gitignore 対象。書式は keystore.properties.example 参照）
//   2. 環境変数 ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD
//      （将来 CI/CD から署名する場合の拡張用。現時点では未使用）
// どちらにも鍵情報が見つからない場合は release ビルドの signingConfig を設定せず、
// 未署名のままビルドを続行する（鍵を持たない CI・開発環境でも bundleRelease が失敗しないようにするため）。
data class ReleaseSigningInfo(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun loadReleaseSigningInfo(): ReleaseSigningInfo? {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val props = Properties()
        propsFile.inputStream().use { props.load(it) }
        val storeFilePath = props.getProperty("storeFile")
        val storePassword = props.getProperty("storePassword")
        val keyAlias = props.getProperty("keyAlias")
        val keyPassword = props.getProperty("keyPassword")
        if (storeFilePath != null && storePassword != null && keyAlias != null && keyPassword != null) {
            val storeFile = rootProject.file(storeFilePath)
            if (storeFile.exists()) {
                return ReleaseSigningInfo(storeFile, storePassword, keyAlias, keyPassword)
            }
        }
    }

    val envStoreFilePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val envStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val envKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val envKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    if (envStoreFilePath != null && envStorePassword != null && envKeyAlias != null && envKeyPassword != null) {
        val storeFile = file(envStoreFilePath)
        if (storeFile.exists()) {
            return ReleaseSigningInfo(storeFile, envStorePassword, envKeyAlias, envKeyPassword)
        }
    }

    return null
}

val releaseSigningInfo = loadReleaseSigningInfo()

android {
    namespace = "com.rokusoudo.hitokazu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rokusoudo.hitokazu"
        minSdk = 26
        targetSdk = 35
        // versionCode: リリースごとに +1 する（Play は同一 versionCode の再アップロードを拒否する）
        // versionName: セマンティックバージョニング（例: 1.0.0）。詳細は README「リリースビルド」参照
        versionCode = 1
        versionName = "1.0"
    }

    if (releaseSigningInfo != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseSigningInfo.storeFile
                storePassword = releaseSigningInfo.storePassword
                keyAlias = releaseSigningInfo.keyAlias
                keyPassword = releaseSigningInfo.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 鍵情報が見つからない場合は signingConfig を設定しない（未署名のままビルドを続行する）。
            if (releaseSigningInfo != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.ui.tooling)

    // JVM上で動くユニットテスト（Firestoreに依存しない純粋関数のみ対象。Issue #29）
    testImplementation(libs.junit)
}
