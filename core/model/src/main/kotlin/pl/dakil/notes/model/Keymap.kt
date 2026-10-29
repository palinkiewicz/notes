package pl.dakil.notes.model

import java.util.Locale

/**
 * Where a keyboard shortcut can fire.
 *
 * [TEXT] is wherever a Markdown field holds the caret — a `.md` note, or a text box on the sheet
 * being typed in. It matters more than the other two because a key bound there is a key taken
 * away from typing, which is why [KeyChord.isSafeWhileTyping] guards it.
 */
enum class ShortcutContext { SHEET, TEXT, LIBRARY }

/** How the settings screen and the system's shortcut list group the actions. No labels here. */
enum class ShortcutGroup { GENERAL, TOOLS, SELECTION, VIEW, TEXT, LIBRARY }

/**
 * Something a key can be bound to.
 *
 * Every constant carries an explicit [key] for the saved keymap, never its name or ordinal — see
 * `LocaleSafeKeysTest`. [repeats] says whether holding the key down keeps firing it: worth it for
 * stepping through undo or zoom, a surprise for anything that toggles.
 */
enum class ShortcutAction(
    val key: String,
    val group: ShortcutGroup,
    val contexts: Set<ShortcutContext>,
    val repeats: Boolean,
    val defaults: List<KeyChord>,
) {
    UNDO("undo", ShortcutGroup.GENERAL, EDITORS, true, listOf(ctrl(Keys.Z))),
    REDO("redo", ShortcutGroup.GENERAL, EDITORS, true, listOf(ctrl(Keys.Z, shift = true), ctrl(Keys.Y))),
    SAVE("save", ShortcutGroup.GENERAL, EDITORS, false, listOf(ctrl(Keys.S))),
    CLOSE_NOTE("closeNote", ShortcutGroup.GENERAL, EDITORS, false, listOf(ctrl(Keys.W))),

    /** Out of the text box being typed in; otherwise lets go of the selection. */
    DESELECT("deselect", ShortcutGroup.GENERAL, EDITORS, false, listOf(plain(Keys.ESCAPE))),
    EXPORT("export", ShortcutGroup.GENERAL, EDITORS, false, listOf(ctrl(Keys.P))),
    SHOW_SHORTCUTS("showShortcuts", ShortcutGroup.GENERAL, EVERYWHERE, false, listOf(ctrl(Keys.SLASH))),

    // Digits in toolbar order rather than initials: they are the same in every language.
    PEN("pen", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.DIGIT_1))),
    FOUNTAIN_PEN("fountainPen", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.DIGIT_2))),
    PENCIL("pencil", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.DIGIT_3))),
    HIGHLIGHTER("highlighter", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.DIGIT_4))),
    MARKER("marker", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.DIGIT_5))),

    /** The last eraser used; pressed again while erasing, the other kind. */
    ERASER("eraser", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.E))),
    LASSO("lasso", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.L))),
    TEXT_TOOL("textTool", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.T))),
    IMAGE_TOOL("imageTool", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.I))),
    RULER("ruler", ShortcutGroup.TOOLS, SHEET_ONLY, false, listOf(plain(Keys.R))),

    SELECT_ALL("selectAll", ShortcutGroup.SELECTION, SHEET_ONLY, false, listOf(ctrl(Keys.A))),
    DELETE_SELECTION(
        "deleteSelection", ShortcutGroup.SELECTION, SHEET_ONLY, false,
        listOf(plain(Keys.FORWARD_DELETE), plain(Keys.BACKSPACE)),
    ),
    DUPLICATE_SELECTION("duplicateSelection", ShortcutGroup.SELECTION, SHEET_ONLY, false, listOf(ctrl(Keys.D))),

    // `+` is Shift and `=` on most layouts, a key of its own on others, and on the keypad too.
    ZOOM_IN(
        "zoomIn", ShortcutGroup.VIEW, SHEET_ONLY, true,
        listOf(ctrl(Keys.EQUALS), ctrl(Keys.EQUALS, shift = true), ctrl(Keys.PLUS), ctrl(Keys.NUMPAD_ADD)),
    ),
    ZOOM_OUT("zoomOut", ShortcutGroup.VIEW, SHEET_ONLY, true, listOf(ctrl(Keys.MINUS), ctrl(Keys.NUMPAD_SUBTRACT))),
    ZOOM_FIT("zoomFit", ShortcutGroup.VIEW, SHEET_ONLY, false, listOf(ctrl(Keys.DIGIT_0), ctrl(Keys.NUMPAD_0))),
    PREVIOUS_PAGE("previousPage", ShortcutGroup.VIEW, SHEET_ONLY, true, listOf(plain(Keys.PAGE_UP))),
    NEXT_PAGE("nextPage", ShortcutGroup.VIEW, SHEET_ONLY, true, listOf(plain(Keys.PAGE_DOWN))),
    ADD_PAGE("addPage", ShortcutGroup.VIEW, SHEET_ONLY, false, listOf(ctrl(Keys.ENTER))),
    GO_TO_PAGE("goToPage", ShortcutGroup.VIEW, SHEET_ONLY, false, listOf(ctrl(Keys.G))),

    BOLD("bold", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.B))),
    ITALIC("italic", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.I))),
    STRIKETHROUGH("strikethrough", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.X, shift = true))),
    INLINE_CODE("code", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.E))),
    LINK("link", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.K))),
    HEADING_1("heading1", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.DIGIT_1, alt = true))),
    HEADING_2("heading2", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.DIGIT_2, alt = true))),
    HEADING_3("heading3", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.DIGIT_3, alt = true))),
    PARAGRAPH("paragraph", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.DIGIT_0, alt = true))),
    BULLET_LIST("bulletList", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.DIGIT_8, shift = true))),
    NUMBERED_LIST("numberedList", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.DIGIT_7, shift = true))),
    TASK_LIST("taskList", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(ctrl(Keys.DIGIT_9, shift = true))),

    /** Only inside a list item; anywhere else the key does what it did before. */
    INDENT("indent", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(plain(Keys.TAB))),
    OUTDENT("outdent", ShortcutGroup.TEXT, TEXT_ONLY, false, listOf(plain(Keys.TAB, shift = true))),

    SEARCH("search", ShortcutGroup.LIBRARY, LIBRARY_ONLY, false, listOf(ctrl(Keys.F))),
    NEW_INK_NOTE("newInkNote", ShortcutGroup.LIBRARY, LIBRARY_ONLY, false, listOf(ctrl(Keys.N))),
    NEW_TEXT_NOTE("newTextNote", ShortcutGroup.LIBRARY, LIBRARY_ONLY, false, listOf(ctrl(Keys.N, shift = true)));

    /** Whether this and [other] can ever be live at once, and so must not share a key. */
    fun overlaps(other: ShortcutAction): Boolean = contexts.any { it in other.contexts }

    val firesWhileTyping: Boolean get() = ShortcutContext.TEXT in contexts

    companion object {
        private val VALUES = entries.toTypedArray()

        fun fromKey(key: String): ShortcutAction? = VALUES.firstOrNull { it.key == key }
    }
}

