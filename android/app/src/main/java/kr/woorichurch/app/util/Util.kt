package kr.woorichurch.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ── 날짜 ──────────────────────────────────────────────────────────────────
object D {
    fun today(): String = LocalDate.now().toString()
    fun parse(s: String): LocalDate? = runCatching { LocalDate.parse(s.take(10)) }.getOrNull()

    /** 2026-09-20 → 9월 20일 (일) */
    fun korean(s: String): String {
        val d = parse(s) ?: return s
        val dow = listOf("월", "화", "수", "목", "금", "토", "일")[d.dayOfWeek.value - 1]
        return "${d.monthValue}월 ${d.dayOfMonth}일 ($dow)"
    }

    fun koreanFull(s: String): String {
        val d = parse(s) ?: return s
        return "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
    }

    /** 이번 주 주일 날짜 */
    fun thisSunday(): String {
        val d = LocalDate.now()
        return d.minusDays((d.dayOfWeek.value % 7).toLong()).toString()
    }

    fun shortTime(s: String): String =
        runCatching { s.substring(5, 16).replace("-", "/") }.getOrDefault(s)

    val fmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
}

// ── 바깥 앱 열기 ───────────────────────────────────────────────────────────
object Launch {

    /**
     * 줌 회의실로 바로 들어갑니다.
     * 줌 앱이 있으면 `zoomus://` 로 한 번에, 없으면 웹 링크로 엽니다.
     */
    fun zoom(context: Context, url: String): Boolean {
        val id = Regex("""/j/(\d+)""").find(url)?.groupValues?.get(1)
        val pwd = Regex("""[?&]pwd=([^&]+)""").find(url)?.groupValues?.get(1)
        if (id != null) {
            val deep = buildString {
                append("zoomus://zoom.us/join?confno=").append(id)
                if (pwd != null) append("&pwd=").append(pwd)
            }
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(deep)).apply {
                setPackage("us.zoom.videomeetings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(i); return true } catch (_: ActivityNotFoundException) { }
        }
        return web(context, url)
    }

    fun web(context: Context, url: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) { false }

    /** 카카오톡 등으로 나눔 글 공유 */
    fun shareText(context: Context, text: String, subject: String = "묵상 나눔") {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(i, "공유하기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 내려받은 주보를 기본 앱(PDF 뷰어 등)으로 열기 */
    fun openFile(context: Context, bytes: ByteArray, fileName: String, mime: String): Boolean = try {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (_: Exception) { false }
}

// ── 주보 사진 자동 축소 ─────────────────────────────────────────────────────
object ImageShrink {

    /**
     * 폰으로 찍은 큰 사진을 서버에 올리기 전에 줄입니다.
     * 긴 변 [maxSide] px, 용량 [maxBytes] 이하가 될 때까지 품질을 낮춥니다.
     * PDF 는 줄일 수 없으므로 그대로 돌려줍니다.
     */
    fun shrink(
        context: Context,
        uri: Uri,
        mime: String,
        maxSide: Int = 2200,
        maxBytes: Int = 4 * 1024 * 1024,
    ): Pair<ByteArray, String>? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (!mime.startsWith("image/")) return raw to mime
        if (raw.size <= maxBytes) {
            // 그래도 화면보다 훨씬 크면 줄여 둡니다
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (maxOf(bounds.outWidth, bounds.outHeight) <= maxSide) return raw to mime
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2

        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return raw to mime

        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp

        var quality = 90
        var out = ByteArrayOutputStream()
        while (true) {
            out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes || quality <= 45) break
            quality -= 10
        }
        return out.toByteArray() to "image/jpeg"
    }

    /**
     * 묵상 나눔 사진용 — 폰 사진(보통 3~8MB)을 화면에서 보기 충분한 크기로 줄입니다.
     * 긴 변 1600px, JPEG, 대략 300KB~1MB. 세로로 찍은 사진이 눕지 않도록 회전 정보도 반영합니다.
     */
    fun forPost(
        context: Context,
        uri: Uri,
        maxSide: Int = 1600,
        maxBytes: Int = 1024 * 1024,
    ): ByteArray? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: return null

        // 사진의 회전 정보 (카메라로 세로로 찍으면 대부분 90도로 저장됩니다)
        val degrees = runCatching {
            when (android.media.ExifInterface(raw.inputStream())
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val m = android.graphics.Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (degrees != 0f) postRotate(degrees)
        }
        val out = if (scale < 1f || degrees != 0f)
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) else bmp

        var quality = 82
        var bytes: ByteArray
        while (true) {
            val o = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, o)
            bytes = o.toByteArray()
            if (bytes.size <= maxBytes || quality <= 50) break
            quality -= 8
        }
        return bytes
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }
}
