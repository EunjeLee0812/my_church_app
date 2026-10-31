package kr.woorichurch.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import kr.woorichurch.app.ui.AppRoot
import kr.woorichurch.app.ui.theme.WooriTheme

class MainActivity : ComponentActivity() {

    // 화면이 뜨기 전에 미리 등록해 두어야 합니다
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermission()
        val app = application as WooriApp
        setContent {
            val scale by app.prefs.fontScale.collectAsState(initial = 0.85f)
            WooriTheme(fontScale = scale) {
                AppRoot(repo = app.repo)
            }
        }
    }

    /** 안드로이드 13부터는 알림을 받으려면 사용자가 한 번 허락해야 합니다 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