private val EDITORS = setOf(ShortcutContext.SHEET, ShortcutContext.TEXT)
private val EVERYWHERE = ShortcutContext.entries.toSet()
private val SHEET_ONLY = setOf(ShortcutContext.SHEET)
private val TEXT_ONLY = setOf(ShortcutContext.TEXT)
private val LIBRARY_ONLY = setOf(ShortcutContext.LIBRARY)

private fun plain(code: Int, shift: Boolean = false) = KeyChord(code, shift = shift)

private fun ctrl(code: Int, shift: Boolean = false, alt: Boolean = false) =
    KeyChord(code, ctrl = true, shift = shift, alt = alt)

/**
 * One key and the modifiers held with it.
 *
 * [keyCode] is an `android.view.KeyEvent` key code. Those are fixed public-API numbers, so this
 * module can name them as plain ints (see [Keys]) and stay free of Android; only keys [Keys]
 * knows are ever made into a chord, which is what lets every chord be written and read back.
 */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    /**
     * Whether binding this to something that fires mid-typing leaves typing alone.
     *
     * Without Ctrl, Alt or Meta, a key that types a character or moves the caret belongs to the
     * text — Shift included, since Shift+A is a capital A. Tab, Escape and the F-keys type nothing
     * a note would want, so they are fair game on their own.
     */
    val isSafeWhileTyping: Boolean get() = ctrl || alt || meta || Keys.isFree(keyCode)

    /** `ctrl+alt+shift+meta+key`, in that order, with only the modifiers that are held. */
    fun encode(): String = buildString {
        if (ctrl) append("ctrl+")
        if (alt) append("alt+")
        if (shift) append("shift+")
        if (meta) append("meta+")
        append(Keys.nameOf(keyCode))
    }

    companion object {
        /** Reads back what [encode] wrote; null for anything else, including a key [Keys] has dropped. */
        fun decode(text: String): KeyChord? {
            val parts = text.trim().lowercase(Locale.ROOT).split('+')
            if (parts.any { it.isEmpty() }) return null
            val code = Keys.codeOf(parts.last()) ?: return null
            val modifiers = parts.dropLast(1)
            if (modifiers.any { it !in MODIFIERS }) return null
            return KeyChord(
                keyCode = code,
                ctrl = "ctrl" in modifiers,
                shift = "shift" in modifiers,
                alt = "alt" in modifiers,
                meta = "meta" in modifiers,
            )
        }

        private val MODIFIERS = setOf("ctrl", "alt", "shift", "meta")
    }
}

