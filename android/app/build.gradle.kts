import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 서버 주소·토큰은 저장소에 넣지 않는다. android/local.properties(gitignore) 또는 환경변수에서 읽어 BuildConfig 에 넣는다.
//   ai.relay.url=https://pleospet-ai-relay.vercel.app      (환경변수 AI_RELAY_URL)
//   ai.relay.token=<APP_TOKEN>                             (환경변수 AI_RELAY_TOKEN)
//   content.base.url=https://<ref>.supabase.co/storage/v1/object/public/pleospet-content   (환경변수 CONTENT_BASE_URL)
// 비어 있으면 AI 는 규칙 문장만 쓰고, 원격 콘텐츠는 확인하지 않는다(내장 콘텐츠).
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secretString(key: String): String {
    val v = localProperties.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')) ?: ""
    return "\"" + v.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "ai.safetypoc.app"
    compileSdk = 35   // AGP 8.8 지원 상한. android-37.0 폴더 이름을 AGP 8.8 이 못 읽음 (11/6)

    defaultConfig {
        applicationId = "ai.safetypoc.app"
        minSdk = 28          // Pleos Connect 요구: API 28 이상
        targetSdk = 35
        // 공모전 규칙: APK 는 저장소 apk/ 폴더에 올린다(GitHub 파일 한도 100MB). 에뮬레이터가 쓰는 두 ABI 만 넣어 114MB → 100MB 아래로
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = 2
        versionName = "1.0"

        buildConfigField("String", "AI_RELAY_URL", secretString("ai.relay.url"))
        buildConfigField("String", "AI_RELAY_TOKEN", secretString("ai.relay.token"))
        buildConfigField("String", "CONTENT_BASE_URL", secretString("content.base.url"))
        // 시연 모드: 캐릭터를 이 단계로 시작한다(산타 모자를 쓰고). 변신은 홈에서 캐릭터를 누르면 본다. 1 이면 꺼짐
        //   demo.start.stage=3   (local.properties, 또는 환경변수 DEMO_START_STAGE)
        // Integer 로 둔다: int 상수면 Kotlin 이 값을 코드에 박아 넣어, 값만 바꾸고 빌드하면 예전 값이 남는다
        buildConfigField("Integer", "DEMO_START_STAGE", (localProperties.getProperty("demo.start.stage") ?: System.getenv("DEMO_START_STAGE") ?: "1").trim())
    }

    // AAOS CarPropertyManager (속도·기어·시동). 실행 시에는 차량 OS 에 들어 있다.
    useLibrary("android.car")

    signingConfigs {
        create("release") {
            val path = System.getenv("RELEASE_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 심사용 APK (11/15 에뮬레이터 확인): Pleos 에뮬레이터는 adb 로 설치한 앱이 debuggable 일 때만
            // distractionOptimized 를 인정한다. 끄면 기어 D 에서 시스템이 앱 전체를 "주행 중에는 사용할 수 없습니다" 로 가린다.
            // 켜면 BuildConfig.DEBUG 도 true 라 AI 최소 주행 0분·주행 종료 대기 짧게(AppConfig). 디버그 탭은 코인 바 길게 누르기로만 열린다.
            isDebuggable = true
            signingConfig = if (System.getenv("RELEASE_KEYSTORE_PATH") != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 아이템 그림 (06 "폴더 구조"의 "앱에 들어가는 방식", 스펙_개발스택 3절 리소스 위치).
// 원본은 기획/디자인/Image/Item/ 한 곳이다. 빌드 때 그 아래 PNG만 생성 폴더의 item/ 으로 옮기고, 그 폴더를 assets 소스로 더한다.
// SVG와 Item/ 밖의 그림은 APK 에 들어가지 않는다. 다른 폴더로 확인하려면 -PitemArtDir=<폴더> (android/ 기준 상대 경로 또는 절대 경로).
val itemArtSourceDir = (findProperty("itemArtDir") as String?) ?: "../기획/디자인/Image/Item"
/** assets 안 아이템 그림 폴더. core.item.ItemImage.ASSET_DIR 과 같아야 한다 */
val itemArtAssetDir = "item"

abstract class SyncItemArt : DefaultTask() {
    @get:Internal abstract val sourceDir: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    val pngs: FileTree get() = sourceDir.asFileTree.matching { include("**/*.png") }
    @get:Input abstract val assetDir: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction fun sync() {
        fs.sync {
            from(pngs) { into(assetDir.get()) }
            into(outputDir)
            includeEmptyDirs = false
        }
    }
}

val syncItemArt = tasks.register<SyncItemArt>("syncItemArt") {
    description = "기획/디자인/Image/Item/ 의 PNG를 assets/item/ 으로 넣는다"
    sourceDir.set(rootProject.file(itemArtSourceDir))
    assetDir.set(itemArtAssetDir)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncItemArt, SyncItemArt::outputDir)
    }
}

// DesignSystemSyncTest가 읽는 디자인 시스템 문서, ItemArtFilesTest가 읽는 아이템 그림 폴더. 이것만 바뀌어도 단위 테스트를 다시 돌린다
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("../기획/디자인/디자인_시스템.md"))
        .withPropertyName("designSystemDoc")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("../기획/디자인/Image/Item"))
        .withPropertyName("itemArtDir")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":core"))     // 미션·성장·아이템·콘텐츠 (화면 뒤 로직)
    implementation(libs.vehicle.sdk)
    implementation(libs.pleos.tts)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre)   // 통계 탭 지도. 타일은 OpenFreeMap(키 없음)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 단위 테스트에서 android.jar 의 org.json 은 빈 껍데기라, Gleo 응답 JSON 을 확인하려면 실제 구현이 필요하다
    testImplementation("org.json:json:20240303")
}
