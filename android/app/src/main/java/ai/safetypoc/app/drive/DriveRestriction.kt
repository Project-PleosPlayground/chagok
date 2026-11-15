package ai.safetypoc.app.drive

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 시스템 주행 규제(CarUxRestrictions). 플랫폼이 Distraction Optimization 을 요구하면 [restricted] 가 true 다.
 * 속도·기어 권한 없이 읽으므로, 권한을 받기 전(첫 실행)에도 주행 여부를 안다.
 * 기어가 P 가 아니면 정차 중에도 규제가 걸린다(에뮬레이터: D·N 단 0 km/h 에서 규제, P 단에서 풀림).
 * 차 서비스가 없거나 연결하지 못하면 false 로 둔다. 그때 주행 판정은 엔진 세션이 맡는다([DriveMode]).
 */
class DriveRestriction(private val context: Context) {
    private val _restricted = MutableStateFlow(false)
    val restricted: StateFlow<Boolean> = _restricted.asStateFlow()

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener(::update)

    /** 차 서비스에 붙어 지금 규제를 읽고, 바뀔 때마다 받는다. 차량(automotive) 기기가 아니면 아무것도 하지 않는다 */
    fun start() {
        if (car != null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        val c = Car.createCar(context) ?: run { Log.w(TAG, "Car 연결 실패: 주행 규제를 읽지 못함"); return }
        val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            ?: run { Log.w(TAG, "주행 규제 서비스 없음"); c.disconnect(); return }
        car = c
        manager = m
        m.registerListener(listener)
        m.currentCarUxRestrictions?.let(::update)
    }

    private fun update(r: CarUxRestrictions) {
        _restricted.value = r.isRequiresDistractionOptimization
    }

    private companion object { const val TAG = "DriveRestriction" }
}
