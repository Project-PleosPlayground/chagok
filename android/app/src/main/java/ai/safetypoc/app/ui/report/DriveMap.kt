package ai.safetypoc.app.ui.report

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.theme.AppPalette
import android.graphics.BitmapFactory
import engine.EventType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import core.ai.Hotspot
import core.ai.Hotspots
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** 지도 바탕. OpenFreeMap(오픈스트리트맵) 밝은 회색 스타일. 키가 없다 */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"

/**
 * 통계 탭 지도 (#13): 감점 행동이 난 곳(핀)과 자주 놀라는 곳(반경 200m 원). [임시] 디자인 전.
 * 좌표는 기기 안 기록에서만 온다. 지도 타일을 받을 때 보고 있는 지역이 타일 서버에 전해지는 것은 어느 지도든 같다.
 */
@Composable
fun DriveMap(
    places: List<EventPlace>,
    hotspots: List<Hotspot>,
    modifier: Modifier = Modifier,
    /** false 면 끌기·확대를 막는다. 작은 지도에서 쓴다 */
    gestures: Boolean = true,
    /** 지도를 누르면 (작은 지도 → 큰 지도 열기) */
    onTap: (() -> Unit)? = null,
    /** 지도가 준비되면 확대·축소 함수(단계 수를 받는다)를 넘긴다. 큰 지도의 +/− 버튼용 */
    onZoomReady: ((zoomBy: (Double) -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { MapHolder() }
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                if (!gestures) map.uiSettings.setAllGesturesEnabled(false)
                onTap?.let { tap -> map.addOnMapClickListener { tap(); true } }
                // 끌고 확대할 수 있는 큰 지도: 깃발을 누르면 그 자리로 확대 (11/15)
                if (gestures && onTap == null) map.addOnMapClickListener { at ->
                    val hit = map.queryRenderedFeatures(map.projection.toScreenLocation(at), MapHolder.PLACES_DOT).firstOrNull()
                    val pt = hit?.geometry() as? Point ?: return@addOnMapClickListener false
                    val zoom = maxOf(map.cameraPosition.zoom + 2, MapHolder.FLAG_ZOOM)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pt.latitude(), pt.longitude()), zoom))
                    true
                }
                map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                    MapHolder.FLAGS.forEach { (name, res) -> style.addImage(name, BitmapFactory.decodeResource(context.resources, res)) }
                    holder.ready(map, style)
                }
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view.onPause(); view.onStop(); view.onDestroy() }
    }
    LaunchedEffect(holder) { onZoomReady?.invoke(holder::zoomBy) }
    AndroidView({ view }, modifier, update = { holder.show(places, hotspots) })
}

/** 스타일이 다 받아진 뒤에야 레이어를 넣을 수 있어서, 그 전에 온 데이터는 들고 있다가 넣는다 */
private class MapHolder {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var pending: Pair<List<EventPlace>, List<Hotspot>>? = null

