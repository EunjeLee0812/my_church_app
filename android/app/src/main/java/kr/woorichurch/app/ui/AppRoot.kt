package kr.woorichurch.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kr.woorichurch.app.data.*
import kr.woorichurch.app.ui.admin.*
import kr.woorichurch.app.ui.auth.*
import kr.woorichurch.app.ui.board.*
import kr.woorichurch.app.ui.common.Loading
import kr.woorichurch.app.ui.common.Screen
import kr.woorichurch.app.ui.devotion.*
import kr.woorichurch.app.ui.home.HomeScreen
import kr.woorichurch.app.ui.home.MainTabs
import kr.woorichurch.app.ui.home.RouletteScreen
import kr.woorichurch.app.ui.prayer.PrayerScreen
import kr.woorichurch.app.ui.settings.*

object Route {
    const val LOADING = "loading"
    const val CHURCH_PICK = "churchPick"
    const val CHURCH_REGISTER = "churchRegister"
    const val LOGIN = "login/{churchId}/{churchName}"
    const val CHANGE_PIN = "changePin"
    const val HOME = "home"
    const val WRITE = "write?date={date}"
    const val FEED = "feed"
    const val ATTENDANCE = "attendance"
    const val PERSON = "person/{memberId}"
    const val SEARCH = "search"
    const val PRAYER = "prayer"
    const val NOTICE = "notice"
    const val BULLETIN = "bulletin"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"
    const val PROFILE = "profile"
    const val BIBLE_LINK = "bibleLink"
    const val ZOOM = "zoom"
    const val MEMBERS = "members"
    const val AUDIT = "audit"
    const val CELL_STATUS = "cellStatus"
    const val SUGGESTIONS = "suggestions"
    const val PLATFORM = "platform"
    const val EXPORT = "export"
    const val ROULETTE = "roulette"

    fun login(id: Long, name: String) = "login/$id/${name.ifBlank { "-" }}"
    fun person(id: Long) = "person/$id"
    /** 새로 쓰기는 write, 이미 쓴 글 고치기는 그 날짜를 넘깁니다 */
    fun write(date: String? = null) = if (date == null) "write" else "write?date=$date"
    fun settings(section: String) = "settings/$section"
}

/** 앱 전체가 함께 쓰는 상태 */
class AppState(val repo: Repo) {
    var me by mutableStateOf<Me?>(null)
    var home by mutableStateOf<HomeData?>(null)
    var progress by mutableStateOf<Progress?>(null)
    var cells by mutableStateOf<List<Cell>>(emptyList())
    var loading by mutableStateOf(true)

    /**
     * 교회의 모든 셀에 속한 사람(목사님 등)은 '우리 셀'과 '전체'가 같으므로
     * 두 칸으로 나누지 않고 전체만 보여 줍니다.
     */
    val inAllCells: Boolean
        get() {
            val m = me ?: return false
            return cells.isNotEmpty() && cells.all { m.cellIds.contains(it.id) }
        }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun apply(b: Bootstrap) {
        me = b.me; home = b.home; progress = b.progress; cells = b.cells
    }

    /** 저장해 둔 값을 즉시 꺼내 씁니다 (없으면 false) */
    suspend fun loadCached(): Boolean {
        val raw = repo.prefs.loadBoot() ?: return false
        return runCatching { apply(json.decodeFromString(Bootstrap.serializer(), raw)); true }
            .getOrDefault(false)
    }

    /** 서버에서 새로 받아 화면과 캐시를 모두 갱신합니다 */
    suspend fun refresh(): Bootstrap? {
        val b = runCatching { repo.bootstrap() }.getOrNull() ?: return null
        apply(b)
        runCatching { repo.prefs.saveBoot(json.encodeToString(Bootstrap.serializer(), b)) }
        return b
    }

    suspend fun refreshMe(): Me? = refresh()?.me

