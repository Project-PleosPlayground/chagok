package core.item

/**
 * 아이템 이미지 값(ItemDef.imageKey)과 앱 assets 경로 사이의 규칙 (06 "폴더 구조", 데이터 컬럼 사전 "아이템").
 * 이미지 값은 `기획/디자인/Image/Item/` 아래 PNG의 상대 경로에서 확장자를 뺀 것이다. 예: `Pet/Haechi/Haechi_Lv1`.
 * 빌드가 그 폴더의 PNG를 assets의 [ASSET_DIR] 아래에 같은 경로로 넣는다(app/build.gradle.kts `syncItemArt`).
 */
object ItemImage {
    /** assets 안 아이템 그림 폴더. app/build.gradle.kts 의 itemArtAssetDir 과 같아야 한다(ItemArtFilesTest 가 확인한다) */
    const val ASSET_DIR = "item"
    const val EXTENSION = ".png"

    /** `/`로 나뉜 조각마다 영문 대소문자·숫자·밑줄. 앞뒤 `/`, 빈 조각, `.`, 띄어쓰기는 허용하지 않는다 */
    private val PATH = Regex("[A-Za-z0-9_]+(/[A-Za-z0-9_]+)*")

    fun isValid(image: String): Boolean = PATH.matches(image)

    /** 이미지 값 → assets 경로. 규칙에 맞지 않으면 null */
    fun assetPath(image: String): String? = if (isValid(image)) "$ASSET_DIR/$image$EXTENSION" else null
}
