package io.github.cfraz89.slipstream

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Forwards live playback to CarPlay, using the exact rate and channels the accessory accepts. */
class AudioStream(private val context: Context, private val session: Long) {
    @Volatile private var running = true
    private val lock = Any()
    private var input: ParcelFileDescriptor.AutoCloseInputStream? = null
    private var worker: Thread? = null

    fun start(sampleRate: Int, channels: Int) {
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                if (Variant.setting(context, "audio", "speakers") == "test") {
                    testTone(sampleRate, channels)
                } else {
                    capture(sampleRate, channels)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e("carjack", "Speaker audio unavailable", e)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        ScreenStream.current?.flash("Audio capture failed: reconnect after restarting Shizuku")
                    }
                }
            }
        }, "highland-audio-send").also { it.start() }
    }

    private fun capture(sampleRate: Int, channels: Int) {
        val descriptor = Variant.openAudio(session, sampleRate, channels)
            ?: throw IOException("Shizuku could not open phone playback")
        val source = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        synchronized(lock) {
            if (!running) { source.close(); Variant.closeAudio(session); return }
            input = source
        }
        try {
            source.use {
                val bytes = ByteArray(sampleRate / 100 * channels * 2)
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                var chunks = 0L
                while (running) {
                    var offset = 0
                    while (running && offset < bytes.size) {
                        val count = it.read(bytes, offset, bytes.size - offset)
                        if (count < 0) throw IOException("Playback capture ended")
                        offset += count
                    }
                    if (!running) break
                    buffer.clear()
                    buffer.put(bytes)
                    Native.pushAudio(session, buffer, bytes.size)
                    if (++chunks % 500 == 0L) Log.i("carjack", "Forwarding phone audio: $chunks PCM chunks")
                }
            }
        } finally {
            synchronized(lock) { input = null }
            Variant.closeAudio(session)
        }
    }

    /** Quiet 440Hz tone for two seconds, followed by silence; never uses phone capture. */
    private fun testTone(sampleRate: Int, channels: Int) {
        val frames = sampleRate / 100
        val buffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        val started = SystemClock.elapsedRealtime()
        var chunk = 0L
        while (running) {
            buffer.clear()
            for (frame in 0 until frames) {
                val index = chunk * frames + frame
                val seconds = index.toDouble() / sampleRate
                val envelope = minOf(1.0, seconds / 0.05, (2.0 - seconds).coerceAtLeast(0.0) / 0.05)
                val value = (sin(2 * PI * 440 * seconds) * 1500 * envelope).toInt().toShort()
                repeat(channels) { buffer.putShort(value) }
            }
            Native.pushAudio(session, buffer, buffer.capacity())
            chunk++
            val delay = started + chunk * 10 - SystemClock.elapsedRealtime()
            if (delay > 0) Thread.sleep(delay)
        }
    }

    fun stop() {
        running = false
        synchronized(lock) {
            try { input?.close() } catch (ignored: IOException) {}
            input = null
        }
        worker?.interrupt()
        Variant.closeAudio(session)
    }
}