/**
 * The keys a shortcut can use: their `KeyEvent` codes and the names the saved keymap uses for them.
 *
 * Names are lower-case ASCII written here by hand, so nothing about them depends on the device's
 * language. They are never shown — the screen has its own labels.
 */
object Keys {
    const val DIGIT_0 = 7
    const val DIGIT_1 = 8
    const val DIGIT_2 = 9
    const val DIGIT_3 = 10
    const val DIGIT_4 = 11
    const val DIGIT_5 = 12
    const val DIGIT_7 = 14
    const val DIGIT_8 = 15
    const val DIGIT_9 = 16
    const val A = 29
    const val B = 30
    const val D = 32
    const val E = 33
    const val F = 34
    const val G = 35
    const val I = 37
    const val K = 39
    const val L = 40
    const val N = 42
    const val P = 44
    const val R = 46
    const val S = 47
    const val T = 48
    const val W = 51
    const val X = 52
    const val Y = 53
    const val Z = 54
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val COMMA = 55
    const val PERIOD = 56
    const val TAB = 61
    const val SPACE = 62
    const val ENTER = 66
    const val BACKSPACE = 67
    const val GRAVE = 68
    const val MINUS = 69
    const val EQUALS = 70
    const val LEFT_BRACKET = 71
    const val RIGHT_BRACKET = 72
    const val BACKSLASH = 73
    const val SEMICOLON = 74
    const val APOSTROPHE = 75
    const val SLASH = 76
    const val PLUS = 81
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val FORWARD_DELETE = 112
    const val MOVE_HOME = 122
    const val MOVE_END = 123
    const val INSERT = 124
    const val F1 = 131
    const val F12 = 142
    const val NUMPAD_0 = 144
    const val NUMPAD_9 = 153
    const val NUMPAD_DIVIDE = 154
    const val NUMPAD_MULTIPLY = 155
    const val NUMPAD_SUBTRACT = 156
    const val NUMPAD_ADD = 157
    const val NUMPAD_DOT = 158
    const val NUMPAD_ENTER = 160
    const val NUMPAD_EQUALS = 161

    /** What a key is to someone typing; see [KeyChord.isSafeWhileTyping]. */
    private enum class Kind { TYPES, EDITS, FREE }

    private class Entry(val code: Int, val name: String, val kind: Kind)

