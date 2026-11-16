package io.github.cfraz89.slipstream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.IOException

/** User-consented playback capture only; never opens a microphone, call, shell or audio file. */
class MirrorPlaybackCapture {
    private var recorder: AudioRecord? = null
    private var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var owner = 0L

    @Synchronized
    fun start(token: Long, sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        stop()
        owner = token
        val projection = MirrorProjection.capture() ?: return null
        if (sampleRate !in listOf(24000, 44100, 48000) || channels !in 1..2) return null
        var created: AudioRecord? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minimum = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val source = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, sampleRate * channels / 5)).build()
            created = source
            check(source.state == AudioRecord.STATE_INITIALIZED)
            source.startRecording()
            check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            pipe = ParcelFileDescriptor.createPipe()
            val destination = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            recorder = source
            output = destination
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val bytes = ByteArray(sampleRate / 100 * channels * 2)
                try {
                    while (true) {
                        val count = source.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                        if (count <= 0) break
                        destination.write(bytes, 0, count)
                    }
                } catch (e: IOException) {
                    Log.i("carjack", "Playback capture pipe closed")
                } catch (e: RuntimeException) {
                    Log.w("carjack", "Playback capture stopped", e)
                } finally {
                    synchronized(this) {
                        if (recorder === source) { recorder = null; output = null }
                    }
                    source.release()
                    try { destination.close() } catch (ignored: IOException) {}
                }
            }, "mirror-playback-capture").start()
            Log.i("carjack", "MediaProjection playback capture: $sampleRate Hz, $channels channels")
            return pipe[0]
        } catch (e: Exception) {
            Log.e("carjack", "Eligible media playback capture unavailable", e)
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
    }
}
