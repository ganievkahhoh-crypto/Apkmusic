package com.aiclips.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Request

/** Генерация кадров через Pollinations (модель turbo, без ключей). */
object Renderer {

    fun render(prompt: String, seed: Long, w: Int = 768, h: Int = 768): Bitmap? {
        return try {
            val full = Net.enc("${Director.style()}, $prompt, no text, no watermark, no signature")
            val url = "https://image.pollinations.ai/prompt/$full" +
                    "?width=$w&height=$h&model=turbo&seed=$seed&nologo=true"
            val req = Request.Builder().url(url).header("User-Agent", "AIClips/1.0").build()
            Net.http().newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val bytes = r.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }
}
