plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.rokusoudo.hitokazu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rokusoudo.hitokazu"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // GameLogicTest が shared/logic_vectors.json を読むために使う（Issue #45）。
    testImplementation(libs.org.json)
}

// shared/logic_vectors.json（採点・累計スコア・ラウンドIDの期待値の単一正本。
// Kotlin/JavaScript/Python共通。Issue #45）を、JVMユニットテストが classpath リソースとして
// 読めるよう src/test/resources へコピーする。
//
// テストコードから `../../shared/logic_vectors.json` のような相対パスで直接読む案もあったが、
// Gradle のテスト実行時カレントディレクトリ（working dir）はAGPのバージョンや実行環境によって
// `app/` だったり別の場所だったりし得るため、CIで確実に動かすにはclasspathリソース経由が
// 安全と判断した（このコピー方式を採用した理由。Issue #45 の未解決の質問への回答）。
val copyLogicVectors by tasks.registering(Copy::class) {
    description = "shared/logic_vectors.json をテストリソースへコピーする（採点ロジックの期待値の単一正本）"
    from(rootDir.resolve("../shared/logic_vectors.json"))
    into(layout.projectDirectory.dir("src/test/resources"))
}

// src/test/resources を実際に読み取るのは test*UnitTest 本体ではなく、それより前段の
// process*UnitTestJavaRes（テストリソースをテスト用classpathへ束ねるタスク）。
// そちらに明示的に依存させないと、Gradle のタスク検証で「暗黙の依存関係」エラーになる。
tasks.matching { it.name.matches(Regex("process.*UnitTestJavaRes")) }.configureEach {
    dependsOn(copyLogicVectors)
}
