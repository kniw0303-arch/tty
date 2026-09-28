package com.example.livetranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * 폰에서 재생되는 영상 소리를 캡처 → Vosk로 음성 인식 → ML Kit로 한국어 번역 → 자막 창 표시
 */
class TranslateService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_LANG = "lang"
        const val ACTION_STOP = "com.example.livetranslator.STOP"
        private const val CHANNEL = "translate"
        private const val RATE = 16000
        private const val MAX_SENTENCE_MS = 8000L   // 말이 계속 이어지면 8초마다 끊어서 번역
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var overlay: SubtitleOverlay? = null
    private var translator: Translator? = null
    @Volatile private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        startForegroundNotification()
        cleanup() // 이미 실행 중이면 새 설정으로 다시 시작

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = projectionData(intent) ?: run { stopSelf(); return START_NOT_STICKY }
        val lang = Languages.list[intent.getIntExtra(EXTRA_LANG, 0)]

        val ov = overlay ?: SubtitleOverlay(this) { stopSelf() }.also { overlay = it }
        ov.show()
        ov.setText("", "준비 중...")

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data).also {
                it.registerCallback(projectionCallback, main)
            }
            setupTranslator(lang)
            startCapture(lang)
        } catch (e: Exception) {
            ov.setText("", "시작 실패: ${e.message}")
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "실시간 번역", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, TranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("실시간 번역 중")
            .setContentText("재생 중인 영상 소리를 번역하고 있어요")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(Notification.Action.Builder(null as Icon?, "중지", stopPi).build())
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ───────── 번역 (ML Kit, 기기 내 오프라인) ─────────
    private fun setupTranslator(lang: SourceLang) {
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(lang.mlkit)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
        translator = t
        t.downloadModelIfNeeded()
            .addOnFailureListener { overlay?.setTranslated("번역 모델 다운로드 실패: ${it.message}") }
    }

    private fun translate(text: String) {
        overlay?.setOriginal(text)
        translator?.translate(text)
            ?.addOnSuccessListener { overlay?.setTranslated(it) }
            ?.addOnFailureListener { overlay?.setTranslated("(번역 모델 준비 중...)") }
    }

    // ───────── 소리 캡처 + 음성 인식 ─────────
    @SuppressLint("MissingPermission")
    private fun startCapture(lang: SourceLang) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, RATE * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = rec
        active = true
        val modelPath = ModelManager.modelDir(this, lang).absolutePath
        worker = thread(name = "stt") { runRecognition(rec, modelPath, lang) }
    }

    private fun runRecognition(rec: AudioRecord, modelPath: String, lang: SourceLang) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            model = Model(modelPath)
            recognizer = Recognizer(model, RATE.toFloat())
            rec.startRecording()
            main.post { overlay?.setText("", "🎧 듣는 중... 영상을 재생하세요") }

            val buf = ByteArray(3200) // 0.1초 분량
            var lastPartialAt = 0L
            var speechStart = 0L
            while (active) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = SystemClock.elapsedRealtime()
                if (recognizer.acceptWaveForm(buf, n)) {
                    emitFinal(JSONObject(recognizer.result).optString("text"), lang)
                    speechStart = 0L
                } else if (now - lastPartialAt > 300) {
                    lastPartialAt = now
                    val partial = lang.clean(JSONObject(recognizer.partialResult).optString("partial"))
                    if (partial.isNotBlank()) {
                        if (speechStart == 0L) speechStart = now
                        if (now - speechStart > MAX_SENTENCE_MS) {
                            emitFinal(JSONObject(recognizer.finalResult).optString("text"), lang)
                            speechStart = 0L
                        } else {
                            main.post { overlay?.setOriginal(partial) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (active) main.post { overlay?.setText("", "음성 인식 오류: ${e.message}") }
        } finally {
            recognizer?.close()
            model?.close()
        }
    }

    private fun emitFinal(raw: String, lang: SourceLang) {
        val text = lang.clean(raw)
        if (text.isNotBlank()) main.post { translate(text) }
    }

    // ───────── 정리 ─────────
    private fun cleanup() {
        active = false
        runCatching { record?.stop() }
        worker?.join(1500)
        worker = null
        record?.release()
        record = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        translator?.close()
        translator = null
    }

    override fun onDestroy() {
        cleanup()
        overlay?.remove()
        overlay = null
        super.onDestroy()
    }
}
