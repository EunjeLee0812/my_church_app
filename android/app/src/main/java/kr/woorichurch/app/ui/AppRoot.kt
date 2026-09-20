package kr.woorichurch.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kr.woorichurch.app.data.Me
import kr.woorichurch.app.data.Repo
import kr.woorichurch.app.ui.admin.*
import kr.woorichurch.app.ui.auth.*
import kr.woorichurch.app.ui.board.*
import kr.woorichurch.app.ui.common.Loading
import kr.woorichurch.app.ui.common.Screen
import kr.woorichurch.app.ui.devotion.*
import kr.woorichurch.app.ui.home.HomeScreen
import kr.woorichurch.app.ui.prayer.PrayerScreen
import kr.woorichurch.app.ui.settings.*

object Route {
    const val LOADING = "loading"
    const val CHURCH_PICK = "churchPick"
    const val CHURCH_REGISTER = "churchRegister"
    const val LOGIN = "login/{churchId}/{churchName}"
    const val CHANGE_PIN = "changePin"
    const val HOME = "home"
    const val WRITE = "write"
    const val FEED = "feed"
    const val ATTENDANCE = "attendance"
    const val PERSON = "person/{memberId}"
    const val SEARCH = "search"
    const val PRAYER = "prayer"
    const val NOTICE = "notice"
    const val BULLETIN = "bulletin"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val BIBLE_LINK = "bibleLink"
    const val ZOOM = "zoom"
    const val MEMBERS = "members"
    const val APPROVALS = "approvals"
    const val AUDIT = "audit"
    const val CELL_STATUS = "cellStatus"
    const val SUGGESTIONS = "suggestions"
    const val PLATFORM = "platform"

    fun login(id: Long, name: String) = "login/$id/${name.ifBlank { "-" }}"
    fun person(id: Long) = "person/$id"
}

/** 앱 전체가 함께 쓰는 상태 */
class AppState(val repo: Repo) {
    var me by mutableStateOf<Me?>(null)
    var loading by mutableStateOf(true)

    suspend fun refreshMe(): Me? {
        me = runCatching { repo.me() }.getOrNull()
        return me
    }
}

