plugins { kotlin("jvm"); kotlin("plugin.serialization") }
// :core — 화면 뒤 로직. 저장(인터페이스, 메모리·문서 저장 구현), 미션 판정, 성장, 코인·아이템, 세션 종료 파이프라인, 리포트 집계.
// Android 의존 없음. 저장소(store/)도 여기서 구현하고, :app 은 저장 매체(DocumentStore)의 파일 구현만 넣는다 (#10).
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")   // 원격 콘텐츠 JSON (#31)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }

// 기획 CSV(미션(조합)·미션 단계 보상·콘텐츠 텍스트, 있으면 아이템)를 내장 콘텐츠로 옮긴다.
// 사용: ./gradlew :core:importContentCsv            (기획/데이터/ 의 CSV)
//       ./gradlew :core:importContentCsv -PcsvDir=<폴더>   (android/ 기준 상대 경로)
tasks.register<JavaExec>("importContentCsv") {
    group = "content"
    description = "기획 CSV 를 core/src/main/resources/content/bundled.json 의 미션·아이템으로 옮긴다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("core.content.ContentCsvImportKt")
    val csvDir = rootProject.file((findProperty("csvDir") as String?) ?: "../기획/데이터")
    args(csvDir.path, file("src/main/resources/content/bundled.json").path)
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
