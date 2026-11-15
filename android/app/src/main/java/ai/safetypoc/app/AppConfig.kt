package ai.safetypoc.app

import android.car.hardware.property.CarPropertyManager
import engine.EngineConfig
import engine.Presets
import engine.SeverityMode

/**
 * 앱 쪽 설정을 한곳에 모은다. 팀이 정하지 않은 값은 [미정] 으로 표시한다.
 */
object AppConfig {

    /**
     * [미정] 임계값 프리셋(KOTSA / BLUELINK)과 심각도 모드는 팀 결정 전.
     * RECORD_ONLY 는 11/2 추천안 (EngineConfig.kt 주석).
     * 정차 후 주행 종료 대기: 디버그 빌드는 [DEMO_SESSION_END_IDLE_MS], 릴리스(시연·제출) 빌드는 엔진 기본값(스펙 3분).
     */
    val engine = EngineConfig(thresholds = Presets.BLUELINK, severityMode = SeverityMode.RECORD_ONLY)
        .let { if (BuildConfig.DEBUG) it.copy(sessionEndIdleMs = DEMO_SESSION_END_IDLE_MS) else it }

    /** 엔진의 세션 종료·쿨다운·발화 지연은 틱에서만 진행된다 (엔진 README "onTick 은 1초마다"). */
    const val TICK_INTERVAL_MS = 1_000L

    /** 미션 제공·갱신·유효시간 끝 처리 주기. 구현에서 고른 값이다. 00:00 갱신과 유효시간 끝은 늦어도 이만큼 뒤에 반영된다 */
    const val MISSION_TICK_MS = 60_000L

    /** [임시] 디버그 빌드 설정: 정차한 뒤 이만큼 지나면 주행을 끝낸다 (엔진 기본 3분) */
    const val DEMO_SESSION_END_IDLE_MS = 10_000L

    /**
     * AI 리포트(#13)를 부르는 주행 최소 시간(분). 짧은 주행은 규칙 문장으로 충분해서 부르지 않는다(비용, 자료/AI_설계 1절).
     * 디버그 빌드는 시연 주행 재생(약 3분)에서도 보이게 0, 릴리스는 10분.
     */
    val AI_MIN_DRIVE_MINUTES: Int = if (BuildConfig.DEBUG) 0 else core.ai.AiGate.MIN_MINUTES

    /** 원격 콘텐츠(#31) manifest 를 다시 확인하는 최소 간격. 비주행 상태에서 앱 시작·탭 전환 때 이 간격이 지났으면 확인한다. 실패 뒤 간격은 core UpdateRetry 가 따로 둔다 */
    const val CONTENT_CHECK_INTERVAL_MS = 30 * 60_000L

    /** 디버그 탭의 포인트 주기 버튼이 한 번에 주는 포인트 (아이템 하나 값, 08 "얻기") */
    const val DEBUG_GRANT_POINTS = 200

    /** 처음 고른 상태로 둘 차종(그림 폴더 이름). 변신 영상이 있는 그랜저. 콘텐츠에 없으면 첫 캐릭터 */
    const val DEFAULT_CHARACTER_ASSET = "grandeur"

    /** [임시] 캐릭터 기본 별명. 별명 입력 화면이 생기기 전까지 홈·변신 제목에 쓴다 */
    const val DEFAULT_CHARACTER_ALIAS = "차곡"

    /** 디버그 탭의 경험치 주기 버튼이 한 번에 주는 경험치. 1→2단계 구간(GrowthModule) 만큼이라 한 번이면 레벨업을 볼 수 있다 */
    const val DEBUG_GRANT_XP = 100

    /** 디버그 탭(주행 시작·종료, 포인트 주기, 시연 주행 재생)을 보일지. 디버그 빌드에서만 보이고 릴리스(시연·제출) 빌드에서는 숨긴다 */
    val SHOW_DEBUG_TAB: Boolean = BuildConfig.DEBUG

    /**
     * 속도 구독 주기. 엔진은 100 ms 로 재표본화하므로(EngineConfig.resampleMs) 1 Hz 로는 급가속 판정이 거칠다.
     * README 가 허용한 두 값 중 FAST 를 쓴다. 에뮬레이터에서는 10~20 Hz 로 들어옴(스펙 2절).
     */
    const val SPEED_RATE_HZ = CarPropertyManager.SENSOR_RATE_FAST

    /**
     * [미확인] Pleos Odometer 트립 거리의 단위. 엔진 TripDistance 는 m.
     * 문서에서 확인하지 못해 km 로 가정한다. 에뮬레이터에서 값이 1000배 다르면 이 값을 1 로 바꾼다.
     */
    const val TRIP_DISTANCE_TO_METERS = 1_000f

    /** 화면에 남길 최근 이벤트 수. 디버그 화면 한 장에 들어가는 정도. */
    const val RECENT_EVENT_LIMIT = 10

    /** 실행 중에 요청할 권한. Manifest 와 같은 목록이어야 한다. */
    val runtimePermissions = arrayOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_POWERTRAIN",
        "pleos.car.permission.CAR_DRIVE",
        "pleos.car.permission.READ_CAR_STEERING",
        "pleos.car.permission.CAR_LIGHTS",
        // 출발 전 안전벨트, 충전·배터리 미션 (07 미션 조건 6~8)
        "pleos.car.permission.CAR_SEATS",
        "pleos.car.permission.CAR_ENERGY",
        "pleos.car.permission.TTS_SERVICE",
        // 감점 행동이 난 곳(반복 지점). 기기 안 기록에만 쓰고 서버로 보내지 않는다 (#13)
        "android.permission.ACCESS_FINE_LOCATION",
    )
}
