package pl.dakil.notes.ui.keys

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.dakil.notes.model.KeyChord
import pl.dakil.notes.model.Keys
import pl.dakil.notes.ui.R
import java.util.Locale

/**
 * The chord this key press makes, or null when it is no shortcut at all.
 *
 * Null for a modifier pressed on its own, and for any key [Keys] does not know. Null, too, for
 * right Alt held without Ctrl: that is AltGr, and on a Polish keyboard AltGr+A is how an `ą` is
 * typed — a shortcut must never stand between someone and their own alphabet.
 */
fun KeyEvent.toChord(): KeyChord? {
    if (!Keys.isKnown(keyCode)) return null
    val altGr = metaState and KeyEvent.META_ALT_RIGHT_ON != 0 &&
        metaState and KeyEvent.META_ALT_LEFT_ON == 0 && !isCtrlPressed
    if (altGr) return null
    return KeyChord(
        keyCode = keyCode,
        ctrl = isCtrlPressed,
        shift = isShiftPressed,
        alt = isAltPressed,
        meta = isMetaPressed,
    )
}

/** The modifiers of this chord as `KeyEvent` meta flags, the way `KeyboardShortcutInfo` wants them. */
fun KeyChord.metaState(): Int =
    (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
        (if (shift) KeyEvent.META_SHIFT_ON else 0) or
        (if (alt) KeyEvent.META_ALT_ON else 0) or
        (if (meta) KeyEvent.META_META_ON else 0)

/** Each key of the chord as it is printed on a keyboard, modifiers first. */
@Composable
fun KeyChord.keyLabels(): List<String> = modifierLabels(ctrl, alt, shift, meta) + keyLabel(keyCode)

/** The modifiers held, in the order [KeyChord.encode] writes them, for a chord not yet finished. */
@Composable
fun modifierLabels(ctrl: Boolean, alt: Boolean, shift: Boolean, meta: Boolean): List<String> = buildList {
    if (ctrl) add(stringResource(R.string.ui_key_ctrl))
    if (alt) add(stringResource(R.string.ui_key_alt))
    if (shift) add(stringResource(R.string.ui_key_shift))
    if (meta) add(stringResource(R.string.ui_key_meta))
}

/** The whole chord as one line, `Ctrl+Shift+Z`, for screen readers and plain text. */
@Composable
fun KeyChord.label(): String = keyLabels().joinToString("+")

@Composable
private fun keyLabel(code: Int): String {
    Keys.functionNumber(code)?.let { return stringResource(R.string.ui_key_function_x, it) }
    val named = when (code) {
        Keys.SPACE -> R.string.ui_key_space
        Keys.ENTER -> R.string.ui_key_enter
        Keys.NUMPAD_ENTER -> R.string.ui_key_numpad_enter
        Keys.BACKSPACE -> R.string.ui_key_backspace
        Keys.FORWARD_DELETE -> R.string.ui_key_delete
        Keys.INSERT -> R.string.ui_key_insert
        Keys.TAB -> R.string.ui_key_tab
        Keys.ESCAPE -> R.string.ui_key_escape
        Keys.MOVE_HOME -> R.string.ui_key_home
        Keys.MOVE_END -> R.string.ui_key_end
        Keys.PAGE_UP -> R.string.ui_key_page_up
        Keys.PAGE_DOWN -> R.string.ui_key_page_down
        else -> null
    }
    if (named != null) return stringResource(named)
    val numpad = when (code) {
        in Keys.NUMPAD_0..Keys.NUMPAD_9 -> "${code - Keys.NUMPAD_0}"
        Keys.NUMPAD_ADD -> "+"
        Keys.NUMPAD_SUBTRACT -> "−"
        Keys.NUMPAD_MULTIPLY -> "×"
        Keys.NUMPAD_DIVIDE -> "÷"
        Keys.NUMPAD_DOT -> "."
        Keys.NUMPAD_EQUALS -> "="
        else -> null
    }
    if (numpad != null) return stringResource(R.string.ui_key_numpad_x, numpad)
    // What is printed on the key cap itself: a letter, a digit, a glyph — the same in any language.
    return GLYPHS[code] ?: Keys.nameOf(code).uppercase(Locale.ROOT)
}

private val GLYPHS: Map<Int, String> = buildMap {
    put(Keys.DPAD_UP, "↑")
    put(Keys.DPAD_DOWN, "↓")
    put(Keys.DPAD_LEFT, "←")
    put(Keys.DPAD_RIGHT, "→")
    put(Keys.COMMA, ",")
    put(Keys.PERIOD, ".")
    put(Keys.GRAVE, "`")
    put(Keys.MINUS, "-")
    put(Keys.EQUALS, "=")
    put(Keys.LEFT_BRACKET, "[")
    put(Keys.RIGHT_BRACKET, "]")
    put(Keys.BACKSLASH, "\\")
    put(Keys.SEMICOLON, ";")
    put(Keys.APOSTROPHE, "'")
    put(Keys.SLASH, "/")
    put(Keys.PLUS, "+")
}

/**
 * A chord drawn as the keys to press, each in a small key cap — how a keyboard shortcut is
 * recognised at a glance, rather than read.
 */
@Composable
fun KeyCaps(chord: KeyChord, modifier: Modifier = Modifier) = KeyCaps(chord.keyLabels(), modifier)

/** Key caps for [keys] as given — a chord still being pressed, say, with the modifiers so far. */
@Composable
fun KeyCaps(keys: List<String>, modifier: Modifier = Modifier) {
    val description = keys.joinToString("+")
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (key in keys) KeyCap(key)
    }
}

@Composable
private fun KeyCap(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Outlined so a lone letter reads as a key to press rather than as a badge or a count.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 24.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
