package pl.dakil.notes

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import pl.dakil.notes.format.NoteKind

/**
 * The "new ink note" and "new text note" entries a long press on the launcher icon offers.
 *
 * Dynamic rather than a static `shortcuts.xml`: a static shortcut's intent has to name its target
 * package as a literal, and the debug build's `.debug` suffix would leave it pointing at whichever
 * build is not installed. Published from code, the intent is built against this package, whatever it
 * is called.
 *
 * The cost is that the labels are text, not resource ids, frozen in the language the app was in when
 * they were published. [publish] runs on every activity start for that reason — switching the app's
 * language recreates the activity, so the shortcuts follow it on the next frame.
 */
object LauncherShortcuts {

    /**
     * The intent actions, and with them the shortcut ids.
     *
     * Stable strings, not derived from [NoteKind]'s names: a launcher pins a shortcut by its id, and
     * renaming an enum constant must not orphan a shortcut the user has dragged to their home screen.
     */
    private const val ACTION_NEW_INK = "pl.dakil.notes.action.NEW_INK_NOTE"
    private const val ACTION_NEW_TEXT = "pl.dakil.notes.action.NEW_TEXT_NOTE"

    fun publish(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        // In the order the launcher lists them, nearest the icon first: ink is what this app is for.
        val shortcuts = listOf(
            shortcut(context, ACTION_NEW_INK, R.string.shortcut_new_ink_short, R.string.shortcut_new_ink_long, R.drawable.ic_shortcut_ink, rank = 0),
            shortcut(context, ACTION_NEW_TEXT, R.string.shortcut_new_text_short, R.string.shortcut_new_text_long, R.drawable.ic_shortcut_text, rank = 1),
        )
        // Rate-limited only for an app in the background, and this runs as the activity starts.
        // A launcher that refuses anyway costs nothing worse than the old labels.
        runCatching { manager.dynamicShortcuts = shortcuts }
    }

    /** The kind of note [intent] asks for, if it came from one of these shortcuts. */
    fun requestedKind(intent: Intent): NoteKind? = when (intent.action) {
        ACTION_NEW_INK -> NoteKind.INK
        ACTION_NEW_TEXT -> NoteKind.TEXT
        else -> null
    }

    /** Tells the launcher a shortcut was used, which is what it ranks its suggestions by. */
    fun reportUsed(context: Context, intent: Intent) {
        val action = intent.action ?: return
        context.getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(action)
    }

    private fun shortcut(
        context: Context,
        action: String,
        shortLabel: Int,
        longLabel: Int,
        icon: Int,
        rank: Int,
    ): ShortcutInfo = ShortcutInfo.Builder(context, action)
        .setShortLabel(context.getString(shortLabel))
        .setLongLabel(context.getString(longLabel))
        .setIcon(Icon.createWithResource(context, icon))
        .setRank(rank)
        // Explicit, so it reaches this activity and no other app can claim the action.
        .setIntent(Intent(action, null, context, MainActivity::class.java))
        .build()
}
