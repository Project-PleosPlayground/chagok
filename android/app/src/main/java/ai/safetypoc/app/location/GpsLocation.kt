package ai.safetypoc.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * Android 표준 GPS(LocationManager) 로 마지막 위치를 갖고 있는다. Pleos FusedLocation SDK 는 신청 불가라 표준 경로를 쓴다.
 * 에뮬레이터는 `adb emu geo fix <경도> <위도>` 로 위치를 넣는다. 권한이 없으면 아무것도 하지 않는다.
 */
class GpsLocation(private val context: Context, private val log: (String) -> Unit = {}) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var last: GeoFix? = null
    private var started = false

    private val listener = LocationListener { l: Location -> last = GeoFix(l.latitude, l.longitude, System.currentTimeMillis()) }

    fun lastFix(): GeoFix? = last

    /** 권한이 있으면 1초마다 위치를 받기 시작한다. 여러 번 불러도 한 번만 */
    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { log("위치 권한 없음: 반복 지점 기록 안 함"); return }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { log("GPS 꺼짐: 반복 지점 기록 안 함"); return }
        runCatching { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper()) }
            .onSuccess { started = true; log("GPS 위치 받기 시작") }
            .onFailure { log("GPS 시작 실패: ${it.message}") }
    }

    fun stop() {
        if (!started) return
        manager.removeUpdates(listener); started = false
    }

    companion object { const val INTERVAL_MS = 1_000L }
}
