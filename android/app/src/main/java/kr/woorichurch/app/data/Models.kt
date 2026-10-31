package kr.woorichurch.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── 직분 / 권한 / 나눔 범위 ────────────────────────────────────────────────
object Titles {
    val ALL = listOf("목사", "사모", "장로", "안수집사", "권사", "집사", "성도")
}

object Roles {
    const val OWNER = "owner"    // 관리자 — 교회를 등록한 분. 담임목사님과 같은 전체 권한 (여러 명 가능)
    const val ADMIN = "admin"    // 부관리자 — 성도·셀 관리, 가입 승인, 공지·주보
    const val LEADER = "leader"  // 셀장
    const val MEMBER = "member"  // 일반 성도

    fun label(role: String) = when (role) {
        OWNER -> "관리자"
        ADMIN -> "부관리자"
        LEADER -> "셀장"
        else -> "성도"
    }
}

object Scopes {
    const val OWN_CELL = "own_cell"
    const val ALL_CELLS = "all_cells"
    fun label(s: String) = if (s == ALL_CELLS) "셀 선택 가능" else "본인 셀만"
}

// ── 공통 ──────────────────────────────────────────────────────────────────
@Serializable data class Ok(val ok: Boolean = true, val message: String? = null)
@Serializable data class ErrorBody(val error: String = "알 수 없는 오류")

// ── 로그인 전 ──────────────────────────────────────────────────────────────
@Serializable data class Church(
    val id: Long,
    val name: String,
    val address: String? = null,
    val denomination: String? = null,
)
@Serializable data class ChurchList(val churches: List<Church> = emptyList())
@Serializable data class LoginResult(val token: String, val mustChangePin: Boolean = false)
@Serializable data class SignupResult(val ok: Boolean = true, val status: String = "", val message: String = "")

// ── 나 ────────────────────────────────────────────────────────────────────
@Serializable data class Me(
    val id: Long,
    val name: String,
    val churchId: Long,
    val churchName: String,
    val title: String = "성도",
    val role: String = Roles.MEMBER,
    val shareScope: String = Scopes.OWN_CELL,
    val cellId: Long? = null,
    /** 화면 표시용 짧은 이름 — 예: '2구역', '1구역 외 2', '전체 구역' */
    val cellName: String? = null,
    /** 속한 구역 이름 전체 */
    val cellNames: String? = null,
    val cellIds: List<Long> = emptyList(),
    val zoomUrl: String? = null,
    val mustChangePin: Boolean = false,
    val isPlatformAdmin: Boolean = false,
) {
    val canManage get() = role == Roles.OWNER || role == Roles.ADMIN
    val isOwner get() = role == Roles.OWNER
    val canPickCells get() = shareScope == Scopes.ALL_CELLS
}

// ── 홈 ────────────────────────────────────────────────────────────────────
@Serializable data class NoticeBrief(
    val id: Long, val title: String? = null, val body: String,
    val createdAt: String = "", val unread: Boolean = false,
)
@Serializable data class BulletinBrief(
    val id: Long, val title: String? = null,
    @SerialName("week_of") val weekOf: String = "",
    val mime: String = "", val size: Long = 0,
)
@Serializable data class HomeData(
    val zoomUrl: String? = null,
    val latestNotice: NoticeBrief? = null,
    val unreadNoticeCount: Int = 0,
    val latestBulletin: BulletinBrief? = null,
    val pendingApprovals: Int = 0,
    val wroteToday: Boolean = false,
)

// ── 셀 ────────────────────────────────────────────────────────────────────
@Serializable data class Cell(
    val id: Long,
    val name: String,
    @SerialName("leader_id") val leaderId: Long? = null,
    @SerialName("leader_name") val leaderName: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
)
@Serializable data class CellList(val cells: List<Cell> = emptyList())

