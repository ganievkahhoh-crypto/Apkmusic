package com.aiclips.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ---------- Модели ----------

data class Line(val t: Double, val text: String)

data class Scene(
    val start: Double,
    val end: Double,
    val lines: List<Line>,
    val zoomIn: Boolean,
    var prompt: String = ""
)

// ---------- HTTP ----------

object Net {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
    fun http(): OkHttpClient = client

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

// ---------- Текст песни ----------

object Lyrics {
    private const val UA = "AIClips/1.0 (Android app)"

    /** Возвращает синхронный текст (LRC) или null. */
    fun fetch(artist: String, title: String, durSec: Double): String? {
        // 1) точный запрос по метаданным
        if (artist.isNotBlank() && title.isNotBlank()) {
            try {
                val url = "https://lrclib.net/api/get" +
                        "?artist_name=" + Net.enc(artist) +
                        "&track_name=" + Net.enc(title) +
                        "&duration=" + durSec.toInt()
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                Net.http().newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val body = r.body?.string() ?: ""
                        val obj = JSONObject(body)
                        val syn = obj.optString("syncedLyrics", "")
                        if (syn.isNotBlank()) return syn
                    }
                }
            } catch (_: Exception) { /* идём в поиск */ }
        }
        // 2) резервный поиск
        try {
            val url = "https://lrclib.net/api/search?q=" + Net.enc("$artist $title")
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            Net.http().newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val body = r.body?.string() ?: return null
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val syn = o.optString("syncedLyrics", "")
                        if (syn.isBlank()) continue
                        val d = o.optDouble("duration", -1.0)
                        if (d > 0 && kotlin.math.abs(d - durSec) > 8.0) continue
                        return syn
                    }
                    // если ничего по длительности не подошло — берём первый с текстом
                    for (i in 0 until arr.length()) {
                        val syn = arr.getJSONObject(i).optString("syncedLyrics", "")
                        if (syn.isNotBlank()) return syn
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Разбор LRC с поддержкой нескольких меток времени на строке. */
    fun parse(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        val re = Regex("""\[(\d+):(\d+(?:[.,]\d+)?)\]""")
        lrc.lines().forEach { raw ->
            val marks = re.findAll(raw).toList()
            if (marks.isEmpty()) return@forEach
            val text = raw.substring(marks.last().range.last + 1).trim()
            if (text.isBlank()) return@forEach
            if (isMeta(text)) return@forEach
            marks.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].replace(',', '.').toDouble()
                out.add(Line(min * 60.0 + sec, text))
            }
        }
        return out.sortedBy { it.t }
    }

    private fun isMeta(t: String): Boolean {
        // строки вида "Композиция: ...", "Исполнитель: ..." и стандартные теги LRC
        if (Regex("""^[A-Za-zА-Яа-яЁё ]{1,25}\s*:\s""").containsMatchIn(t)) return true
        val tag = t.substringBefore(':').trim().lowercase()
        return tag in setOf("ti", "ar", "al", "re", "ve", "by", "offset", "tool", "length")
    }

    /** Распределение ручного текста (без таймкодов) равномерно по треку. */
    fun distribute(manual: String, durSec: Double): List<Line> {
        val rows = manual.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (rows.isEmpty()) return emptyList()
        val n = rows.size
        val step = durSec / n
        return rows.mapIndexed { i, t -> Line(step * i + step / 2, t) }
    }
}

// ---------- Группировка строк в сцены ----------

object Scenes {
    /** Собираем смысловые блоки: не мельтешим на каждой строке. */
    fun build(lines: List<Line>, durSec: Double): List<Scene> {
        val clean = lines.filter { it.t in 0.0..durSec }
        if (clean.isEmpty()) return emptyList()
        val scenes = ArrayList<Scene>()
        var buf = ArrayList<Line>()
        var bufStart = clean[0].t
        for (i in clean.indices) {
            val l = clean[i]
            buf.add(l)
            val nextT = if (i + 1 < clean.size) clean[i + 1].t else durSec
            val span = l.t - bufStart
            val gap = nextT - l.t
            if (span >= 14.0 || buf.size >= 4 || gap > 10.0) {
                scenes.add(Scene(bufStart, nextT, ArrayList(buf), zoomIn = scenes.size % 2 == 0))
                buf = ArrayList()
                bufStart = nextT
            }
        }
        if (buf.isNotEmpty()) {
            scenes.add(Scene(bufStart, durSec, ArrayList(buf), zoomIn = scenes.size % 2 == 0))
        }
        return scenes
    }

    /** Заглушка, если текста нет вообще: равные блоки по ~20 секунд. */
    fun blank(durSec: Double): List<Scene> {
        val out = ArrayList<Scene>()
        var t = 0.0
        var i = 0
        while (t < durSec) {
            val end = minOf(t + 20.0, durSec)
            out.add(Scene(t, end, emptyList(), zoomIn = i % 2 == 0))
            t = end
            i++
        }
        return out
    }
}