    private val ENTRIES: List<Entry> = buildList {
        for (i in 0..9) add(Entry(DIGIT_0 + i, "$i", Kind.TYPES))
        for (i in 0 until 26) add(Entry(A + i, "${'a' + i}", Kind.TYPES))
        add(Entry(COMMA, "comma", Kind.TYPES))
        add(Entry(PERIOD, "period", Kind.TYPES))
        add(Entry(GRAVE, "grave", Kind.TYPES))
        add(Entry(MINUS, "minus", Kind.TYPES))
        add(Entry(EQUALS, "equals", Kind.TYPES))
        add(Entry(LEFT_BRACKET, "leftBracket", Kind.TYPES))
        add(Entry(RIGHT_BRACKET, "rightBracket", Kind.TYPES))
        add(Entry(BACKSLASH, "backslash", Kind.TYPES))
        add(Entry(SEMICOLON, "semicolon", Kind.TYPES))
        add(Entry(APOSTROPHE, "apostrophe", Kind.TYPES))
        add(Entry(SLASH, "slash", Kind.TYPES))
        add(Entry(PLUS, "plus", Kind.TYPES))
        add(Entry(SPACE, "space", Kind.TYPES))
        for (i in 0..9) add(Entry(NUMPAD_0 + i, "numpad$i", Kind.TYPES))
        add(Entry(NUMPAD_DIVIDE, "numpadDivide", Kind.TYPES))
        add(Entry(NUMPAD_MULTIPLY, "numpadMultiply", Kind.TYPES))
        add(Entry(NUMPAD_SUBTRACT, "numpadSubtract", Kind.TYPES))
        add(Entry(NUMPAD_ADD, "numpadAdd", Kind.TYPES))
        add(Entry(NUMPAD_DOT, "numpadDot", Kind.TYPES))
        add(Entry(NUMPAD_EQUALS, "numpadEquals", Kind.TYPES))

        add(Entry(ENTER, "enter", Kind.EDITS))
        add(Entry(NUMPAD_ENTER, "numpadEnter", Kind.EDITS))
        add(Entry(BACKSPACE, "backspace", Kind.EDITS))
        add(Entry(FORWARD_DELETE, "delete", Kind.EDITS))
        add(Entry(INSERT, "insert", Kind.EDITS))
        add(Entry(DPAD_UP, "up", Kind.EDITS))
        add(Entry(DPAD_DOWN, "down", Kind.EDITS))
        add(Entry(DPAD_LEFT, "left", Kind.EDITS))
        add(Entry(DPAD_RIGHT, "right", Kind.EDITS))
        add(Entry(MOVE_HOME, "home", Kind.EDITS))
        add(Entry(MOVE_END, "end", Kind.EDITS))
        add(Entry(PAGE_UP, "pageUp", Kind.EDITS))
        add(Entry(PAGE_DOWN, "pageDown", Kind.EDITS))

        add(Entry(TAB, "tab", Kind.FREE))
        add(Entry(ESCAPE, "escape", Kind.FREE))
        for (i in 0 until 12) add(Entry(F1 + i, "f${i + 1}", Kind.FREE))
    }

    private val BY_CODE = ENTRIES.associateBy { it.code }
    private val BY_NAME = ENTRIES.associateBy { it.name.lowercase(Locale.ROOT) }

    /** Whether a shortcut can use this key at all. Modifiers, media and system keys cannot. */
    fun isKnown(code: Int): Boolean = code in BY_CODE

    fun nameOf(code: Int): String = BY_CODE[code]?.name ?: "unknown"

    fun codeOf(name: String): Int? = BY_NAME[name.lowercase(Locale.ROOT)]?.code

    internal fun isFree(code: Int): Boolean = BY_CODE[code]?.kind == Kind.FREE

    /** The number of an F-key, 1 to 12, or null for any other key. */
    fun functionNumber(code: Int): Int? = if (code in F1..F12) code - F1 + 1 else null
}

/**
 * Which keys do what: every [ShortcutAction] with the chords bound to it.
 *
 * Always valid, whatever it was built from:
 * - no chord does two things in one context — two actions that can be live at once never share
 *   one, while a tool key on the sheet and a library key can;
 * - nothing that fires mid-typing is bound to a key that types (see [KeyChord.isSafeWhileTyping]).
 *
 * A preference of this device, like the drawing order, and saved like it: only what differs from
 * [DEFAULT] is written, so a better default shipped later still reaches anyone who never changed it.
 */
class Keymap private constructor(private val bound: Map<ShortcutAction, List<KeyChord>>) {

    private val index: Map<ShortcutContext, Map<KeyChord, ShortcutAction>> =
        ShortcutContext.entries.associateWith { context ->
            buildMap {
                for ((action, chords) in bound) {
                    if (context in action.contexts) for (chord in chords) put(chord, action)
                }
            }
        }