    fun ready(map: MapLibreMap, style: Style) {
        this.map = map; this.style = style
        koreanLabels(style)
        val red = AppPalette.red.toArgb()
        style.addSource(GeoJsonSource(SPOTS))
        style.addSource(GeoJsonSource(PLACES))
        style.addLayer(FillLayer(SPOTS_FILL, SPOTS).withProperties(PropertyFactory.fillColor(red), PropertyFactory.fillOpacity(0.15f)))
        style.addLayer(LineLayer(SPOTS_LINE, SPOTS).withProperties(PropertyFactory.lineColor(red), PropertyFactory.lineWidth(2f)))
        // 사건 자리에 사건 종류별 색 깃발. 깃대 받침 가운데가 사건 자리에 오게 (11/15)
        style.addLayer(
            SymbolLayer(PLACES_DOT, PLACES).withProperties(
                PropertyFactory.iconImage(Expression.get(KIND)),
                PropertyFactory.iconSize(FLAG_SIZE),
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconOffset(arrayOf(FLAG_OFFSET_X, 0f)),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        pending?.let { (p, h) -> show(p, h) }
    }

    /** 마지막으로 그린 데이터. 화면이 다시 그려질 때마다 카메라를 맞추면 사용자가 확대한 것이 되돌아가서, 데이터가 바뀔 때만 맞춘다 (11/15) */
    private var shown: Pair<List<EventPlace>, List<Hotspot>>? = null

    /** 큰 지도의 +/− 버튼 */
    fun zoomBy(delta: Double) { map?.animateCamera(CameraUpdateFactory.zoomBy(delta)) }

    fun show(places: List<EventPlace>, hotspots: List<Hotspot>) {
        val style = style; val map = map
        if (style == null || map == null) { pending = places to hotspots; return }
        pending = null
        if (shown == places to hotspots) return
        shown = places to hotspots
        style.getSourceAs<GeoJsonSource>(PLACES)?.setGeoJson(
            FeatureCollection.fromFeatures(
                places.map { p ->
                    Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply { addStringProperty(KIND, flagOf(p.type)) }
                },
            ),
        )
        style.getSourceAs<GeoJsonSource>(SPOTS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                hotspots.map { h ->
                    val ring = MapShapes.circle(h.lat, h.lng, Hotspots.RADIUS_M).map { (la, ln) -> Point.fromLngLat(ln, la) }
                    Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
                },
            ),
        )
        // 자주 놀라는 곳은 원 전체가 보이게 원 둘레까지 담는다. 핀 하나·둘만 담으면 너무 확대돼 화면이 원 안에 들어간다
        val rings = hotspots.flatMap { MapShapes.circle(it.lat, it.lng, Hotspots.RADIUS_M, n = 8) }
        val b = MapShapes.bounds(places.map { it.lat to it.lng } + rings) ?: return
        if (b[0] == b[2] && b[1] == b[3]) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(b[0], b[1]), SINGLE_ZOOM))
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b[2], b[3], b[0], b[1]), PADDING_PX))
            if (map.cameraPosition.zoom > SINGLE_ZOOM) map.moveCamera(CameraUpdateFactory.zoomTo(SINGLE_ZOOM))
        }
    }

    /** 라벨을 한국어 이름으로 (없으면 기본 이름). 오픈스트리트맵은 name:ko 를 따로 둔다 */
    private fun koreanLabels(style: Style) {
        val name = Expression.coalesce(Expression.get("name:ko"), Expression.get("name"))
        for (layer in style.layers.filterIsInstance<SymbolLayer>()) {
            if (layer.textField.expression?.toString()?.contains("name") == true) layer.setProperties(PropertyFactory.textField(name))
        }
    }

    companion object {
        const val KIND = "kind"
        val FLAGS = listOf(
            "flag-red" to R.drawable.map_flag_red, "flag-orange" to R.drawable.map_flag_orange, "flag-pink" to R.drawable.map_flag_pink,
            "flag-green" to R.drawable.map_flag_green, "flag-blue" to R.drawable.map_flag_blue,
        )
        const val FLAG_SIZE = 0.42f
        /** 깃발 그림에서 깃대 받침이 가로 31% 쯤에 있어 가운데로 맞추려 오른쪽으로 민다 (그림 픽셀) */
        const val FLAG_OFFSET_X = 23f
        /** 사건 종류별 깃발: 급제동 빨강 · 급가속 주황(주행 중 말풍선 색과 같게) · 깜빡이 미시행 분홍 · 깜빡이 켜고 차로 변경 초록 · 갈지자·과속 파랑 */
        fun flagOf(t: EventType) = when (t) {
            EventType.HARSH_BRAKE -> "flag-red"
            EventType.HARSH_ACCEL -> "flag-orange"
            EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL -> "flag-pink"
            EventType.LANE_CHANGE_SIGNALED -> "flag-green"
            EventType.WEAVING_EST, EventType.SPEEDING -> "flag-blue"
        }
        const val SPOTS = "hotspots"; const val PLACES = "places"
        const val SPOTS_FILL = "hotspots-fill"; const val SPOTS_LINE = "hotspots-line"; const val PLACES_DOT = "places-dot"
        const val SINGLE_ZOOM = 15.0
        /** 깃발을 눌렀을 때 적어도 이만큼 확대 */
        const val FLAG_ZOOM = 17.5
        const val PADDING_PX = 48
    }
}
