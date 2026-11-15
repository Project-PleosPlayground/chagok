# android

Project-PleosPet 앱 코드. 안전점수 엔진(`engine/`), 화면 뒤 로직(`core/`), Android 앱(`app/`).
스펙: [`../기획/스펙_안전점수.md`](../기획/스펙_안전점수.md) v0.2. 검토: `../안전점수_스펙_v0.2_검토_1102.md`.
개발 스택(언어·빌드·SDK·라이브러리 버전): [`../기획/스펙_개발스택.md`](../기획/스펙_개발스택.md). 버전을 바꾸면 같은 PR에서 이 문서도 고친다.

## 폴더 구조

의존 방향은 `app → core → engine`이다. 패키지마다 넣는 것은 [개발 스택 스펙 3절](../기획/스펙_개발스택.md#3-모듈-구성)이 정본이다.

```
android/
├── settings.gradle.kts, build.gradle.kts   모듈 목록, 공통 빌드 설정
├── gradle/libs.versions.toml               라이브러리 버전 (정본)
├── tools/                                  시나리오 JSON 생성기, 에뮬레이터 설치·신호 주입 스크립트
│
├── engine/                     순수 Kotlin(JVM) 안전점수 엔진. Android·Pleos SDK 의존 없음
│   └── src/
│       ├── main/kotlin/engine/            엔진 코드
│       └── test/
│           ├── kotlin/engine/             시나리오·단위 테스트
│           └── resources/scenarios/       시나리오 JSON
│
├── core/                       순수 Kotlin(JVM) 화면 뒤 로직. Android·DB 의존 없음
│   └── src/
│       ├── main/
│       │   ├── kotlin/core/
│       │   │   ├── content/               콘텐츠 묶음, 검증, 원격 교체 (#31)
│       │   │   ├── mission/               미션 데이터 표·판정 규칙, 제공·도전·판정, 저장소
│       │   │   ├── character/             캐릭터 종류·고르기, 프로필 상태, 캐릭터별 성장, 코인(구매·스테이지 배치 저장)
│       │   │   ├── item/                  아이템, 아이템 칸(펫·소품·배경)
│       │   │   ├── session/               세션 기록, 저장소, 차량 상태
│       │   │   ├── flow/                  세션 종료 처리 (여러 기능을 묶어 부름)
│       │   │   ├── report/                리포트 집계
│       │   │   └── Time.kt, Numbers.kt    날짜 경계, 넘침 없는 덧셈
│       │   └── resources/content/         내장 콘텐츠 JSON (bundled.json)
│       └── test/kotlin/core/              main과 같은 패키지로 나눈다
│
└── app/                        Android 앱. Android·Pleos SDK는 여기서만 쓴다
    └── src/main/
        ├── AndroidManifest.xml            앱 이름, 권한, 첫 화면
        ├── java/ai/safetypoc/app/         앱 코드 (Kotlin)
        │   ├── MainActivity.kt, SafetyApp.kt, EngineHost.kt, AppConfig.kt
        │   ├── AppGraph.kt                :core 객체 묶음 (저장소, 미션 엔진, 파이프라인, 습관 미션 planner, AI)
        │   ├── ai/                        AI 중계 서버 HTTP 호출(HttpAiClient), 리포트용 캐릭터 정보, 동의(AiConsent) (#13)
        │   ├── ui/                        화면: nav(화면 이동·탭), onboarding, home, debug, driving, report, history, mission,
        │   │                                    decorate, settings, nav, theme, stage
        │   ├── data/                      store: 프로필·세션·미션 기기 파일 저장 (FileDocumentStore, #10)
        │   ├── drive/                     주행·정차 판별
        │   ├── content/                   원격 콘텐츠 확인·다운로드·저장 (RemoteContentUpdater, ContentFiles, #31)
        │   ├── source/                    차량 신호 입력 (Pleos Vehicle SDK, AAOS)
        │   └── voice/                     음성 재생
        ├── res/                           Android 리소스
        │   ├── drawable/                  UI 아이콘 (VectorDrawable)
        │   ├── values/                    문구 (strings.xml)
        │   └── raw/                       효과음. 온보딩 시동음은 engine_start (#44)
        └── assets/                        그림. 폴더 구조 그대로 들어간다
            ├── character/                 캐릭터 프레임 (차종/레벨/클립)
            ├── pet/                       펫
            ├── prop/                      소품
            └── background/                배경
```

- `app`의 코드 폴더 이름은 `java/`지만 Kotlin 코드도 여기 둔다. 안드로이드 프로젝트의 관례다. `engine`·`core`는 순수 Kotlin 모듈이라 `kotlin/`을 쓴다.
- 코드 폴더 아래 경로가 곧 패키지 이름이다. `java/ai/safetypoc/app/ui/home/`의 파일은 `package ai.safetypoc.app.ui.home`이다.
- `core/src/main/kotlin/core/`처럼 이름이 두 번 나온다. 앞은 모듈 폴더, 뒤는 패키지 이름이다. 순수 Kotlin 모듈은 둘을 같게 둔다(`engine`, `core`).
- `app/src/main/res/`는 Android 리소스다. 빌드할 때 `R.drawable.이름`, `R.string.이름` 같은 ID가 생긴다. 하위 폴더를 만들 수 없고, 리소스가 아닌 파일(`README.md` 등)을 넣으면 빌드가 실패한다.
- `core/src/main/resources/`는 Android 리소스가 아니라 JVM 리소스다. ID 없이 경로(`/content/bundled.json`)로 읽는다.
- 캐릭터·펫·소품·배경 그림은 `res/`가 아니라 `assets/`에 둔다 (#42). 어떤 그림을 띄울지 데이터가 정하므로 ID가 아니라 경로 문자열(`character/grandeur/still_lv3.png`)로 찾는다. `assets/`는 하위 폴더를 쓸 수 있고, 넣은 파일이 가공 없이 그대로 들어간다.
- 아직 파일이 없는 패키지·리소스 폴더는 `.gitkeep`으로 남겨 두었다.
- Android Studio 왼쪽 창의 기본 "Android" 보기는 폴더를 종류별로 묶어 보여 준다(`kotlin+java`, `res`). 실제 폴더 그대로 보려면 위쪽 드롭다운에서 "Project" 보기를 고른다.

## 실행

JDK 17 필요. 맥에서 `brew install openjdk@17`.

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :engine:test            # 시나리오 전부 × 프리셋 2 × 심각도 모드 2
```

결과: `engine/build/reports/tests/test/index.html`

## 엔진 사용법

```kotlin
val cfg = EngineConfig(thresholds = Presets.BLUELINK, severityMode = SeverityMode.RECORD_ONLY)  // 둘 다 기본값 없음. 팀 결정 전
val engine = DefaultSafetyScoreEngine(cfg)

scope.launch { engine.events.collect { /* 이벤트 확정 즉시. 캐릭터 표정·음성 */ } }
scope.launch { engine.lifecycle.collect { /* Started / Validated(인정 전 이벤트 포함) / Ended */ } }
scope.launch { engine.feedback.collect { cue -> voicePlayer.play(cue) } }
scope.launch { engine.sessions.collect { result -> /* 세션 점수·세부 점수·이벤트 목록 → 미션 모듈 */ } }

// 신호는 출처와 무관하게 VehicleSignal 로. 시계는 timestampMs 와 onTick 뿐.
engine.onSignal(VehicleSignal.Speed(nowMs, mps))
engine.onSignal(VehicleSignal.BrakePedal(nowMs, pressed))     // Pleos Brake 는 Boolean 뿐
engine.onSignal(VehicleSignal.SteeringAngle(nowMs, deg))
engine.onSignal(VehicleSignal.TurnSignal(nowMs, TurnSignalState.LEFT))
engine.onSignal(VehicleSignal.Gear(nowMs, GearState.D))
engine.onTick(nowMs)                                           // Android 에서 1초마다
engine.endSessionNow(nowMs)                                    // 앱 종료 직전
```

### :app 이 지켜야 할 것

- **속도는 1 Hz 이상으로 넣는다.** 스펙 2-2 의 stale 판정(5초 무응답)이 있어서, "변경 시 콜백"만 받으면 정속 주행이 stale 로 잡힌다.
  AAOS `CarPropertyManager.registerCallback(cb, PERF_VEHICLE_SPEED, SENSOR_RATE_NORMAL /*1Hz*/ 또는 SENSOR_RATE_FAST)` 로 받으면 된다.
- `onTick` 은 1초마다. 세션 종료(정차 3분)·쿨다운·발화 지연은 틱에서만 진행된다.
- 조향각 단위(핸들 각 / 앞바퀴 각)가 미확정이라 `LaneChangeConfig.theta1ByKmh` 는 가안. 에뮬레이터 확인 뒤 바꾼다.

## 시나리오 JSON (7-1)

`engine/src/test/resources/scenarios/*.json`. 파일 추가 = 테스트 추가. `ScenarioTest` 가 전부 읽어 `expect` 와 비교한다.

```json
{
  "name": "S03_harsh_brake",
  "signals": [
    {"t": 0, "gear": "D", "speed": 16.67, "brake": false, "steering": 0, "turnSignal": "OFF"},
    {"t": 3000, "brake": true},
    {"t": 3100, "speed": 15.97}, ...
  ],
  "tickEveryMs": 1000,
  "endAt": 230000,                 // 마지막 신호 뒤 이 시각까지 틱만 → 정차 3분 자동 종료
  "distanceKmOverride": 10,        // t=0 에 TripDistance(0), 마지막 신호 시각에 TripDistance(10km) 주입
  "config": {"sessionEndIdleMs": 10000, "feedback": {"sameTypeMinMs": 60000}},   // 선택
  "expect": {
    "events": [{"type": "HARSH_BRAKE", "severity": 2}],
    "scoreByMode": {"WEIGHTED": 93, "RECORD_ONLY": 95},    // 또는 "score": 93 (모든 모드 공통)
    "valid": true, "validated": true, "validatedEventCount": 1,
    "liveEventCount": 1,
    "cues": [{"type": "SESSION_START"}, {"type": "HARSH_BRAKE", "atMs": 7500}]
  }
}
```

- `t` 는 ms. 같은 `t` 의 신호는 파일 순서대로. `brake` 는 true/false (숫자면 >0 이 밟음).
- `speed` 는 m/s. 정속 구간에도 1초마다 찍는다(위 stale 이유).
- 생성기 `python3 tools/gen_scenarios.py` 는 L05~L09(차선변경)만 다시 쓴다.

## 현재 상태 (11/4)

- **정본 시나리오는 `scenarios/S01~S16` 12개** (형식·통과 규칙은 `scenarios/README_scenarios.md`). 엔진 수정 없이 전부 통과.
- 차선변경 `L05~L09` 6개는 엔진 쪽에서 만든 것(생성기 `tools/gen_scenarios.py`). 조향각이 핸들 각 스케일로 확인돼(11/3) θ1 25/15/10° 그대로 쓴다.
- 18개 파일 × 프리셋 2(KOTSA/BLUELINK) × 심각도 모드 3(a/b/c) = 108 케이스 전부 통과.
- 러너는 두 expect 형식을 다 읽는다: 스펙 형식(`scoreByMode {a,b,c}`, `feedbackCues`, `firstCueAtMs`, `validatedAtMs`, `eventsBeforeValidated`, `config {F_same_s, F_all_s, N_max}`) + 엔진 형식(`score`, `cues`, `liveEventCount`, `config.feedback`).
- 11/4 팀 결정 대기: 임계값 프리셋, 심각도 모드. 결정되면 `Presets` 에서 안 쓰는 쪽을 지우고 `EngineConfig` 기본값을 넣는다.

## 앱 빌드 (Windows, Git Bash)

```
bash gradlew :app:assembleDebug --no-daemon     # app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties`에 `sdk.dir`이 필요하다(Android Studio가 만든다, 저장소에는 안 올림).
- compileSdk 35. AGP 8.8 이 `android-37.0` 폴더를 못 읽어서 35 를 쓴다. 없으면 Gradle 이 받아온다.
- Vehicle SDK 는 2.3.2. 2.3.1 은 Pleos Maven 에 없다.

## :core (11/7) — 화면 뒤 로직

`core/` 순수 Kotlin. `:engine` 의 `SessionResult` 를 받아 저장·미션·성장·코인·리포트를 처리한다. Android 의존 없음. 앱은 `store/` 의 저장 구현에 `:app` 의 파일 저장 매체(`FileDocumentStore`)를 넣어 쓴다 (#10).

패키지 구성은 `기획/스펙_개발스택.md` 3절. 파일은 `core/src/main/kotlin/core/` 기준.

| 파일 | 내용 |
|---|---|
| `content/Content.kt` | ContentPack(미션·아이템·점수 구간·엔진 수치 묶음), ContentCodec, BundledContent(내장 묶음), DefaultMissions, DefaultItems |
| `content/ContentUpdate.kt`, `ContentValidator.kt`, `UpdateRetry.kt` | 원격 콘텐츠 판단·검증·교체, 재시도 간격 (#31) |
| `content/Csv.kt`, `MissionCsv.kt`, `ItemCsv.kt`, `ContentCsvImport.kt` | 기획 CSV(미션(조합)·미션 단계 보상·콘텐츠 텍스트·아이템) → 내장 콘텐츠. `./gradlew :core:importContentCsv` (기본 `기획/데이터/`, 다른 폴더는 `-PcsvDir=`). 콘텐츠 검사를 통과할 때만 `bundled.json` 의 미션·아이템을 바꾼다. 아이템 CSV 가 없으면 아이템은 그대로 |
| `mission/Mission.kt` | ConditionType 9종, MissionKind(개인·주간), MissionStatus(제공·도전 중·성공·실패·미판정·취소·사라짐), 미션 데이터 표(기획/데이터_컬럼_사전.md: ConditionRow 미션(조합), TierReward 미션 단계 보상, MissionText 콘텐츠 텍스트, MissionRules 제공 개수·슬롯·유효 주행 경험치·취소·실패 패널티 배수, MissionCatalog), ConditionType 은 조건 번호표의 조건ID 를 가진다, MissionInstance(받은 미션. 제공할 때 정한 목표·보상·이름) |
| `mission/ConditionRules.kt` | 조건별 판정 규칙 표(측정 함수·비교·세는 범위), 공통 판정(afterDrive, atExpiry), 주행 중에 셀 수 있는 조건(LIVE), RangePolicy(이하 조건을 한 주행으로 셀지, 주간 미션은 유효시간 동안 셀지) |
| `mission/MissionEngine.kt` | refresh(00:00 갱신·제공·주간 목록 채우기·유효시간 끝 처리·주행 보전), offer(정한 미션 제공), challenge·cancel·clear(도전 슬롯. 주간 미션에 도전하면 목록을 채운다), liveProgress(주행 중 진행도, 판정 없음), onDriveEnd(도전 중 미션 판정, 성공 보상·실패 패널티). 기준은 `기획/07_미션.md` |
| `mission/LiveDrive.kt` | 주행 중 진행도용으로 지금 주행의 이벤트를 모은다. 유효 주행으로 인정된 뒤부터 기록을 내고, 인정 전 이벤트도 센다 (07 "판정 시점") |
| `mission/MissionStore.kt` | MissionStore + InMemory 구현 |
| `character/Character.kt` | CharacterId(캐릭터 종류 번호), CharacterDef(번호·이름·그림 폴더), CharacterSelector(고르기) |
| `character/Profile.kt` | Profile, ProfileState(고른 캐릭터·코인·가진 아이템·스테이지 배치), CharacterProgress(캐릭터별 별칭·경험치·단계) |
| `character/Growth.kt` | GrowthModule(경험치→5단계, 레벨업·레벨 하락), Wallet(코인 받기·깎기, 구매, 스테이지 배치 저장) |
| `item/Item.kt` | ItemId(아이템 번호), ItemCategory(펫·소품·배경), ItemSlot(처음 놓이는 자리, 종류로 정함), ItemDef(번호·종류·이름 키·그림·가격·자리), StagePoint(스테이지 좌표) |
| `item/Stage.kt` | StageItem(놓인 아이템·위치), StageRules(놓기·빼기·옮기기·저장 정리. 여러 개, 같은 아이템 한 번, 배경 한 개, 옮긴 것이 맨 위) |
| `character/ProfileStore.kt` | ProfileStore(프로필·프로필 상태·캐릭터별 성장) + InMemory 구현 |
| `session/SessionRecord.kt` | SessionRecord(엔진 결과 + 위치·출발지·배터리 메타), EventRecord, VehicleStatus |
| `session/SessionStore.kt` | SessionStore + InMemory 구현 |
| `store/DocumentStore.kt`, `StoreCodec.kt`, `DocumentStores.kt` | 저장 매체 인터페이스(모음·키·JSON 문서)와 메모리 구현, 저장 문서 JSON(스키마 버전·수정 시각 봉투), ProfileStore·SessionStore·MissionStore 의 문서 저장 구현 (#10) |
| `session/EndAnimation.kt` | 종료 애니메이션 enum |
| `flow/SessionEndPipeline.kt` | 세션 종료 → 저장 → 미션 → 코인·경험치(유효 주행 경험치 포함) → 요약(애니메이션 구간, 규칙 코멘트) |
| `flow/MissionActions.kt`, `Rewards.kt` | 화면의 미션 동작(도전·취소·비움)과 틱 갱신, 보상·취소·실패 패널티를 코인·경험치에 반영 |
| `ai/Habits.kt` | HabitRanker: 최근 7일 주행에서 고칠 습관·지킬 습관을 규칙으로 고르고, 바라는 목표치를 조건 표의 단계로 바꾼다 (#13) |
| `ai/PersonalMissionPlanner.kt` | 개인 미션(하루 2개)을 랜덤 대신 7일 습관으로 정해 `MissionEngine.offer`. `refresh` 보다 먼저 부른다. 7일 유효 주행 3회 미만이면 아무것도 안 함(랜덤 제공 그대로) |
| `ai/AiPayload.kt` | 주행 끝 AI 리포트 입력(`DriveEndPayload`, 서버 `/api/drive-end`): 방금 주행, 7일 집계, 습관, 어제·오늘 개인 미션. kotlinx.serialization. 위치·도로명·날짜·시각 없음(요일·시간대만). `AiDriveEnd` 는 동의한 프로필만 보낸다 |
| `ai/AiService.kt` | AiClient(:app 이 HTTP 로 구현), AiGate(이벤트 0회·10분 미만은 호출 안 함), AiService(8초 타임아웃), AiDriveEnd(요약의 comment 를 AI 리포트로 바꿈. 실패하면 규칙 문장 그대로) |
| `report/ReportAggregator.kt` | 기간 집계(10km당 횟수, 깜빡이 매칭률, 평균 점수), 주간 전주 대비, 달력 셀, 세션 상세 구간 마커 |
| `Time.kt`, `Numbers.kt` | KST 날·주 경계, 넘침 없는 덧셈 |

사용 (앱 쪽):
```kotlin
val content = ContentState(ContentUpdate.pickAtStartupFromBytes(BundledContent.pack, cachedBytes, appVersionCode))   // #31 원격 콘텐츠
val missions = MissionEngine(missionStore, sessionStore, missions = { content.active.missions })
val pipeline = SessionEndPipeline(sessionStore, profileStore, missions, content = { content.active })
val engine = DefaultSafetyScoreEngine(content.active.engineConfig(AppConfig.engine))  // #33 원격 조정 수치 (스펙 6-1)
content.applyStaged()?.let { engine.updateConfig(it.engineConfig(AppConfig.engine)) } // 탭 전환 때. 엔진은 다음 세션부터 적용
val actions = MissionActions(missions, Rewards(profileStore, GrowthModule(), Wallet { content.active.items }))
val planner = PersonalMissionPlanner(missions, missionStore, sessionStore, { content.active.missions })   // #13 습관 기반 개인 미션
planner.offerToday(profileId, System.currentTimeMillis())                // refresh 보다 먼저. 오늘 개인 미션 2개를 7일 습관으로
actions.refresh(profileId, System.currentTimeMillis(), driveStartMs = null)   // 앱 시작 때. 주행 중이면 그 주행의 시작 시각
actions.challenge(profileId, uid, now, driving)                         // 미션 탭. cancel·clear 도 같은 모양
engine.sessions.collect { result ->                                     // 엔진 Flow
    val record = SessionRecord(id, profileId, result, taggedEvents, startWall, endWall, origin, dest)
    val summary = pipeline.onSessionEnd(record, vehicleStatus, System.currentTimeMillis())
    // summary.completed / coinsGained / xpGained / leveledUpTo / animation / comment → 주행 종료 화면 (먼저 보여 준다)
    val withAi = aiDriveEnd.run(summary, AiCharacter(name, stage, persona), vehicleStatus, System.currentTimeMillis())   // 최대 8초. comment 만 바뀐다
}
val aiDriveEnd = AiDriveEnd(AiPayloadBuilder(sessionStore, missionStore), AiService(httpClient))   // httpClient: AiClient 구현. POST <서버>/api/drive-end, 헤더 x-app-token, 본문 = 받은 JSON 그대로
planner.offerToday(profileId, now)                                      // 1분 틱 refresh 앞에도 (00:00 갱신 때 습관 미션이 먼저 나가게)
actions.refresh(profileId, now, driveStartMs)                           // 1분 틱 (00:00 갱신, 유효시간 끝)
ReportAggregator(sessionStore).weekTrend(profileId, now)                // 통계 탭
```
가안 수치: 경험치 구간 0/100/300/600/1000, 미션 조건 표의 단계 목표치·단계별 보상·유효 주행 경험치(`기획/데이터` CSV 확정 전), 아이템 가격, 점수대 애니메이션(90+/70~89/70 미만).

## :app AI·원격 콘텐츠 연결 (11/12)

둘 다 서버 주소가 없으면 조용히 꺼진다(AI 는 규칙 문장, 콘텐츠는 내장만). 주소·토큰은 저장소에 넣지 않고 `android/local.properties`(gitignore) 에 둔다. 환경변수(`AI_RELAY_URL`, `AI_RELAY_TOKEN`, `CONTENT_BASE_URL`)로도 된다.

```
ai.relay.url=https://pleospet-ai-relay.vercel.app
ai.relay.token=<APP_TOKEN. 서버 배포 담당에게 받는다>
content.base.url=https://<ref>.supabase.co/storage/v1/object/public/pleospet-content
```

| 어디 | 무엇 |
|---|---|
| `SafetyApp.startMissionTicker` | 1분 틱마다 `graph.planner.offerToday` → `missionActions.refresh`. 7일 유효 주행 3회부터 개인 미션 2개가 습관으로 나온다 |
| `location/` | `GpsLocation`(표준 LocationManager, 1초) → `EventLocations`(이벤트 순간 위치, 30초 넘은 위치는 버림) → `DriveRecorder` 가 `EventRecord.lat/lng` 에 붙임. 기기 안에만, 서버로 안 보냄. 시연 주행은 `DemoRoute`(강남역→선릉역 근사, ±20m)라 두 번 돌리면 반복 지점이 생긴다. 에뮬레이터 GPS: `adb emu geo fix <경도> <위도>` |
| `ui/stats/` | 통계 탭 (DashboardView 로우파이 시안) [가안]: 달력(하루 주행 수로 칸 진하기) + 고른 날 주행 목록, 이번 주 한마디, 누적 안전점수(지난주 대비·이번 주 감점 행동), 1주일간 추이그래프. 다른 날을 고르면 그날 분석, 주행을 누르면 상세(점수·구간 막대·감점 행동 목록·운전 습관 3단계). 한마디·분석·목록 문장은 [임시] 규칙 문장 |
| `ui/report/DriveMap` | 통계 탭 지도 (#13) [임시 화면]: MapLibre + OpenFreeMap 밝은 회색 스타일(키 없음, 구글 플레이 서비스 불필요). 감점 행동 핀(`ReportState.places`)과 자주 놀라는 곳 반경 200m 원(`hotspots`). 라벨은 `name:ko`. 원 계산은 `MapShapes`(JVM 테스트) |
| `ai/AiConsent` | AI 리포트 동의, 프로필마다, **기본 꺼짐**. 꺼져 있으면 서버로 아무것도 보내지 않는다(규칙 문장). 안내 문구 `R.string.ai_consent_notice`. 설정 탭에서 켜고 끄고, 첫 주행 종료 화면에서 한 번 묻는다(`asked`). "좋아요"를 누르면 방금 주행의 한마디도 받아 온다 |
| `settings/AppSettings`, `ui/settings/` | 설정 탭 (#101) [가안 화면]: AI 주행 리포트, 급제동 위치 기록(끄면 GPS 멈춤·위치 안 붙임), 캐릭터 음성(`SwitchableVoicePlayer`), 프로필 선택으로 가는 버튼(#119, 프로필 선택에서 다시 시작). 주행 중에는 바꾸지 않는다 |
| `ui/transform/`, `ui/art/CharacterArt` | 캐릭터 그림과 변신 [가안 화면]. 홈 캐릭터 자리에 단계별 정지 그림(`assets/character/<차종>/still_lv<단계>.png`), 주행 중에도 같은 그림. 없으면 프로필 그림을 대신 쓴다. 그림은 IO 스레드에서 읽고 `CharacterArtStore` 가 앱 최대 메모리의 1/16 안에서 캐시한다(#42, 기획/06 "캐릭터"). 캐릭터를 누르면 2단계부터 지금 단계까지 영상을 이어서 튼다(1단계면 캐릭터가 흔들린다). 처음 볼 수 있게 되면 "눌러 봐!" 말풍선을 누를 때까지 띄운다. 레벨업하면 오른 단계 한 편. 정차 중에만 띄운다. 영상은 `transform_lv<단계>.mp4`(640×640, 흰 바탕, 영화 포스터 구성(Bebas Neue, SIL OFL), 캐릭터를 누르면 이어 붙인 `transform_seq<단계>.mp4` 로 끊김 없이, 약 3.9초, 원본의 1.3배 속도, 효과음 포함. 효과음은 시스템 효과음 용도로 잠깐 포커스를 받고, 설정 "캐릭터 음성"을 끄면 소리 없이), 지금은 그랜저만 있어 다른 차종도 그랜저 영상을 쓴다. 기본 차종은 그랜저(`AppConfig.DEFAULT_CHARACTER_ASSET`). 디버그 탭 "경험치 +100"으로 레벨업을 볼 수 있다 |
| 시연 모드 | `local.properties` 에 `demo.start.stage=3`(또는 환경변수 `DEMO_START_STAGE`): 캐릭터가 3단계(다음 단계까지 60%)로, 산타 모자를 쓰고 시작한다. 변신은 홈에서 캐릭터를 누르면 본다. 없거나 1 이면 꺼짐 |
| `SafetyApp.onSessionEnded` | 요약을 먼저 보여 주고, 뒤에서 `graph.ai.run` → 답이 오면 `drives.update` 로 코멘트만 바꿈 (최대 8초). 시연 주행 재생(디버그 탭)도 같은 길 |
| `SafetyApp.checkContentIfDue` | 앱 시작·탭 전환 때, 주행 중이 아니고 30분(`AppConfig.CONTENT_CHECK_INTERVAL_MS`)이 지났으면 IO 스레드에서 `RemoteContentUpdater.check`. 받은 묶음은 다음 탭 전환 때 `applyStaged` (주행 중엔 적용 안 함) |
| `AppGraph` 생성 | `ContentUpdate.pickAtStartupFromBytes(내장, filesDir/content/pack.json, versionCode)` 로 저장해 둔 묶음부터 쓴다 |

원격 콘텐츠 서버(Supabase Storage, 공개 읽기 버킷. 기존 Pro 프로젝트 안 `pleospet-content` 버킷, 업로드는 서버 배포 담당) 파일 배치와 올리는 법. 버킷 이름은 주소에 들어가므로 바꾸면 `content.base.url` 도 바꾼다:

```
<base>/manifest/<appVersionCode>.json   이 앱 버전이 보는 manifest (contentVersion, minAppVersion, path, sha256)
<base>/packs/content-v<N>.json          묶음 파일
python3 tools/content_release.py --pack <묶음.json> --app-version 1 --out dist/content   # 두 파일을 만든다. dist/content 를 그대로 올린다
```
실패 시 재시도 간격·타임아웃은 core `UpdateRetry`·`UpdateTiming`, 상태는 `filesDir/content/retry.json`. 심사 중인 앱 버전의 manifest 는 바꾸지 않는다(제출본 고정, #31).
