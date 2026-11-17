package com.himanshu.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import kotlin.math.max
import kotlin.math.sqrt

/**
 * अपना माइक-लूप: धीमी आवाज़ को अपने आप बढ़ाकर (auto gain) Vosk को देता है, ताकि धीरे बोलने पर भी सुनाई दे.
 * बोलते समय (pause) माइक की आवाज़ Vosk तक नहीं जाती, और फिर चालू होते ही Vosk साफ़ (reset) हो जाता है,
 * ताकि हिमांशु अपनी ही आवाज़ को आदेश न समझ ले.
 */
class Mic(private val rec: Recognizer, private val listener: RecognitionListener) {
    @Volatile private var run = false
    @Volatile private var paused = false
    @Volatile private var needReset = false
    private var th: Thread? = null

    fun start() {
        run = true
        th = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    fun setPause(p: Boolean) {
        if (paused && !p) needReset = true
        paused = p
    }

    fun stop() {
        run = false
        try { th?.join(2000) } catch (e: Exception) {}
        th = null
    }

    fun shutdown() {
        stop()
        try { rec.close() } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val sr = 16000
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, 8192) * 4)
        } catch (e: Exception) { listener.onError(e); return }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release(); listener.onError(Exception("माइक की अनुमति या माइक नहीं मिला")); return
        }
        val buf = ShortArray(3200)          // 0.2 सेकंड
        var gain = 4f                       // शुरू में ही थोड़ा बढ़ा हुआ
        try {
            ar.startRecording()
            while (run) {
                val n = ar.read(buf, 0, buf.size)
                if (n < 0) { listener.onError(Exception("माइक पढ़ने में दिक्कत ($n)")); break }
                if (needReset) {
                    needReset = false
                    try { rec.reset() } catch (e: Exception) {}
                }
                if (n == 0 || paused) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                if (rms > 120) {                                  // आवाज़ जैसी चीज़ हो तभी गेन बदलो
                    val want = (2800.0 / rms).toFloat().coerceIn(1f, 12f)
                    gain += (want - gain) * (if (want < gain) 0.6f else 0.25f)
                } else {
                    gain += (3f - gain) * 0.02f                   // शांत में गेन धीरे-धीरे घटाओ, ताकि शोर न बढ़े
                }
                for (i in 0 until n) {
                    val v = buf[i] * gain
                    buf[i] = v.coerceIn(-32000f, 32000f).toInt().toShort()
                }
                if (rec.acceptWaveForm(buf, n)) listener.onResult(rec.result)
                else listener.onPartialResult(rec.partialResult)
            }
        } catch (e: Exception) {
            if (run) listener.onError(e)
        } finally {
            try { ar.stop() } catch (e: Exception) {}
            ar.release()
        }
    }
}
