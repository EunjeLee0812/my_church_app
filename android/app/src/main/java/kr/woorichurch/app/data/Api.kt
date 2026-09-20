package kr.woorichurch.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        vararg params: Pair<String, Any?>,
    ): T = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(urlOf(path, params)).get()
            .apply { authHeader()?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { json.decodeFromString(serializer, readOrThrow(it)) }
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
        client.newCall(req).execute().use { json.decodeFromString(serializer, readOrThrow(it)) }
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
        client.newCall(req).execute().use { json.decodeFromString(Ok.serializer(), readOrThrow(it)) }
    }

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
        else -> JsonPrimitive(v.toString())
    }
}
