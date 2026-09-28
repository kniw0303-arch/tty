package com.example.livetranslator

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        private const val REQ_PERM = 1
        private const val REQ_PROJECTION = 2
    }

    private lateinit var urlInput: EditText
    private lateinit var langSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    @Volatile private var downloading = false
    private val selectedLang get() = Languages.list[langSpinner.selectedItemPosition]

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setPadding(0, dp(16), 0, dp(4))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        urlInput = EditText(this).apply {
            hint = "https://..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                Languages.list.map { it.label },
            )
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
            text = "1. 영상 링크를 붙여넣거나, 브라우저·유튜브에서 '공유 → 실시간 영상 번역'을 누르세요.\n" +
                "2. 영상 언어를 고르고 '번역 시작'을 누르세요.\n" +
                "3. 영상이 열리면 재생하세요. 자막 창은 끌어서 옮길 수 있어요.\n\n" +
                "※ 처음 한 번은 음성 인식·번역 모델(약 40~80MB)을 내려받습니다. Wi‑Fi 권장."
        }
        val startBtn = Button(this).apply {
            text = "▶ 번역 시작"
            setOnClickListener { proceed() }
        }
        val stopBtn = Button(this).apply {
            text = "■ 번역 중지"
            setOnClickListener {
                startService(Intent(this@MainActivity, TranslateService::class.java)
                    .setAction(TranslateService.ACTION_STOP))
                status.text = "번역을 중지했습니다."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(TextView(this@MainActivity).apply {
                text = "실시간 영상 번역"; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
            })
            addView(label("영상 링크"))
            addView(urlInput)
            addView(label("영상 언어 → 한국어"))
            addView(langSpinner)
            addView(startBtn)
            addView(stopBtn)
            addView(progress)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** 다른 앱에서 '공유'로 받은 텍스트에서 링크만 꺼냄 */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.let { urlInput.setText(it.value) }
    }

    /** 필요한 준비를 순서대로 확인하고, 모두 되면 캡처 권한을 요청 */
    private fun proceed() {
        val url = urlInput.text.toString().trim()
        if (!url.startsWith("http")) {
            toast("올바른 영상 링크를 입력하세요"); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), REQ_PERM); return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("'다른 앱 위에 표시'를 허용한 뒤 돌아와서 다시 시작을 누르세요")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        val lang = selectedLang
        if (!ModelManager.isReady(this, lang)) {
            downloadModel(lang); return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun downloadModel(lang: SourceLang) {
        if (downloading) return
        downloading = true
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "${lang.label} 음성 인식 모델 내려받는 중..."
        thread {
            try {
                ModelManager.download(this, lang) { p ->
                    runOnUiThread {
                        progress.progress = p
                        status.text = "${lang.label} 음성 인식 모델 내려받는 중... $p%"
                    }
                }
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "모델 준비 완료!"
                    proceed()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "모델 다운로드 실패: ${e.message}\n인터넷 연결을 확인하고 다시 시도하세요."
                }
            } finally {
                downloading = false
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            proceed()
        } else {
            status.text = "영상 소리를 들으려면 '오디오 녹음' 권한이 필요합니다."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "화면 녹화(소리 캡처) 권한이 거부되었습니다."; return
        }
        startForegroundService(Intent(this, TranslateService::class.java)
            .putExtra(TranslateService.EXTRA_CODE, resultCode)
            .putExtra(TranslateService.EXTRA_DATA, data)
            .putExtra(TranslateService.EXTRA_LANG, langSpinner.selectedItemPosition))
        status.text = "번역 중! 영상을 재생하세요."
        openUrl(urlInput.text.toString().trim())
    }

    /** 크롬으로 열기 (없으면 기본 앱) */
    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome"))
        } catch (e: ActivityNotFoundException) {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (e2: ActivityNotFoundException) { toast("링크를 열 앱이 없습니다") }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