// ── 성도 ──────────────────────────────────────────────────────────────────
@Serializable data class Member(
    val id: Long,
    val name: String,
    val title: String = "성도",
    val role: String = Roles.MEMBER,
    @SerialName("share_scope") val shareScope: String = Scopes.OWN_CELL,
    @SerialName("cell_id") val cellId: Long? = null,
    @SerialName("cell_name") val cellName: String? = null,
    val cellIds: List<Long> = emptyList(),
    val status: String = "active",
    @SerialName("created_at") val createdAt: String = "",
)
@Serializable data class MemberList(
    val members: List<Member> = emptyList(),
    val canEdit: Boolean = false,
    val canGrantRole: Boolean = false,
)
@Serializable data class ResetPinResult(val ok: Boolean = true, val tempPin: String = "")
@Serializable data class BatchResult(val ok: Boolean = true, val count: Int = 0)

// ── 묵상 ──────────────────────────────────────────────────────────────────
@Serializable data class EntryImage(val id: Long, val url: String)
@Serializable data class ShareLink(val ok: Boolean = true, val url: String = "")
@Serializable data class UploadImageResult(val ok: Boolean = true, val id: Long = 0, val url: String = "")

@Serializable data class Entry(
    val id: Long,
    val date: String,
    val title: String? = null,
    val body: String = "",
    @SerialName("is_public") val isPublicRaw: Int = 1,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("member_id") val memberId: Long = 0,
    @SerialName("member_name") val memberName: String = "",
    @SerialName("member_title") val memberTitle: String = "",
    @SerialName("cell_name") val cellName: String? = null,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("liked") val likedRaw: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    val images: List<EntryImage> = emptyList(),
    /** 내 글을 불러올 때만: 이 글을 나눈 셀들 */
    val cellIds: List<Long> = emptyList(),
) {
    val isPublic get() = isPublicRaw == 1
    val liked get() = likedRaw > 0
}
@Serializable data class EntryList(val entries: List<Entry> = emptyList(), val scope: String = "cell")
@Serializable data class SaveEntryResult(val ok: Boolean = true, val id: Long = 0, val cellCount: Int = 0)

@Serializable data class Person(
    val id: Long, val name: String, val title: String = "성도",
    @SerialName("cell_id") val cellId: Long? = null,
)
@Serializable data class PersonEntries(
    val person: Person, val total: Int = 0, val entries: List<Entry> = emptyList(),
)

@Serializable data class AttendancePerson(
    val id: Long, val name: String, val title: String = "성도",
    @SerialName("cell_name") val cellName: String? = null,
    val dates: List<String> = emptyList(),
)
@Serializable data class Attendance(
    val from: String = "", val to: String = "",
    val year: Int = 0, val quarter: Int = 1,
    val people: List<AttendancePerson> = emptyList(),
)

@Serializable data class QuarterProgress(val quarter: Int, val done: Int, val total: Int, val percent: Int)
@Serializable data class AnnualProgress(val done: Int, val total: Int, val percent: Int)
@Serializable data class Progress(
    val year: Int = 0,
    val quarters: List<QuarterProgress> = emptyList(),
    val annual: AnnualProgress = AnnualProgress(0, 0, 0),
)

@Serializable data class LikeResult(val liked: Boolean = false, val count: Int = 0)

@Serializable data class Comment(
    val id: Long, val body: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("member_id") val memberId: Long = 0,
    @SerialName("member_name") val memberName: String = "",
    @SerialName("member_title") val memberTitle: String = "",
)
@Serializable data class CommentList(val comments: List<Comment> = emptyList(), val myId: Long = 0)

// ── 셀 현황판 ──────────────────────────────────────────────────────────────
@Serializable data class CellStatusPerson(
    val id: Long, val name: String, val title: String = "성도",
    @SerialName("wrote_today") val wroteTodayRaw: Int = 0,
    @SerialName("week_count") val weekCount: Int = 0,
    @SerialName("last_date") val lastDate: String? = null,
) { val wroteToday get() = wroteTodayRaw > 0 }
@Serializable data class CellStatus(
    val cellName: String? = null, val date: String = "",
    val people: List<CellStatusPerson> = emptyList(),
)

