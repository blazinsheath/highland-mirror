package io.github.cfraz89.slipstream

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager

/** One user-approved projection and one virtual display, reused across vehicle reconnects. */
object MirrorProjection {
    const val RESULT_CODE = "mirror.result_code"
    const val RESULT_DATA = "mirror.result_data"
    @Volatile private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var surfaceOwner = 0L
    @Volatile var active = false
        private set
    @Volatile var attached = false
        private set
    @Volatile var phoneSize = 1 to 1
        private set
    val audio = MirrorHelper.audio

    @Synchronized
    fun start(service: SlipstreamService, intent: Intent?): Boolean {
        if (active) return true
        val code = intent?.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra(RESULT_DATA, Intent::class.java)
        if (code != Activity.RESULT_OK || data == null) return false
        // Android requires approval, then a mediaProjection FGS, then getMediaProjection.
        service.promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val p = checkNotNull(service.getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data))
        val bounds = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        phoneSize = bounds.width() to bounds.height()
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                synchronized(this@MirrorProjection) {
                    if (projection !== p) return
                    release(false)
                }
                Variant.onStatus(service, "Screen sharing ended. Tap Start to approve a new session.", false)
                service.stopSelf()
            }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (projection !== p || width <= 0 || height <= 0) return
                phoneSize = width to height
                MirrorInput.cancel()
                MirrorControlService.cancelTouch()
                Log.i("carjack", "Phone capture resized: ${width}x$height (aspect ratio preserved)")
            }
        }
        p.registerCallback(cb, Handler(Looper.getMainLooper()))
        projection = p
        callback = cb
        active = true
        Log.i("carjack", "User-approved full-phone projection ready; bundled helper handles audio")
        return true
    }

    @Synchronized
    fun open(context: Context, width: Int, height: Int, density: Int, target: Surface): Runnable {
        val p = checkNotNull(projection) { "Screen sharing must be approved again" }
        check(active) { "Screen sharing ended" }
        val owner = ++surfaceOwner
        val old = display
        if (old == null) {
            display = p.createVirtualDisplay("Highland Mirror", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, target, null, null)
        } else {
            // A projection token may create only ONE display; setSurface/resize are reusable.
            old.surface = null
            old.resize(width, height, density)
            old.surface = target
        }
        attached = true
        MirrorInput.start(context)
        Variant.prefs(context).edit().putBoolean("streaming", true).apply()
        return Runnable {
            synchronized(this) {
                if (surfaceOwner == owner) {
                    display?.surface = null
                    attached = false
                    MirrorInput.stop()
                    MirrorControlService.cancelTouch()
                    Variant.prefs(context).edit().putBoolean("streaming", false).apply()
                }
            }
        }
    }

    @Synchronized
    fun stop() = release(true)

    private fun release(stopProjection: Boolean) {
        val p = projection
        projection = null
        active = false
        attached = false
        surfaceOwner++
        MirrorInput.stop()
        audio.stop()
        MirrorControlService.cancelTouch()
        display?.release()
        display = null
        callback?.let { p?.unregisterCallback(it) }
        callback = null
        if (stopProjection) p?.stop()
    }
}
