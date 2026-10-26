package pl.dakil.notes.data

import android.content.Context
import android.content.SharedPreferences

/** Where a note was being looked at when it was last closed. */
sealed interface NoteViewState {
    /** A sheet's pan and zoom, in the same raw terms `SheetTransform` keeps them. */
    data class Ink(val zoom: Float, val offsetX: Float, val offsetY: Float) : NoteViewState

    /** A `.md` note's scroll, in pixels down the editor. */
    data class Text(val scroll: Int) : NoteViewState
}

/**
 * The written form of a [NoteViewState], with the moment it was last used.
 *
 * Plain Kotlin so the format is testable without a device. The timestamp rides along because
 * `SharedPreferences` keeps no order of its own, and the cap needs to know which entry is oldest.
 */
internal object NoteViewStateCodec {

    fun encode(state: NoteViewState, usedAtMs: Long): String = when (state) {
        // `Float.toString`, never a formatter: it is locale-free, where `%f` on a Polish device
        // writes `1,5` and the file reads back as garbage.
        is NoteViewState.Ink -> "i;$usedAtMs;${state.zoom};${state.offsetX};${state.offsetY}"
        is NoteViewState.Text -> "t;$usedAtMs;${state.scroll}"
    }

    fun decode(text: String): NoteViewState? {
        val parts = text.split(';')
        return when (parts.firstOrNull()) {
            "i" -> if (parts.size != 5) null else {
                val zoom = parts[2].toFloatOrNull() ?: return null
                val x = parts[3].toFloatOrNull() ?: return null
                val y = parts[4].toFloatOrNull() ?: return null
                // A NaN x is meaningful — "centred" — so only the zoom and y have to be real.
                if (!zoom.isFinite() || zoom <= 0f || !y.isFinite()) null
                else NoteViewState.Ink(zoom, x, y)
            }
            "t" -> if (parts.size != 3) null else parts[2].toIntOrNull()?.let(NoteViewState::Text)
            else -> null
        }
    }

    fun usedAt(text: String): Long = text.split(';').getOrNull(1)?.toLongOrNull() ?: 0L

    /** The keys to drop so that at most [cap] entries remain, oldest first. */
    fun overflow(entries: Map<String, String>, cap: Int): List<String> {
        if (entries.size <= cap) return emptyList()
        return entries.entries.sortedBy { usedAt(it.value) }
            .take(entries.size - cap)
            .map { it.key }
    }
}

/**
 * Remembers where each note was being looked at, keyed by its ref.
 *
 * Only ever written when the user asked for it — see `AppSettings.rememberInkPosition` and
 * `rememberTextPosition` — and emptied of a kind when that setting is switched off, so a "no" leaves
 * nothing behind on disk. App-private and outside the library: a position is a fact about this
 * device, not about the note, and syncing it would scroll someone else's screen.
 */
class NoteViewStates(context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("view_state", Context.MODE_PRIVATE)

    fun get(ref: StoreRef): NoteViewState? =
        prefs.getString(ref.value, null)?.let(NoteViewStateCodec::decode)

    fun put(ref: StoreRef, state: NoteViewState) {
        val entry = NoteViewStateCodec.encode(state, clock())
        val all = HashMap<String, String>()
        for ((key, value) in prefs.all) if (value is String) all[key] = value
        all[ref.value] = entry
        val editor = prefs.edit().putString(ref.value, entry)
        NoteViewStateCodec.overflow(all, MAX_ENTRIES).forEach(editor::remove)
        editor.apply()
    }

    fun remove(ref: StoreRef) = prefs.edit().remove(ref.value).apply()

    /** Carries an entry over a rename or a move, which hand the same note a new ref. */
    fun move(from: StoreRef, to: StoreRef) {
        val entry = prefs.getString(from.value, null) ?: return
        prefs.edit().remove(from.value).putString(to.value, entry).apply()
    }

    /** Forgets every entry of one kind — what switching the setting off means. */
    fun clear(ink: Boolean) {
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            val state = (value as? String)?.let(NoteViewStateCodec::decode)
            if (state == null || (state is NoteViewState.Ink) == ink) editor.remove(key)
        }
        editor.apply()
    }

    private companion object {
        /** Far more notes than anyone flips between; the oldest positions are the ones forgotten. */
        const val MAX_ENTRIES = 300
    }
}
