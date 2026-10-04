package ai.meteor.kcode.plugin

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.test.runner.AndroidJUnitRunner

/** Window assertions need an interactive display throughout the APK-loading suite. */
class NativeWindowTestRunner : AndroidJUnitRunner() {
    private var screenLease: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    override fun onStart() {
        val power = targetContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        screenLease = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "kcode:plugin-window-tests",
        ).also { it.acquire(30 * 60 * 1_000L) }
        try {
            super.onStart()
        } catch (error: Throwable) {
            releaseScreen()
            throw error
        }
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        releaseScreen()
        super.finish(resultCode, results)
    }

    private fun releaseScreen() {
        screenLease?.let { if (it.isHeld) it.release() }
        screenLease = null
    }
}
