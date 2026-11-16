package com.aiclips.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Режиссёр: превращает строки песни в визуальные промпты.
 * Основной путь — текстовая модель Pollinations, фолбэк — словарь мотивов.
 */
object Director {

    private const val SYSTEM = """Ты — визуальный режиссёр музыкального клипа. Тебе дают строку песни,
стиль клипа и описание предыдущих кадров. Придумай ОДИН конкретный кадр для этой строки.
Описывай видимые вещи: место, время суток, свет, предметы, людей, позы, ракурс.
Не описывай чувства и абстракции напрямую — переводи их в образ (тоска -> одинокая фигура у окна).
Сохраняй преемственность: если в предыдущих кадрах есть персонажи или место, используй их же.
Ответ строго в формате JSON: {"scene": "описание кадра на английском", "motion": "движение камеры или объектов на английском"}."""

    private const val STYLE = "cinematic film still, warm muted palette, film grain, 35mm, melancholic mood"

    private val MOTIFS = listOf(
        Triple(listOf("хрущ", "панел", "дом", "квартир", "подъезд"),
            "Soviet panel apartment building at dusk, one warm lit window, courtyard below", "slow zoom toward the lit window"),
        Triple(listOf("окн"),
            "close view of an apartment window at evening, warm light inside", "camera slowly pushes toward the glass"),
        Triple(listOf("силуэт", "вдво", "мы с тобой", "двои"),
            "two human silhouettes side by side, backlit, standing close together", "silhouettes shift slightly, gentle zoom out"),
        Triple(listOf("дорог", "шоссе", "трасс", "шосс"),
            "empty night highway with fading headlights, wet asphalt", "slow pan along the road"),
        Triple(listOf("ноч", "полноч", "звезд"),
            "night cityscape or field under starry sky, deep blue and amber tones", "slow upward tilt to the sky"),
        Triple(listOf("дожд", "грязь", "луж"),
            "rain on a dim street, drops on glass, reflections in puddles", "static shot, rain falls continuously"),
        Triple(listOf("город", "улиц", "двор"),
            "city street in the evening, sparse pedestrians, glowing signs", "slow lateral pan"),
        Triple(listOf("море", "волн", "берег"),
            "seashore at dusk, slow waves, empty beach", "wide shot, waves move slowly"),
        Triple(listOf("поезд", "вокзал", "путь", "рельс"),
            "railway platform at night, single lamp, receding tracks", "camera slides along the platform"),
        Triple(listOf("глаз", "лицо", "щек"),
            "close-up portrait in soft window light, thoughtful gaze", "slow push-in on the face"),
        Triple(listOf("рук", "обним", "плеч"),
            "two hands almost touching in dim warm light", "very slow zoom on the hands"),
        Triple(listOf("свет", "ламп", "свеч"),
            "single warm lamp in a dark room, dust in the light beam", "slow drift around the lamp"),
        Triple(listOf("трам", "автобус", "такс"),
            "old tram or bus at a night stop, interior lights on", "static wide shot, vehicle departs slowly"),
        Triple(listOf("провода", "забор", "двор", "песоч"),
            "quiet courtyard between panel buildings, wires against the sky", "slow upward tilt past the wires")
    )

    fun fallback(line: String, index: Int): String {
        val low = line.lowercase()
        val hit = MOTIFS.firstOrNull { it.first.any { kw -> kw in low } }
        val (scene, motion) = hit?.second?.let { it }
            ?: listOf(
                "empty urban evening scene, atmospheric haze" to "slow cinematic zoom",
                "lonely figure seen from afar, warm light nearby" to "slow pan",
                "window light in darkness, curtains moving slightly" to "slow push-in"
            )[index % 3]
        return "$scene, $motion"
    }

    fun make(scene: Scene, index: Int, history: List<Scene>): String {
        val text = if (scene.lines.isEmpty()) "" else scene.lines.joinToString(" ") { it.text }
        val fb = if (text.isBlank()) fallback("город ночь", index) else fallback(text, index)
        if (text.isBlank()) return fb
        val hist = history.takeLast(3).joinToString("; ") { it.prompt.ifBlank { "unknown" } }
        return try {
            val sys = SYSTEM.replace("\n", " ")
            val body = JSONObject()
                .put("model", "openai")
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content",
                        "Стиль клипа: $STYLE\nСтрока песни: \"$text\"\nПредыдущие кадры: $hist\n" +
                        "Придумай кадр. Ответ строго JSON {\"scene\": ..., \"motion\": ...}")))
            val req = okhttp3.Request.Builder()
                .url("https://text.pollinations.ai/")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "AIClips/1.0")
                .build()
            Net.http().newCall(req).execute().use { r ->
                val s = r.body?.string() ?: throw Exception("пустой ответ")
                val obj = JSONObject(extractJson(s) ?: throw Exception("не JSON"))
                val sc = obj.optString("scene", "").trim()
                val mo = obj.optString("motion", "").trim()
                if (sc.isBlank()) throw Exception("пустой кадр")
                if (mo.isBlank()) sc else "$sc, $mo"
            }
        } catch (_: Exception) {
            fb
        }
    }

    private fun extractJson(s: String): String? {
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return s.substring(a, b + 1)
    }

    fun style(): String = STYLE
}
