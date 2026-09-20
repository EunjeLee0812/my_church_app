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

        /** 성서유니온 오늘의 말씀 — 기본값 */
        const val DEFAULT_BIBLE_URL = "https://sum.su.or.kr:8888/bible/today"
    }

    val fontScale: Flow<Float> = context.dataStore.data.map { it[FONT_SCALE] ?: 1.0f }
    val bibleUrl: Flow<String> = context.dataStore.data.map { it[BIBLE_URL] ?: DEFAULT_BIBLE_URL }
    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[TOKEN] }

    suspend fun token(): String? = context.dataStore.data.first()[TOKEN]

    suspend fun bibleUrlNow(): String = context.dataStore.data.first()[BIBLE_URL] ?: DEFAULT_BIBLE_URL

    suspend fun fontScaleNow(): Float = context.dataStore.data.first()[FONT_SCALE] ?: 1.0f

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
