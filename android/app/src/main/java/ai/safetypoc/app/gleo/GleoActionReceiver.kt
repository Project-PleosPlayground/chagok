package ai.safetypoc.app.gleo

import ai.safetypoc.app.SafetyApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Gleo 가 `component: service` 함수를 부를 때 보내는 브로드캐스트를 받아 바로 답한다 (Pleos Gleo Interface, #3).
 * action 이 함수 이름이다. 우리 함수는 파라미터가 없어 `ACTION_PARAM` 은 읽지 않는다.
 * 앱 객체는 메인 스레드에서만 다루므로 onReceive(메인 스레드) 안에서 읽고 보낸다. Gleo 는 7초 안에 답을 받아야 한다.
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val function = GleoFunction.of(intent.action) ?: return
        val app = context.applicationContext as SafetyApp
        val reply = runCatching { app.gleo.answer(function) }.getOrElse { e ->
            Log.w(SafetyApp.LOG_TAG, "Gleo ${function.name} 답 만들기 실패", e)
            GleoReply.error(function)
        }
        val json = reply.toJson()
        Log.i(SafetyApp.LOG_TAG, "Gleo ${function.name} 응답 $json")
        context.sendBroadcast(
            Intent(ACTION_RESPONSE)
                .putExtra(EXTRA_PACKAGE, context.packageName)
                .putExtra(EXTRA_RESPONSE, json),
        )
    }

    companion object {
        /** Gleo 가 앱의 응답을 받는 브로드캐스트 action */
        const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        const val EXTRA_PACKAGE = "packageName"
        const val EXTRA_RESPONSE = "response"
    }
}
