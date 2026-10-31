package kr.woorichurch.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "woori_prefs")

/**
 * 기기에만 저장되는 값들.
 *  - 로그인 토큰
 *  - 글씨 크기 (시니어용)
 *  - 묵상 본문 링크  ← 교회 전체가 아니라 '이 기기'에만 적용됩니다
 *  - 마지막에 고른 교회 (다시 로그인할 때 편하도록)
 *  - 작성 중이던 묵상 임시 보관
 */
class Prefs(private val context: Context) {

    companion object {
        val TOKEN = stringPreferencesKey("token")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val BIBLE_URL = stringPreferencesKey("bible_url")
        val LAST_CHURCH_ID = longPreferencesKey("last_church_id")
        val LAST_CHURCH_NAME = stringPreferencesKey("last_church_name")
        val LAST_NAME = stringPreferencesKey("last_name")
        val DRAFT_DATE = stringPreferencesKey("draft_date")
        val DRAFT_TITLE = stringPreferencesKey("draft_title")
        val DRAFT_BODY = stringPreferencesKey("draft_body")
        val BOOT = stringPreferencesKey("boot")
        val PUSH_TOKEN = stringPreferencesKey("push_token")
        val BOOT_AT = longPreferencesKey("boot_at")
        val SENIOR = booleanPreferencesKey("senior_mode")
        val WHEELS = stringPreferencesKey("wheels_json")

        /** 성서유니온 오늘의 말씀 — 기본값 */
        const val DEFAULT_BIBLE_URL = "https://sum.su.or.kr:8888/bible/today"
    }

    /** 글씨 크기 — 따로 정한 적이 없으면 시니어 모드는 보통(1.0), 새 화면은 가장 작게(0.85) */
    val fontScale: Flow<Float> = context.dataStore.data.map { it[FONT_SCALE] ?: defaultScale(it[SENIOR] == true) }
    private fun defaultScale(senior: Boolean) = if (senior) 1.0f else 0.85f
    val bibleUrl: Flow<String> = context.dataStore.data.map { it[BIBLE_URL] ?: DEFAULT_BIBLE_URL }
    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[TOKEN] }

    /** 시니어 모드 — 켜면 예전의 큰 글씨·큰 버튼 화면, 끄면 아래 탭이 있는 새 화면 (기본: 꺼짐) */
    val seniorMode: Flow<Boolean> = context.dataStore.data.map { it[SENIOR] ?: false }
    /** 모드를 바꾸면 글씨 크기도 그 모드의 기본으로 (시니어 켜기 → 보통, 끄기 → 가장 작게) */
    suspend fun setSeniorMode(on: Boolean) = context.dataStore.edit {
        it[SENIOR] = on
        it[FONT_SCALE] = defaultScale(on)
    }

    /** 돌림판 — 이 기기에만 저장 (JSON 문자열) */
    suspend fun wheelsJson(): String? = context.dataStore.data.first()[WHEELS]
    suspend fun setWheelsJson(v: String) = context.dataStore.edit { it[WHEELS] = v }

    suspend fun token(): String? = context.dataStore.data.first()[TOKEN]

    suspend fun bibleUrlNow(): String = context.dataStore.data.first()[BIBLE_URL] ?: DEFAULT_BIBLE_URL

    suspend fun fontScaleNow(): Float =
        context.dataStore.data.first().let { it[FONT_SCALE] ?: defaultScale(it[SENIOR] == true) }

    /** 홈 화면 데이터를 잠깐 저장해 두고 다음에 곧바로 보여 줍니다 */
    suspend fun saveBoot(json: String) = context.dataStore.edit {
        it[BOOT] = json; it[BOOT_AT] = System.currentTimeMillis()
    }

    suspend fun loadBoot(): String? {
        val p = context.dataStore.data.first()
        val at = p[BOOT_AT] ?: return null
        if (System.currentTimeMillis() - at > 12 * 3600_000L) return null   // 반나절 지나면 버림
        return p[BOOT]
    }

    suspend fun clearBoot() = context.dataStore.edit { it.remove(BOOT); it.remove(BOOT_AT) }

    suspend fun pushToken(): String? = context.dataStore.data.first()[PUSH_TOKEN]
    suspend fun setPushToken(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(PUSH_TOKEN) else it[PUSH_TOKEN] = v
    }

    suspend fun setToken(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(TOKEN) else it[TOKEN] = value
    }

    suspend fun setFontScale(value: Float) = context.dataStore.edit { it[FONT_SCALE] = value }

    suspend fun setBibleUrl(value: String) = context.dataStore.edit {
        it[BIBLE_URL] = value.ifBlank { DEFAULT_BIBLE_URL }
    }

    suspend fun rememberChurch(id: Long, name: String) = context.dataStore.edit {
        it[LAST_CHURCH_ID] = id; it[LAST_CHURCH_NAME] = name
    }

    suspend fun lastChurch(): Pair<Long, String>? {
        val p = context.dataStore.data.first()
        val id = p[LAST_CHURCH_ID] ?: return null
        return id to (p[LAST_CHURCH_NAME] ?: "")
    }

    suspend fun rememberName(name: String) = context.dataStore.edit { it[LAST_NAME] = name }
    suspend fun lastName(): String = context.dataStore.data.first()[LAST_NAME] ?: ""

    // 작성 중 자동 임시저장 ---------------------------------------------------
    suspend fun saveDraft(date: String, title: String, body: String) = context.dataStore.edit {
        it[DRAFT_DATE] = date; it[DRAFT_TITLE] = title; it[DRAFT_BODY] = body
    }

    suspend fun loadDraft(date: String): Pair<String, String>? {
        val p = context.dataStore.data.first()
        if (p[DRAFT_DATE] != date) return null
        val body = p[DRAFT_BODY] ?: return null
        if (body.isBlank()) return null
        return (p[DRAFT_TITLE] ?: "") to body
    }

    suspend fun clearDraft() = context.dataStore.edit {
        it.remove(DRAFT_DATE); it.remove(DRAFT_TITLE); it.remove(DRAFT_BODY)
    }
}