@Composable
fun AppRoot(repo: Repo) {
    val nav = rememberNavController()
    val state = remember { AppState(repo) }
    val scope = rememberCoroutineScope()

    // 앱을 켜면 저장된 토큰으로 자동 로그인 (시니어가 매번 입력하지 않도록)
    LaunchedEffect(Unit) {
        val token = repo.prefs.token()
        if (token == null) {
            state.loading = false
            nav.navigate(Route.CHURCH_PICK) { popUpTo(Route.LOADING) { inclusive = true } }
        } else {
            val me = state.refreshMe()
            state.loading = false
            if (me == null) {
                repo.prefs.setToken(null)
                nav.navigate(Route.CHURCH_PICK) { popUpTo(Route.LOADING) { inclusive = true } }
            } else if (me.mustChangePin) {
                nav.navigate(Route.CHANGE_PIN) { popUpTo(Route.LOADING) { inclusive = true } }
            } else {
                nav.navigate(Route.HOME) { popUpTo(Route.LOADING) { inclusive = true } }
            }
        }
    }

    fun goHome() = nav.navigate(Route.HOME) { popUpTo(Route.HOME) { inclusive = true } }

    fun afterLogin() {
        scope.launch {
            val me = state.refreshMe()
            if (me?.mustChangePin == true) {
                nav.navigate(Route.CHANGE_PIN) { popUpTo(0) }
            } else {
                nav.navigate(Route.HOME) { popUpTo(0) }
            }
        }
    }

    fun signOut() {
        scope.launch {
            repo.logout()
            state.me = null
            nav.navigate(Route.CHURCH_PICK) { popUpTo(0) }
        }
    }

    NavHost(navController = nav, startDestination = Route.LOADING) {

        composable(Route.LOADING) {
            Screen("우리교회") { Loading("잠시만 기다려 주세요") }
        }

        // ── 로그인 전 ────────────────────────────────────────────────────
        composable(Route.CHURCH_PICK) {
            ChurchPickScreen(
                repo = repo,
                onPicked = { id, name -> nav.navigate(Route.login(id, name)) },
                onRegisterChurch = { nav.navigate(Route.CHURCH_REGISTER) },
            )
        }
        composable(Route.CHURCH_REGISTER) {
            ChurchRegisterScreen(repo = repo, onBack = { nav.popBackStack() })
        }
        composable(Route.LOGIN) { entry ->
            val id = entry.arguments?.getString("churchId")?.toLongOrNull() ?: 0L
            val name = entry.arguments?.getString("churchName").orEmpty()
            LoginScreen(
                repo = repo, churchId = id, churchName = if (name == "-") "" else name,
                onBack = { nav.popBackStack() },
                onLoggedIn = { afterLogin() },
            )
        }
        composable(Route.CHANGE_PIN) {
            ChangePinScreen(repo = repo, onDone = { afterLogin() })
        }

        // ── 로그인 후 ────────────────────────────────────────────────────
        composable(Route.HOME) {
            HomeScreen(
                state = state,
                onNavigate = { nav.navigate(it) },
                onSignOut = { signOut() },
            )
        }
        composable(Route.WRITE) {
            WriteScreen(state = state, onBack = { nav.popBackStack() }, onSaved = { goHome() })
        }
        composable(Route.FEED) {
            FeedScreen(
                state = state,
                onBack = { nav.popBackStack() }, onHome = { goHome() },
                onPerson = { nav.navigate(Route.person(it)) },
                onAttendance = { nav.navigate(Route.ATTENDANCE) },
                onSearch = { nav.navigate(Route.SEARCH) },
            )
        }
        composable(Route.ATTENDANCE) {
            AttendanceScreen(
                state = state, onBack = { nav.popBackStack() }, onHome = { goHome() },
                onPerson = { nav.navigate(Route.person(it)) },
            )
        }
        composable(Route.PERSON) { entry ->
            val id = entry.arguments?.getString("memberId")?.toLongOrNull() ?: 0L
            PersonScreen(state = state, memberId = id, onBack = { nav.popBackStack() }, onHome = { goHome() })
        }
        composable(Route.SEARCH) {
            SearchScreen(
                state = state, onBack = { nav.popBackStack() }, onHome = { goHome() },
                onPerson = { nav.navigate(Route.person(it)) },
            )
        }
        composable(Route.PRAYER) {
            PrayerScreen(state = state, onBack = { nav.popBackStack() }, onHome = { goHome() })
        }
        composable(Route.NOTICE) {
            NoticeScreen(state = state, onBack = { nav.popBackStack() }, onHome = { goHome() })
        }
        composable(Route.BULLETIN) {
            BulletinScreen(state = state, onBack = { nav.popBackStack() }, onHome = { goHome() })
        }

        // ── 설정 ────────────────────────────────────────────────────────
        composable(Route.SETTINGS) {
            SettingsScreen(
                state = state, onBack = { nav.popBackStack() }, onHome = { goHome() },
                onNavigate = { nav.navigate(it) }, onSignOut = { signOut() },
            )
        }
        composable(Route.PROFILE) {
            ProfileScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(Route.BIBLE_LINK) {
            BibleLinkScreen(repo = repo, onBack = { nav.popBackStack() })
        }
        composable(Route.ZOOM) {
            ZoomSettingScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(Route.MEMBERS) {
            MembersScreen(state = state, onBack = { nav.popBackStack() }, onHome = { goHome() })
        }
        composable(Route.APPROVALS) {
            ApprovalsScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(Route.AUDIT) {
            AuditScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(Route.CELL_STATUS) {
            CellStatusScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(Route.SUGGESTIONS) {
            SuggestionsScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(Route.PLATFORM) {
            PlatformScreen(state = state, onBack = { nav.popBackStack() })
        }
    }
}