    /**
     * 지금 있는 곳에서 다음에 누를 만한 화면의 데이터를 미리 받아 둡니다.
     * (실제로 그 화면을 열면 기다리지 않고 바로 보입니다)
     */
    fun prefetchAround(where: String) {
        val m = me ?: return
        val today = kr.woorichurch.app.util.D.today()
        val scope = if (inAllCells) "all" else "cell"
        val firstCell = m.cellIds.firstOrNull()
        when (where) {
            "home" -> {
                repo.prefetchFeed(today, scope)          // 나눔광장 탭
                repo.prefetchMyEntry(today)              // 묵상 나누기 / 고치기
                repo.prefetchPerson(m.id)                // 내 기록
                repo.prefetchNotices()                   // 알림 탭
                repo.prefetchPrayers(scope)              // 기도제목 나누기
                if (m.canPickCells) repo.prefetchCells()
            }
            "more" -> {
                repo.prefetchPrayers(scope)
                repo.prefetchAttendance(firstCell)
            }
            "alerts" -> {
                repo.prefetchNotices()
                repo.prefetchBulletins()
            }
            "feed" -> {
                repo.prefetchAttendance(firstCell)       // 출석표 보기
                repo.prefetchMyFeed(m.id)                // 나의 나눔
                repo.prefetchFeed(today, if (scope == "cell") "all" else "cell")
                repo.prefetchFeed(java.time.LocalDate.now().minusDays(1).toString(), scope)   // 어제
            }
            "settings" -> repo.prefetchPushConfig()
        }
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
        } else if (state.loadCached() && state.me?.mustChangePin != true) {
            // 저장된 값으로 곧바로 홈을 띄우고, 뒤에서 조용히 새로 받습니다
            state.loading = false
            nav.navigate(Route.HOME) { popUpTo(Route.LOADING) { inclusive = true } }
            state.refresh()
        } else {
            val me = state.refresh()?.me
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
            runCatching { repo.prefs.clearBoot() }
            val me = state.refresh()?.me
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
            runCatching { repo.prefs.clearBoot() }
            state.me = null; state.home = null; state.progress = null; state.cells = emptyList()
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
            // 시니어 모드면 예전의 큰 글씨 홈, 아니면 아래 탭이 있는 새 화면
            val senior by repo.prefs.seniorMode.collectAsState(initial = null)
            when (senior) {
                null -> Screen("") { }
                true -> HomeScreen(
                    state = state,
                    onNavigate = { nav.navigate(it) },
                    onSignOut = { signOut() },
                )
                false -> MainTabs(
                    state = state,
                    onNavigate = { nav.navigate(it) },
                    onSignOut = { signOut() },
                )
            }
        }
        composable(Route.ROULETTE) {
            RouletteScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable(
            Route.WRITE,
            arguments = listOf(navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            val editDate = entry.arguments?.getString("date")
            WriteScreen(
                state = state, editDate = editDate,
                onBack = { nav.popBackStack() },
                // 새 글은 홈으로, 고친 글은 보던 화면으로 돌아갑니다
                onSaved = { if (editDate == null) goHome() else nav.popBackStack() },
            )
        }
        composable(Route.FEED) {
            FeedScreen(
                state = state,
                onBack = { nav.popBackStack() }, onHome = { goHome() },
                onPerson = { nav.navigate(Route.person(it)) },
                onAttendance = { nav.navigate(Route.ATTENDANCE) },
                onSearch = { nav.navigate(Route.SEARCH) },
                onEdit = { nav.navigate(Route.write(it)) },
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
            PersonScreen(
                state = state, memberId = id, onBack = { nav.popBackStack() }, onHome = { goHome() },
                onEdit = { nav.navigate(Route.write(it)) },
            )
        }
        composable(Route.SEARCH) {
            SearchScreen(
                state = state, onBack = { nav.popBackStack() }, onHome = { goHome() },
                onPerson = { nav.navigate(Route.person(it)) },
                onEdit = { nav.navigate(Route.write(it)) },
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
        composable(Route.SETTINGS_SECTION) { entry ->
            SettingsSectionScreen(
                state = state, section = entry.arguments?.getString("section").orEmpty(),
                onBack = { nav.popBackStack() }, onHome = { goHome() },
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
        composable(Route.EXPORT) {
            ExportScreen(state = state, onBack = { nav.popBackStack() })
        }
    }
}
