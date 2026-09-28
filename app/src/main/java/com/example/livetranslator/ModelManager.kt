package com.example.livetranslator

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** 음성 인식 모델을 처음 한 번만 내려받아 앱 저장소에 풀어 둠 */
object ModelManager {
    fun modelDir(ctx: Context, lang: SourceLang) = File(ctx.filesDir, lang.model)
    fun isReady(ctx: Context, lang: SourceLang) = File(modelDir(ctx, lang), ".ready").exists()

    fun download(ctx: Context, lang: SourceLang, onProgress: (Int) -> Unit) {
        val zip = File(ctx.cacheDir, "${lang.model}.zip")
        val conn = URL(lang.url).openConnection() as HttpURLConnection
        conn.connect()
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            zip.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
        }
        val root = ctx.filesDir.canonicalPath
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(ctx.filesDir, e.name)
                if (!f.canonicalPath.startsWith(root)) throw IOException("잘못된 압축 파일")
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
        File(modelDir(ctx, lang), ".ready").createNewFile()
    }
}
