package kr.woorichurch.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kr.woorichurch.app.BuildConfig
import kr.woorichurch.app.MainActivity
import kr.woorichurch.app.R
import kr.woorichurch.app.WooriApp

const val CHANNEL_ID = "woori"

/** 알림 통로를 만들어 둡니다 (안드로이드 8 이상 필수) */
fun ensureChannel(context: Context) {
    val mgr = context.getSystemService(NotificationManager::class.java) ?: return
    if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
    mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "교회 소식", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "묵상 나눔, 공지사항, 가입 신청 등"
        }
    )
}

/**
 * 이 기기의 알림 토큰을 서버에 등록합니다.
 * Firebase 설정이 없으면 조용히 아무 일도 하지 않습니다.
 */
object PushRegistrar {
    suspend fun register(app: WooriApp): Result<Unit> = runCatching {
        if (!BuildConfig.FIREBASE_READY) error("알림 설정이 아직 되어 있지 않습니다.")
        val token = FirebaseMessaging.getInstance().token.await()
        app.repo.registerPush(token)
        app.prefs.setPushToken(token)
    }

    suspend fun unregister(app: WooriApp): Result<Unit> = runCatching {
        val token = app.prefs.pushToken()
        app.repo.unregisterPush(token)
        if (BuildConfig.FIREBASE_READY) runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
        app.prefs.setPushToken(null)
    }
}

class WooriMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val app = application as? WooriApp ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (app.prefs.token() != null) {
                    app.repo.registerPush(token)
                    app.prefs.setPushToken(token)
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "우리교회"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val link = message.data["link"].orEmpty()
        ensureChannel(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("link", link)
        }
        val pending = PendingIntent.getActivity(
            this, link.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), n)
        }
    }
}
