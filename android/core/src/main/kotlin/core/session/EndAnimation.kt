package core.session

import kotlinx.serialization.Serializable

/**
 * 주행 종료 애니메이션. 어느 점수에 어느 값을 쓰는지는 ContentPack.scoreBands 가 정한다.
 * 종류 enum 은 표시 코드와 같이 바뀌므로 원격 콘텐츠 업데이트(#31)로 바꾸지 않고 스토어 업데이트로 배포한다.
 */
@Serializable
enum class EndAnimation { DANCE, OK, OTL, NONE }
