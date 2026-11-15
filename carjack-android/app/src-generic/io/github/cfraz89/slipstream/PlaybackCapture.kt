package io.github.cfraz89.slipstream

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.IOException

/** Shell-privileged phone output capture. Never opens the microphone or saves audio. */
class PlaybackCapture(private val base: Context?) {
    private var recorder: AudioRecord? = null
    private var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var owner = 0L

    @Synchronized
    fun start(token: Long, sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        stop()
        owner = token
        if (sampleRate !in listOf(24000, 44100, 48000) || channels !in 1..2) return null
        var created: AudioRecord? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            // AudioRecord must attribute this request to the shell UID that Shizuku runs us as.
            val shell = object : ContextWrapper(base) {
                override fun getPackageName() = "com.android.shell"
                override fun getOpPackageName() = "com.android.shell"
                override fun getApplicationContext(): Context = this
                override fun getAttributionSource(): AttributionSource =
                    AttributionSource.Builder(Process.myUid()).setPackageName(packageName).build()
            }
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minimum = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Unsupported capture format" }
            val builder = AudioRecord.Builder()
            // setContext is a framework API; reflection is allowed in the Shizuku shell process.
            AudioRecord.Builder::class.java.getMethod("setContext", Context::class.java)
                .invoke(builder, shell)
            created = builder.setAudioSource(8 /* REMOTE_SUBMIX: phone output, not microphone */)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, sampleRate * channels / 5)).build()
            check(created.state == AudioRecord.STATE_INITIALIZED) { "Capture did not initialize" }
            created.startRecording()
            check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Capture did not start" }
            pipe = ParcelFileDescriptor.createPipe()
            val destination = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            val source = created
            recorder = source
            output = destination
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val bytes = ByteArray(sampleRate / 100 * channels * 2) // 10ms PCM
                try {
                    while (true) {
                        val count = source.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) break
                        destination.write(bytes, 0, count)
                    }
                } catch (e: IOException) {
                    Log.i("carjack", "Audio capture pipe closed")
                } catch (e: RuntimeException) {
                    Log.w("carjack", "Audio capture stopped", e)
                } finally {
                    synchronized(this) {
                        if (recorder === source) { recorder = null; output = null }
                    }
                    source.release()
                    try { destination.close() } catch (ignored: IOException) {}
                }
            }, "highland-audio-capture").start()
            Log.i("carjack", "Phone playback capture started: $sampleRate Hz, $channels channels")
            return pipe[0] // AIDL transfers this descriptor to the app.
        } catch (e: Exception) {
            Log.e("carjack", "Could not capture phone playback", e)
            created?.release()
            pipe?.forEach { try { it.close() } catch (ignored: IOException) {} }
            return null
        }
    }

    @Synchronized
    fun stop(token: Long = owner) {
        if (token != owner) return
        val previous = recorder
        recorder = null
        try { previous?.stop() } catch (ignored: IllegalStateException) {}
        try { output?.close() } catch (ignored: IOException) {}
        output = null
        // The capture thread releases AudioRecord after its final read has returned.
    }
}