    fun bindings(action: ShortcutAction): List<KeyChord> = bound[action].orEmpty()

    /** What [chord] does in [context], if anything. */
    fun actionFor(chord: KeyChord, context: ShortcutContext): ShortcutAction? = index[context]?.get(chord)

    /** The action [chord] would be taken from if it were given to [action]. */
    fun holderOf(chord: KeyChord, action: ShortcutAction): ShortcutAction? =
        bound.entries.firstOrNull { (other, chords) ->
            other != action && other.overlaps(action) && chord in chords
        }?.key

    /**
     * Binds [chord] to [action] as well as whatever it already has, taking it from any action it
     * would clash with — the user pressed it for this one, which is the clearer intent.
     * Unchanged when [accepts] says no.
     */
    fun assign(action: ShortcutAction, chord: KeyChord): Keymap {
        if (!accepts(action, chord) || chord in bindings(action)) return this
        val out = bound.mapValuesTo(LinkedHashMap()) { (other, chords) ->
            if (other != action && other.overlaps(action)) chords - chord else chords
        }
        out[action] = bindings(action) + chord
        return Keymap(out)
    }

    fun unbind(action: ShortcutAction, chord: KeyChord): Keymap {
        if (chord !in bindings(action)) return this
        return Keymap(bound + (action to bindings(action) - chord))
    }

    /**
     * Back to [action]'s own defaults, taking them back from whatever was given them meanwhile.
     * Restoring "undo" should make Ctrl+Z undo, not quietly leave it on the action that took it.
     */
    fun reset(action: ShortcutAction): Keymap {
        var out = Keymap(bound + (action to emptyList()))
        for (chord in action.defaults) out = out.assign(action, chord)
        return out
    }

    fun isDefault(action: ShortcutAction): Boolean = bindings(action) == action.defaults

    val isDefault: Boolean get() = ShortcutAction.entries.all(::isDefault)

    /** `bold=ctrl+b|ctrl+shift+b;lasso=` — only the actions that differ, an empty list meaning none. */
    fun encode(): String = ShortcutAction.entries
        .filterNot(::isDefault)
        .joinToString(";") { action -> action.key + "=" + bindings(action).joinToString("|") { it.encode() } }

    override fun equals(other: Any?): Boolean = other is Keymap && other.bound == bound

    override fun hashCode(): Int = bound.hashCode()

    override fun toString(): String = "Keymap(${encode()})"

    companion object {
        val DEFAULT: Keymap = Keymap(ShortcutAction.entries.associateWith { it.defaults })

        /** Whether [chord] may be bound to [action] at all; see the class's invariants. */
        fun accepts(action: ShortcutAction, chord: KeyChord): Boolean =
            Keys.isKnown(chord.keyCode) && (!action.firesWhileTyping || chord.isSafeWhileTyping)

        /**
         * Reads back what [encode] wrote. Never fails: unknown actions and keys are dropped, and an
         * action the text does not mention keeps its default — less any key the user has since
         * given to something else, which is their choice and stays made.
         */
        fun decode(text: String?): Keymap {
            if (text.isNullOrBlank()) return DEFAULT
            val chosen = LinkedHashMap<ShortcutAction, List<KeyChord>>()
            for (entry in text.split(';')) {
                val eq = entry.indexOf('=')
                if (eq < 0) continue
                val action = ShortcutAction.fromKey(entry.substring(0, eq).trim()) ?: continue
                val chords = entry.substring(eq + 1).split('|')
                    .filter { it.isNotBlank() }
                    .mapNotNull(KeyChord::decode)
                    .filter { accepts(action, it) }
                    .distinct()
                chosen[action] = chords
            }
            // Chosen bindings claim their keys first, then defaults take what is left.
            val order = chosen.keys + ShortcutAction.entries.filter { it !in chosen }
            val out = LinkedHashMap<ShortcutAction, List<KeyChord>>()
            for (action in order) {
                val wanted = chosen[action] ?: action.defaults
                out[action] = wanted.filter { chord ->
                    out.none { (other, chords) -> other.overlaps(action) && chord in chords }
                }
            }
            return Keymap(ShortcutAction.entries.associateWith { out[it].orEmpty() })
        }
    }
}
