package kr.woorichurch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import kr.woorichurch.app.ui.AppRoot
import kr.woorichurch.app.ui.theme.WooriTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as WooriApp
        setContent {
            val scale by app.prefs.fontScale.collectAsState(initial = 1f)
            WooriTheme(fontScale = scale) {
                AppRoot(repo = app.repo)
            }
        }
    }
}
