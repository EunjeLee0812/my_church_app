package kr.woorichurch.app.data

/** 화면에서 부르는 서버 기능 모음. 주소·파라미터는 전부 여기 한 곳에 모아 둡니다. */
class Repo(val api: Api, val prefs: Prefs) {

    // ── 로그인 전 ──────────────────────────────────────────────────────────
    suspend fun searchChurches(q: String) =
        api.get("/api/churches/search", ChurchList.serializer(), "q" to q).churches

    /** 교회 등록 — 교회 이름 · 주소 · 교단 · 담임목사 성함 + 신청하시는 분(관리자) 이름과 비밀번호 */
    suspend fun registerChurch(
        churchName: String, address: String, denomination: String,
        pastorName: String, applicantName: String, pinNumber: String,
    ) = api.post("/api/churches/register", SignupResult.serializer(), mapOf(
        "churchName" to churchName, "address" to address,
        "denomination" to denomination, "pastorName" to pastorName,
        "applicantName" to applicantName, "pinNumber" to pinNumber,
    ))

    suspend fun signup(churchId: Long, name: String, pin: String) =
        api.post("/api/members/signup", SignupResult.serializer(), mapOf(
            "churchId" to churchId, "name" to name, "pinNumber" to pin,
        ))

    suspend fun login(churchId: Long, name: String, pin: String): LoginResult {
        val r = api.post("/api/auth/login", LoginResult.serializer(), mapOf(
            "churchId" to churchId, "name" to name, "pinNumber" to pin,
        ))
        prefs.setToken(r.token)
        prefs.rememberName(name)
        return r
    }

    suspend fun loginByEmail(email: String, password: String): LoginResult {
        val r = api.post("/api/auth/login", LoginResult.serializer(), mapOf(
            "email" to email, "password" to password,
        ))
        prefs.setToken(r.token)
        return r
    }

    suspend fun logout() {
        runCatching { api.post("/api/auth/logout", Ok.serializer()) }
        prefs.setToken(null)
    }

    // ── 나 ────────────────────────────────────────────────────────────────
    suspend fun me() = api.get("/api/me", Me.serializer())
    suspend fun updateMe(name: String? = null, currentPin: String? = null, newPin: String? = null) =
        api.post("/api/me/update", Ok.serializer(), mapOf(
            "name" to name, "currentPin" to currentPin, "newPin" to newPin,
        ))

    suspend fun home() = api.get("/api/home", HomeData.serializer())

    /** 홈에 필요한 것을 한 번에 — 서버 왕복을 1번으로 줄입니다 */
    suspend fun bootstrap() = api.get("/api/bootstrap", Bootstrap.serializer())

    /** 묵상 기록 전체 내려받기 (scope: mine | church) */
    suspend fun exportEntries(scope: String) =
        api.get("/api/entries/export", ExportData.serializer(), "scope" to scope)

    // ── 셀 ────────────────────────────────────────────────────────────────
    suspend fun cells() = api.get("/api/cells", CellList.serializer()).cells
    suspend fun saveCell(id: Long?, name: String) =
        api.post("/api/cells/save", Ok.serializer(), mapOf("id" to id, "name" to name))
    suspend fun setCellLeader(cellId: Long, memberId: Long?) =
        api.post("/api/cells/leader", Ok.serializer(), mapOf("cellId" to cellId, "memberId" to memberId))
    suspend fun deleteCell(id: Long) =
        api.post("/api/cells/delete", Ok.serializer(), mapOf("id" to id))
    suspend fun cellStatus(cellId: Long?) =
        api.get("/api/cells/status", CellStatus.serializer(), "cellId" to cellId)

    // ── 성도 ──────────────────────────────────────────────────────────────
    suspend fun members() = api.get("/api/members", MemberList.serializer())
    suspend fun updateMember(
        memberId: Long, title: String? = null, cellIds: List<Long>? = null,
        role: String? = null, shareScope: String? = null, status: String? = null,
    ) = api.post("/api/members/update", Ok.serializer(), buildMap {
        put("memberId", memberId)
        title?.let { put("title", it) }
        cellIds?.let { put("cellIds", it) }        // 여러 셀을 한꺼번에 지정
        role?.let { put("role", it) }
        shareScope?.let { put("shareScope", it) }
        status?.let { put("status", it) }
    })
    suspend fun approveMember(memberId: Long, approve: Boolean, cellIds: List<Long> = emptyList()) =
        api.post("/api/members/approve", Ok.serializer(), mapOf(
            "memberId" to memberId, "approve" to approve, "cellIds" to cellIds,
        ))
    /** 일괄 승인 — 사람마다 고른 셀과 함께 */
    suspend fun approveAll(items: List<Pair<Long, List<Long>>>) =
        api.post("/api/members/approve-batch", BatchResult.serializer(), mapOf(
            "items" to items.map { (id, cells) -> mapOf("memberId" to id, "cellIds" to cells) },
        ))
    suspend fun resetPin(memberId: Long) =
        api.post("/api/members/reset-pin", ResetPinResult.serializer(), mapOf("memberId" to memberId))

