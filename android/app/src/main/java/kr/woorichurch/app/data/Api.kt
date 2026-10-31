package kr.woorichurch.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kr.woorichurch.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 서버가 돌려준 오류 메시지를 그대로 사용자에게 보여주기 위한 예외 */
class ApiException(message: String, val status: Int = 0) : Exception(message)

class Api(private val prefs: Prefs) {

    companion object {
        /** 미리 받은 데이터를 쓰는 시간 — 이보다 오래되면 새로 받습니다 */
        const val CACHE_MS = 60_000L
        /** 미리 받아 둘 수 있는 (읽기 전용) 주소 */
        val CACHEABLE = setOf(
            "/api/entries/feed", "/api/entries/person", "/api/entries/mine", "/api/entries/attendance",
            "/api/entries/comments", "/api/prayers", "/api/notices", "/api/bulletins", "/api/cells",
            "/api/push/config",
        )
    }

    private val base = BuildConfig.API_BASE.trimEnd('/')

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)   // 주보 업로드용
        .build()

    private suspend fun authHeader(): String? = prefs.token()?.let { "Bearer $it" }

    private fun urlOf(path: String, params: Array<out Pair<String, Any?>>): String {
        val b = (base + path).toHttpUrl().newBuilder()
        params.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v.toString()) }
        return b.build().toString()
    }

    private fun readOrThrow(resp: okhttp3.Response): String {
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val msg = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }
                .getOrElse { "서버에 연결하지 못했습니다 (${resp.code})" }
            throw ApiException(msg, resp.code)
        }
        return text
    }

    // ── 미리 가져오기 캐시 ──────────────────────────────────────────────────
    // 다음에 누를 만한 화면의 데이터를 미리 받아 두면, 실제로 눌렀을 때 기다리지 않습니다.
    //  · 같은 주소를 이미 받는 중이면 그 요청을 같이 기다립니다 (두 번 받지 않음)
    //  · 잠깐(CACHE_MS)만 보관하고, 무엇이든 저장·수정(POST)하면 전부 비웁니다
    //  · 목록·글처럼 읽기만 하는 주소만 캐시합니다
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Hit(val at: Long, val job: Deferred<String>)
    private val cache = ConcurrentHashMap<String, Hit>()
    @Volatile private var generation = 0L

    private fun cacheable(path: String) = CACHEABLE.any { path == it }

    /** 저장·수정 뒤에 부릅니다 — 미리 받아 둔 것을 모두 버립니다 */
    fun clearCache() { generation++; cache.clear() }

    /** POST 한 주소에 따라 버릴 캐시를 고릅니다 (읽음 표시·알림 등록은 다른 화면 데이터와 무관) */
    private fun invalidateAfter(path: String) {
        when (path) {
            "/api/notices/read" -> cache.keys.removeAll { it.contains("/api/notices") }
            "/api/push/register", "/api/push/unregister", "/api/notifications/read" -> Unit
            "/api/push/prefs" -> cache.keys.removeAll { it.contains("/api/push/config") }
            else -> clearCache()
        }
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get()
            .apply { authHeader()?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { readOrThrow(it) }
    }

    /** 캐시를 거쳐 글자 그대로 받아 옵니다 */
    private suspend fun cachedText(url: String): String {
        val now = System.currentTimeMillis()
        val hit = cache[url]
        if (hit != null && now - hit.at < CACHE_MS) {
            return runCatching { hit.job.await() }.getOrElse {
                cache.remove(url, hit)          // 미리 받다 실패했으면 다시 받습니다
                fetchText(url)
            }
        }
        val gen = generation
        val job = cacheScope.async { fetchText(url) }
        val mine = Hit(now, job)
        cache[url] = mine
        val text = try {
            job.await()
        } catch (e: Exception) {
            cache.remove(url, mine); throw e
        }
        if (gen != generation) cache.remove(url, mine)   // 받는 사이 저장이 있었으면 보관하지 않음
        return text
    }

    suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        vararg params: Pair<String, Any?>,
    ): T {
        val url = urlOf(path, params)
        val text = if (cacheable(path)) cachedText(url) else fetchText(url)
        return json.decodeFromString(serializer, text)
    }

    /** 결과는 버리고 캐시에만 채워 둡니다. 실패해도 조용히 넘어갑니다. */
    fun prefetch(path: String, vararg params: Pair<String, Any?>) {
        if (!cacheable(path)) return
        val url = urlOf(path, params)
        val hit = cache[url]
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_MS) return
        val gen = generation
        val job = cacheScope.async { fetchText(url) }
        val mine = Hit(System.currentTimeMillis(), job)
        cache[url] = mine
        job.invokeOnCompletion { err ->
            if (err != null || gen != generation) cache.remove(url, mine)
        }
    }

    suspend fun <T> post(
        path: String,
        serializer: KSerializer<T>,
        body: Map<String, Any?> = emptyMap(),
        vararg params: Pair<String, Any?>,
    ): T = withContext(Dispatchers.IO) {
        val payload = toJsonObject(body).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(urlOf(path, params)).post(payload)
            .apply { authHeader()?.let { header("Authorization", it) } }
            .build()
        try {
            client.newCall(req).execute().use { json.decodeFromString(serializer, readOrThrow(it)) }
        } finally { invalidateAfter(path) }
    }

    /** 주보 파일 업로드 — 본문에 바이트를 그대로 실어 보냅니다 */
    suspend fun upload(
        bytes: ByteArray,
        mime: String,
        title: String,
        weekOf: String,
    ): Ok = withContext(Dispatchers.IO) {
        val url = urlOf("/api/bulletins/upload", arrayOf("title" to title, "weekOf" to weekOf))
        val req = Request.Builder().url(url)
            .post(bytes.toRequestBody(mime.toMediaType()))
            .apply { authHeader()?.let { header("Authorization", it) } }
            .build()
        try {
            client.newCall(req).execute().use { json.decodeFromString(Ok.serializer(), readOrThrow(it)) }
        } finally { clearCache() }
    }

    /** 묵상 사진 올리기 — 미리 줄인 jpg 바이트 */
    suspend fun uploadImage(bytes: ByteArray, mime: String): UploadImageResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/entries/image")
            .post(bytes.toRequestBody(mime.toMediaType()))
            .apply { authHeader()?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { json.decodeFromString(UploadImageResult.serializer(), readOrThrow(it)) }
    }

    /** 서버가 주는 /img/... 같은 상대 주소를 전체 주소로 */
    fun absolute(path: String): String = if (path.startsWith("http")) path else base + path

    /** 주보 파일 내려받기 (이미지 미리보기 / PDF 열기용) */
    suspend fun download(bulletinId: Long): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/bulletins/$bulletinId/file").get()
            .apply { authHeader()?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("주보를 불러오지 못했습니다 (${resp.code})", resp.code)
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun toJsonObject(map: Map<String, Any?>): JsonObject = buildJsonObject {
        map.forEach { (k, v) -> put(k, toElement(v)) }
    }

    private fun toElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { toElement(it) })
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toElement(x) })
        else -> JsonPrimitive(v.toString())
    }
}