// ── 기도제목 ───────────────────────────────────────────────────────────────
@Serializable data class Prayer(
    val id: Long,
    val body: String,
    @SerialName("answered") val answeredRaw: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("member_id") val memberId: Long = 0,
    @SerialName("member_name") val memberName: String = "",
    @SerialName("member_title") val memberTitle: String = "",
    @SerialName("amen_count") val amenCount: Int = 0,
    @SerialName("amened") val amenedRaw: Int = 0,
) {
    val answered get() = answeredRaw == 1
    val amened get() = amenedRaw > 0
}
@Serializable data class PrayerList(val prayers: List<Prayer> = emptyList(), val scope: String = "cell")

// ── 공지 · 주보 ────────────────────────────────────────────────────────────
@Serializable data class Notice(
    val id: Long, val title: String? = null, val body: String,
    val pinned: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("read_flag") val readFlag: Int = 0,
    @SerialName("read_count") val readCount: Int = 0,
) { val unread get() = readFlag == 0 }
@Serializable data class NoticeList(val notices: List<Notice> = emptyList(), val canEdit: Boolean = false)

@Serializable data class Bulletin(
    val id: Long, val title: String? = null,
    @SerialName("week_of") val weekOf: String = "",
    val mime: String = "", val size: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    val uploader: String? = null,
) { val isPdf get() = mime.contains("pdf") }
@Serializable data class BulletinList(val bulletins: List<Bulletin> = emptyList(), val canEdit: Boolean = false)

// ── 변경 기록 ──────────────────────────────────────────────────────────────
@Serializable data class AuditEntry(
    val id: Long,
    @SerialName("actor_name") val actorName: String? = null,
    val action: String = "",
    @SerialName("target_name") val targetName: String? = null,
    val detail: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)
@Serializable data class AuditList(val logs: List<AuditEntry> = emptyList())

@Serializable data class InviteCode(val inviteCode: String = "")

// ── 알림 ──────────────────────────────────────────────────────────────────
@Serializable data class PushConfig(
    val vapidPublicKey: String? = null,
    val fcmAvailable: Boolean = false,
    val registered: Boolean = false,
    val notifyShare: Boolean = true,
    val notifyBoard: Boolean = true,
    val notifyAdmin: Boolean = true,
)
@Serializable data class AppNotification(
    val id: Long,
    val title: String,
    val body: String,
    val link: String? = null,
    @SerialName("read_flag") val readFlag: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)
@Serializable data class NotificationList(val notifications: List<AppNotification> = emptyList())

// ── 플랫폼 운영자 ───────────────────────────────────────────────────────────
@Serializable data class ChurchApplication(
    val id: Long, val name: String,
    val address: String? = null, val phone: String? = null,
    val denomination: String? = null,
    @SerialName("pastor_name") val pastorName: String? = null,
    val seminary: String? = null, val homepage: String? = null, val note: String? = null,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("invited_by_name") val invitedByName: String? = null,
    @SerialName("applicant_name") val applicantName: String? = null,
)
@Serializable data class ChurchApplicationList(val churches: List<ChurchApplication> = emptyList())

@Serializable data class Suggestion(
    val id: Long,
    val name: String? = null,
    val body: String,
    @SerialName("is_read") val isRead: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)
@Serializable data class SuggestionList(val suggestions: List<Suggestion> = emptyList())

/** 홈에 필요한 것을 한 번에 받는 응답 — 첫 화면을 빨리 띄우기 위함 */
@Serializable data class Bootstrap(
    val me: Me,
    val home: HomeData = HomeData(),
    val cells: List<Cell> = emptyList(),
    val progress: Progress = Progress(),
)

@Serializable data class ExportEntry(
    val date: String,
    val title: String? = null,
    val body: String = "",
    @SerialName("member_name") val memberName: String? = null,
    @SerialName("member_title") val memberTitle: String? = null,
)
@Serializable data class ExportData(
    val scope: String = "mine",
    val churchName: String = "",
    val name: String = "",
    val entries: List<ExportEntry> = emptyList(),
)
