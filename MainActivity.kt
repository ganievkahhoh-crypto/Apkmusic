package com.aiclips.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Random

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var status: TextView
    private lateinit var lyrics: TextView
    private lateinit var imgA: ImageView
    private lateinit var imgB: ImageView
    private lateinit var artistEdit: EditText
    private lateinit var titleEdit: EditText
    private lateinit var manualEdit: EditText
    private lateinit var buildBtn: Button

    private var trackUri: Uri? = null
    private var scenes: List<Scene> = emptyList()
    private val bitmaps = HashMap<Int, Bitmap>()
    private var currentScene = -1
    private var lastLine: String? = null
    private var frontIsA = false
    private var ticker: Job? = null
    private var genJob: Job? = null
    private var assembling = false

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                trackUri = uri
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                status.text = "Трек загружен. Проверь поля и жми «Собрать клип»."
                buildBtn.isEnabled = true
                guessMeta(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#12100E"))
            setPadding(pad, pad, pad, pad)
        }

        val header = TextView(this).apply {
            text = "AI Клипы"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        }

        artistEdit = EditText(this).apply {
            hint = "Исполнитель"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            singleLine = true
        }
        titleEdit = EditText(this).apply {
            hint = "Название трека"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            singleLine = true
        }
        manualEdit = EditText(this).apply {
            hint = "Текст вручную (если не найдётся сам), строка на строку"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            minLines = 2
            maxLines = 4
        }

        val pickBtn = Button(this).apply { text = "Выбрать трек" }
        buildBtn = Button(this).apply {
            text = "Собрать клип"
            isEnabled = false
        }

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#E8B84B"))
            textSize = 14f
        }

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        imgA = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        imgB = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        stage.addView(imgA, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        stage.addView(imgB, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        lyrics = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val lyricsLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (100 * dp).toInt())
        lyricsLp.topMargin = (8 * dp).toInt()

        root.addView(header)
        root.addView(artistEdit)
        root.addView(titleEdit)
        root.addView(manualEdit)
        root.addView(pickBtn)
        root.addView(buildBtn)
        root.addView(status)
        root.addView(stage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = pad })
        root.addView(lyrics, lyricsLp)
        setContentView(root)

        player = ExoPlayer.Builder(this).build()

        pickBtn.setOnClickListener { pickLauncher.launch("audio/*") }
        buildBtn.setOnClickListener {
            if (!assembling) {
                assembling = true
                lifecycleScope.launch { assemble() }
            }
        }
    }

    private fun guessMeta(uri: Uri) {
        val name = uri.lastPathSegment ?: ""
        val clean = name.substringAfterLast('/')
        if (" - " in clean) {
            val parts = clean.removeSuffix(".mp3").removeSuffix(".m4a").removeSuffix(".ogg")
                .removeSuffix(".wav").removeSuffix(".flac").split(" - ")
            if (parts.size >= 2) {
                artistEdit.setText(parts[0].trim())
                titleEdit.setText(parts[1].trim())
            }
        }
    }

    private suspend fun assemble() {
        try {
            val durMs = player.duration
            if (durMs <= 0) {
                status.text = "Трек ещё не готов, подожди пару секунд"
                assembling = false
                return
            }
            val durSec = durMs / 1000.0
            val artist = artistEdit.text.toString().trim()
            val title = titleEdit.text.toString().trim()

            status.text = "Ищу текст песни…"
            val lrc = withContext(Dispatchers.IO) { Lyrics.fetch(artist, title, durSec) }

            var lines: List<Line> = emptyList()
            if (lrc != null) {
                lines = Lyrics.parse(lrc)
            }
            if (lines.isEmpty()) {
                val manual = manualEdit.text.toString().trim()
                if (manual.isNotBlank()) lines = Lyrics.distribute(manual, durSec)
            }

            scenes = if (lines.isNotEmpty()) Scenes.build(lines, durSec) else Scenes.blank(durSec)
            if (scenes.isEmpty()) {
                status.text = "Не получилось разобрать текст. Вставь его вручную в поле."
                assembling = false
                return
            }

            status.text = "Сцен: ${scenes.size}. Продумываю кадры…"
            withContext(Dispatchers.IO) {
                scenes.forEachIndexed { i, s -> s.prompt = Director.make(s, i, scenes.take(i)) }
            }

            lastLine = null
            currentScene = -1
            startTicker()
            startPrefetch()
            player.playWhenReady = true
            player.play()
            assembling = false
        } catch (e: Exception) {
            status.text = "Ошибка: ${e.message}"
            assembling = false
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (true) {
                tick()
                delay(100)
            }
        }
    }

    private fun tick() {
        val pos = player.currentPosition / 1000.0
        val idx = scenes.indexOfFirst { pos >= it.start && pos < it.end }
        if (idx >= 0 && idx != currentScene) {
            currentScene = idx
            onScene(idx)
        }
        val line = scenes.getOrNull(currentScene)
            ?.lines?.lastOrNull { it.t <= pos }?.text
        if (line != null && line != lastLine) {
            lastLine = line
            lyrics.text = line
        }
    }

    private fun onScene(idx: Int) {
        val bmp = bitmaps[idx] ?: bitmaps[idx - 1] ?: return
        showFrame(bmp, scenes[idx])
    }

    private fun showFrame(bmp: Bitmap, scene: Scene) {
        val toShow = if (frontIsA) imgB else imgA
        val toHide = if (frontIsA) imgA else imgB
        frontIsA = !frontIsA

        toShow.setImageBitmap(bmp)
        toShow.scaleX = 1f; toShow.scaleY = 1f
        toShow.translationX = 0f; toShow.translationY = 0f
        toShow.alpha = 0f
        toShow.animate().alpha(1f).setDuration(900L).start()
        toHide.animate().alpha(0f).setDuration(900L).start()

        val durMs = ((scene.end - scene.start).coerceAtLeast(2.0) * 1000).toLong()
        if (scene.zoomIn) {
            toShow.animate().scaleX(1.15f).scaleY(1.15f)
                .setDuration(durMs).setInterpolator(LinearInterpolator()).start()
        } else {
            toShow.scaleX = 1.15f; toShow.scaleY = 1.15f
            toShow.animate().scaleX(1f).scaleY(1f)
                .setDuration(durMs).setInterpolator(LinearInterpolator()).start()
        }
    }

    private fun startPrefetch() {
        genJob?.cancel()
        val seed = Random().nextLong()
        genJob = lifecycleScope.launch(Dispatchers.IO) {
            for (i in scenes.indices) {
                if (bitmaps.containsKey(i)) continue
                if (i > currentScene + 2) delay(1200)
                val bmp = Renderer.render(scenes[i].prompt, seed + i)
                if (bmp != null) {
                    bitmaps[i] = bmp
                    withContext(Dispatchers.Main) { if (i == currentScene) onScene(i) }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker?.cancel()
        genJob?.cancel()
        player.release()
    }
}