    suspend fun auditLog() = api.get("/api/audit", AuditList.serializer()).logs

    // ── 교회 설정 ──────────────────────────────────────────────────────────
    suspend fun setZoom(url: String) =
        api.post("/api/settings/zoom", Ok.serializer(), mapOf("zoomUrl" to url))
    suspend fun inviteCode() = api.get("/api/settings/invite", InviteCode.serializer()).inviteCode

    // ── 묵상 ──────────────────────────────────────────────────────────────
    suspend fun myEntries(from: String? = null, to: String? = null) =
        api.get("/api/entries/mine", EntryList.serializer(), "from" to from, "to" to to).entries

    suspend fun saveEntry(
        date: String, title: String, body: String, isPublic: Boolean,
        cellIds: List<Long>? = null, allCells: Boolean = false,
        imageIds: List<Long> = emptyList(),
    ) = api.post("/api/entries/save", SaveEntryResult.serializer(), mapOf(
        "date" to date, "title" to title, "body" to body,
        "isPublic" to isPublic, "cellIds" to cellIds, "allCells" to allCells,
        "imageIds" to imageIds,
    ))

    suspend fun uploadEntryImage(bytes: ByteArray, mime: String) = api.uploadImage(bytes, mime)
    fun imageUrl(path: String) = api.absolute(path)

    suspend fun deleteEntry(id: Long) =
        api.post("/api/entries/delete", Ok.serializer(), mapOf("id" to id))

    suspend fun feed(date: String, scope: String) =
        api.get("/api/entries/feed", EntryList.serializer(), "date" to date, "scope" to scope).entries

    /** 이번 달을 맨 오른쪽으로 최근 12개월 */
    suspend fun attendanceYear(scope: String, cellId: Long? = null) =
        api.get("/api/entries/attendance", Attendance.serializer(),
            "scope" to scope, "range" to "year", "cellId" to cellId)

    /** 내 글 공유 링크 (교회 밖 사람도 그 글 하나만 볼 수 있음) */
    suspend fun shareLink(entryId: Long) =
        api.post("/api/entries/share", ShareLink.serializer(), mapOf("entryId" to entryId)).url

    suspend fun personEntries(memberId: Long, month: String? = null, day: String? = null) =
        api.get("/api/entries/person", PersonEntries.serializer(),
            "memberId" to memberId, "month" to month, "day" to day)

    suspend fun search(q: String, scope: String, memberId: Long? = null, from: String? = null, to: String? = null) =
        api.get("/api/entries/search", EntryList.serializer(),
            "q" to q, "scope" to scope, "memberId" to memberId, "from" to from, "to" to to).entries

    suspend fun progress(year: Int? = null) =
        api.get("/api/entries/progress", Progress.serializer(), "year" to year)

    suspend fun toggleLike(entryId: Long) =
        api.post("/api/entries/like", LikeResult.serializer(), mapOf("entryId" to entryId))

    suspend fun comments(entryId: Long) =
        api.get("/api/entries/comments", CommentList.serializer(), "entryId" to entryId)

    suspend fun addComment(entryId: Long, body: String) =
        api.post("/api/entries/comment", Ok.serializer(), mapOf("entryId" to entryId, "body" to body))

    suspend fun deleteComment(id: Long) =
        api.post("/api/entries/comment/delete", Ok.serializer(), mapOf("id" to id))

    // ── 기도제목 ───────────────────────────────────────────────────────────
    suspend fun prayers(scope: String) =
        api.get("/api/prayers", PrayerList.serializer(), "scope" to scope).prayers
    suspend fun savePrayer(body: String, cellIds: List<Long>? = null, allCells: Boolean = false) =
        api.post("/api/prayers/save", Ok.serializer(), mapOf(
            "body" to body, "cellIds" to cellIds, "allCells" to allCells,
        ))
    suspend fun amen(id: Long) = api.post("/api/prayers/amen", LikeResult.serializer(), mapOf("id" to id))
    suspend fun markAnswered(id: Long, answered: Boolean) =
        api.post("/api/prayers/answered", Ok.serializer(), mapOf("id" to id, "answered" to answered))
    suspend fun deletePrayer(id: Long) =
        api.post("/api/prayers/delete", Ok.serializer(), mapOf("id" to id))

