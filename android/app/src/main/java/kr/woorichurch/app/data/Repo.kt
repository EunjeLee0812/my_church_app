package kr.woorichurch.app.data

/** 화면에서 부르는 서버 기능 모음. 주소·파라미터는 전부 여기 한 곳에 모아 둡니다. */
class Repo(val api: Api, val prefs: Prefs) {

    // ── 로그인 전 ──────────────────────────────────────────────────────────
    suspend fun searchChurches(q: String) =
        api.get("/api/churches/search", ChurchList.serializer(), "q" to q).churches

    suspend fun registerChurch(
        churchName: String, address: String, phone: String, denomination: String,
        pastorName: String, seminary: String, homepage: String, note: String,
        pinNumber: String, email: String?, password: String?, inviteCode: String?,
    ) = api.post("/api/churches/register", SignupResult.serializer(), mapOf(
        "churchName" to churchName, "address" to address, "phone" to phone,
        "denomination" to denomination, "pastorName" to pastorName,
        "seminary" to seminary, "homepage" to homepage, "note" to note,
        "pinNumber" to pinNumber,
        "email" to email?.ifBlank { null }, "password" to password?.ifBlank { null },
        "inviteCode" to inviteCode?.ifBlank { null },
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
        memberId: Long, title: String? = null, cellId: Long? = null, clearCell: Boolean = false,
        role: String? = null, shareScope: String? = null, status: String? = null,
    ) = api.post("/api/members/update", Ok.serializer(), buildMap {
        put("memberId", memberId)
        title?.let { put("title", it) }
        if (clearCell) put("cellId", null) else cellId?.let { put("cellId", it) }
        role?.let { put("role", it) }
        shareScope?.let { put("shareScope", it) }
        status?.let { put("status", it) }
    })
    suspend fun approveMember(memberId: Long, approve: Boolean, cellId: Long? = null) =
        api.post("/api/members/approve", Ok.serializer(), mapOf(
            "memberId" to memberId, "approve" to approve, "cellId" to cellId,
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
    ) = api.post("/api/entries/save", SaveEntryResult.serializer(), mapOf(
        "date" to date, "title" to title, "body" to body,
        "isPublic" to isPublic, "cellIds" to cellIds, "allCells" to allCells,
    ))

    suspend fun deleteEntry(id: Long) =
        api.post("/api/entries/delete", Ok.serializer(), mapOf("id" to id))

    suspend fun feed(date: String, scope: String) =
        api.get("/api/entries/feed", EntryList.serializer(), "date" to date, "scope" to scope).entries

    suspend fun attendance(scope: String, year: Int? = null, quarter: Int? = null) =
        api.get("/api/entries/attendance", Attendance.serializer(),
            "scope" to scope, "year" to year, "quarter" to quarter)

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
    suspend fun sendSuggestion(body: String) =
        api.post("/api/suggestions", Ok.serializer(), mapOf("body" to body))

    suspend fun suggestions() =
        api.get("/api/suggestions", SuggestionList.serializer()).suggestions

    // ── 플랫폼 운영자 ──────────────────────────────────────────────────────
    suspend fun churchApplications(status: String = "pending") =
        api.get("/api/platform/churches", ChurchApplicationList.serializer(), "status" to status).churches
    suspend fun reviewChurch(churchId: Long, status: String, reason: String? = null) =
        api.post("/api/platform/churches/review", Ok.serializer(), mapOf(
            "churchId" to churchId, "status" to status, "reason" to reason,
        ))
}
