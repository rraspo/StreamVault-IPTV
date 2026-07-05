package com.streamvault.app.util

import android.content.Context
import android.content.Intent

/**
 * Fully restarts the app process so singletons that read a preference only at
 * construction time (notably the Hilt-provided PlayerEngine, which picks VLC vs
 * ExoPlayer once at startup) are rebuilt with the new value.
 */
object AppRestarter {
    fun restart(context: Context) {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        context.startActivity(launchIntent)
        // Tear the current process down so the relaunched task starts a fresh one.
        Runtime.getRuntime().exit(0)
    }
}
