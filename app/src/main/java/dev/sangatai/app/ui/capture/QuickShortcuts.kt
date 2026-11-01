package dev.braindump.splitter.ui.capture

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.braindump.splitter.R

/** Home-screen icons that open the capture sheet directly, skipping the dashboard. */
object QuickShortcuts {

    /** Asks the launcher to pin a one-tap capture icon; the launcher shows its own confirmation. */
    fun pinToHomeScreen(context: Context, voice: Boolean): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

        val shortcut = ShortcutInfoCompat.Builder(context, if (voice) PIN_VOICE_ID else PIN_TYPE_ID)
            .setShortLabel(context.getString(if (voice) R.string.shortcut_voice_short else R.string.shortcut_type_short))
            .setLongLabel(context.getString(if (voice) R.string.shortcut_voice_long else R.string.shortcut_type_long))
            .setIcon(IconCompat.createWithResource(context, if (voice) R.mipmap.ic_quick_voice else R.mipmap.ic_quick_dump))
            .setIntent(CaptureActivity.intent(context, startWithVoice = voice))
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    // Distinct from the manifest shortcut IDs so pinning never collides with them.
    private const val PIN_VOICE_ID = "pinned_voice_dump"
    private const val PIN_TYPE_ID = "pinned_type_dump"
}