    // ── 공지 · 주보 ────────────────────────────────────────────────────────
    suspend fun notices() = api.get("/api/notices", NoticeList.serializer())
    suspend fun saveNotice(id: Long?, title: String, body: String, pinned: Boolean) =
        api.post("/api/notices/save", Ok.serializer(), mapOf(
            "id" to id, "title" to title, "body" to body, "pinned" to pinned,
        ))
    suspend fun readNotice(id: Long) =
        api.post("/api/notices/read", Ok.serializer(), mapOf("id" to id))
    suspend fun deleteNotice(id: Long) =
        api.post("/api/notices/delete", Ok.serializer(), mapOf("id" to id))

    suspend fun bulletins() = api.get("/api/bulletins", BulletinList.serializer())
    suspend fun uploadBulletin(bytes: ByteArray, mime: String, title: String, weekOf: String) =
        api.upload(bytes, mime, title, weekOf)
    suspend fun bulletinBytes(id: Long) = api.download(id)
    suspend fun deleteBulletin(id: Long) =
        api.post("/api/bulletins/delete", Ok.serializer(), mapOf("id" to id))

    // ── 건의함 ────────────────────────────────────────────────────────────
    // ── 알림 ──────────────────────────────────────────────────────────────
    suspend fun registerPush(token: String) =
        api.post("/api/push/register", Ok.serializer(), mapOf("kind" to "fcm", "token" to token))
    suspend fun unregisterPush(token: String?) =
        api.post("/api/push/unregister", Ok.serializer(), mapOf("token" to token))
    suspend fun pushConfig() = api.get("/api/push/config", PushConfig.serializer())
    suspend fun pushPrefs(share: Boolean? = null, board: Boolean? = null, admin: Boolean? = null) =
        api.post("/api/push/prefs", Ok.serializer(), buildMap {
            share?.let { put("share", it) }; board?.let { put("board", it) }; admin?.let { put("admin", it) }
        })
    suspend fun notifications() =
        api.get("/api/notifications", NotificationList.serializer()).notifications

    suspend fun sendSuggestion(body: String) =
        api.post("/api/suggestions", Ok.serializer(), mapOf("body" to body))

    suspend fun suggestions() =
        api.get("/api/suggestions", SuggestionList.serializer()).suggestions

    // ── 미리 가져오기 ─────────────────────────────────────────────────────
    // 지금 화면에서 다음에 누를 만한 화면의 데이터를 뒤에서 미리 받아 둡니다.
    // 주소·파라미터는 위의 함수들과 똑같아야 캐시가 맞아떨어집니다.
    fun prefetchFeed(date: String, scope: String) =
        api.prefetch("/api/entries/feed", "date" to date, "scope" to scope)
    fun prefetchMyFeed(memberId: Long) =
        api.prefetch("/api/entries/person", "memberId" to memberId, "month" to null, "day" to null)
    fun prefetchPerson(memberId: Long) =
        api.prefetch("/api/entries/person", "memberId" to memberId, "month" to "all", "day" to "all")
    fun prefetchMyEntry(date: String) =
        api.prefetch("/api/entries/mine", "from" to date, "to" to date)
    fun prefetchAttendance(cellId: Long?) =
        api.prefetch("/api/entries/attendance",
            "scope" to (if (cellId == null) "all" else "cell"), "range" to "year", "cellId" to cellId)
    fun prefetchPrayers(scope: String) = api.prefetch("/api/prayers", "scope" to scope)
    fun prefetchNotices() = api.prefetch("/api/notices")
    fun prefetchBulletins() = api.prefetch("/api/bulletins")
    fun prefetchCells() = api.prefetch("/api/cells")
    fun prefetchPushConfig() = api.prefetch("/api/push/config")

    // ── 플랫폼 운영자 ──────────────────────────────────────────────────────
    suspend fun churchApplications(status: String = "pending") =
        api.get("/api/platform/churches", ChurchApplicationList.serializer(), "status" to status).churches
    suspend fun reviewChurch(churchId: Long, status: String, reason: String? = null) =
        api.post("/api/platform/churches/review", Ok.serializer(), mapOf(
            "churchId" to churchId, "status" to status, "reason" to reason,
        ))
}
