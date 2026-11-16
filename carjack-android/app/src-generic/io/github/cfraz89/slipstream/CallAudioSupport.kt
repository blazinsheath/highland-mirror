package io.github.cfraz89.slipstream

import android.media.AudioFormat
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only call-routing capability check in the Shizuku shell process. */
object CallAudioSupport {
    private val scheduled = AtomicBoolean(false)

    fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        Thread({ inspect() }, "highland-call-support").also { it.isDaemon = true; it.start() }
    }

    private fun inspect() {
        try {
            val manager = Class.forName("android.media.AudioManager")
            val downlink = manager.methods.any { it.name == "getCallDownlinkExtractionAudioRecord" &&
                it.parameterTypes.contentEquals(arrayOf(AudioFormat::class.java)) }
            val uplink = manager.methods.any { it.name == "getCallUplinkInjectionAudioTrack" &&
                it.parameterTypes.contentEquals(arrayOf(AudioFormat::class.java)) }
            Log.i("carjack", "Call audio API availability: uid=${Process.myUid()}, downlink=$downlink, uplink=$uplink")

            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "audio") as? IBinder
                ?: throw IllegalStateException("Audio service unavailable")
            val service = Class.forName("android.media.IAudioService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val supported = Class.forName("android.media.IAudioService")
                .getMethod("isPstnCallAudioInterceptable").invoke(service) as Boolean
            Log.i("carjack", "Call audio support probe: pstn_interceptable=$supported")
        } catch (error: Exception) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            Log.w("carjack", "Call audio support probe unavailable: ${cause.javaClass.simpleName}: ${cause.message?.take(180)}")
        }
    }
}
