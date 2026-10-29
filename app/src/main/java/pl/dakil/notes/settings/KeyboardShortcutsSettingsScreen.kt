package pl.dakil.notes.settings

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.dakil.notes.R
import pl.dakil.notes.model.KeyChord
import pl.dakil.notes.model.Keymap
import pl.dakil.notes.model.ShortcutAction
import pl.dakil.notes.model.ShortcutGroup
import pl.dakil.notes.ui.components.flatTopAppBarColors
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.keys.KeyCaps
import pl.dakil.notes.ui.keys.label
import pl.dakil.notes.ui.keys.modifierLabels
import pl.dakil.notes.ui.keys.toChord
import pl.dakil.notes.ui.R as UiR

/**
 * Which key does what, with every shortcut one tap from being changed.
 *
 * Laid out the way the shortcuts are met: grouped by where they work, since that — on the sheet, or
 * while typing — is what decides which keys a shortcut may use at all. Each row shows its keys as
 * the key caps to press, because a shortcut is recognised by its shape on the keyboard long before
 * its name is read.
 *
 * Changing one is a dialog that records the next key pressed. It lives in a dialog not for
 * ceremony but because a dialog is a window of its own: the app's shortcuts are dispatched by the
 * activity's window, so none of them can fire while a key is being recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardShortcutsSettingsScreen(
    keymap: Keymap,
    onChange: (Keymap) -> Unit,
    onNavigateBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<ShortcutAction?>(null) }
    var confirmingReset by remember { mutableStateOf(false) }
    val hasKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    editing?.let { action ->
        ShortcutDialog(
            action = action,
            keymap = keymap,
            canRecord = hasKeyboard,
            onChange = onChange,
            onDismiss = { editing = null },
        )
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            icon = { Icon(NotesIcons.Restore, contentDescription = null) },
            title = { Text(stringResource(R.string.keymap_reset_all_title)) },
            text = { Text(stringResource(R.string.keymap_reset_all_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingReset = false
                        onChange(Keymap.DEFAULT)
                    },
                ) { Text(stringResource(R.string.keymap_reset_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text(stringResource(UiR.string.ui_cancel)) }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_keyboard_shortcuts)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(NotesIcons.Back, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(onClick = { confirmingReset = true }, enabled = !keymap.isDefault) {
                        Text(stringResource(R.string.keymap_reset_action))
                    }
                },
                colors = flatTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "intro") {
                Intro(keymap = keymap, hasKeyboard = hasKeyboard)
            }
            for (group in ShortcutGroup.entries) {
                val actions = ShortcutAction.entries.filter { it.group == group }
                item(key = "header-${group.name}") { GroupHeader(group) }
                items(actions, key = { it.key }) { action ->
                    ShortcutRow(
                        action = action,
                        keymap = keymap,
                        shape = groupShape(first = action == actions.first(), last = action == actions.last()),
                        onClick = { editing = action },
                    )
                }
            }
        }
    }
}

@Composable
private fun Intro(keymap: Keymap, hasKeyboard: Boolean) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val overview = keymap.bindings(ShortcutAction.SHOW_SHORTCUTS).firstOrNull()
        val description = stringResource(R.string.keymap_description)
        Text(
            text = if (overview == null) description
            else description + " " + stringResource(R.string.keymap_description_overview_x, overview.label()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Said up front rather than discovered at the record button: without a keyboard, this whole
        // screen is setting up for later, and that is worth knowing before anything is tapped.
        if (!hasKeyboard) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(NotesIcons.Keyboard, contentDescription = null)
                    Text(stringResource(R.string.keymap_no_keyboard), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: ShortcutGroup) {
    Column(Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(group.titleRes()),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = group.whereLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One shortcut: its name, and its first key as key caps.
 *
 * Only the first, with a count for the rest, so every row is one line tall and the caps line up
 * down the right edge; the dialog shows them all.
 */
@Composable
private fun ShortcutRow(action: ShortcutAction, keymap: Keymap, shape: Shape, onClick: () -> Unit) {
    val chords = keymap.bindings(action)
    val changed = !keymap.isDefault(action)
    Column(Modifier.padding(top = 2.dp)) {
        ListItem(
            headlineContent = { Text(action.label()) },
            supportingContent = if (changed) {
                {
                    Text(
                        text = stringResource(R.string.keymap_custom),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                null
            },
            trailingContent = {
                val first = chords.firstOrNull()
                if (first == null) {
                    Text(
                        text = stringResource(R.string.keymap_none),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KeyCaps(first)
                        if (chords.size > 1) {
                            Text(
                                text = stringResource(R.string.keymap_more_x, chords.size - 1),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick),
        )
    }
}

/** A group's rows as one card, rounded at its ends and joined in the middle, as Drawing order does. */
private fun groupShape(first: Boolean, last: Boolean): Shape {
    val outer = 20.dp
    val inner = 4.dp
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        topEnd = if (first) outer else inner,
        bottomStart = if (last) outer else inner,
        bottomEnd = if (last) outer else inner,
    )
}

/** What the recorder has to say about the key it was just given. */
private sealed interface Recorded {
    /** A key that types, offered to something that fires while typing. */
    data class Refused(val chord: KeyChord) : Recorded

    /** A key already doing something else where this one works too. */
    data class Taken(val chord: KeyChord, val holder: ShortcutAction) : Recorded
}

/**
 * Every key of one shortcut: each as a chip to remove, a recorder to add another, and a way back
 * to the defaults. Changes are saved as they are made — Done only closes — so there is no state in
 * which the screen behind and the dialog disagree.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutDialog(
    action: ShortcutAction,
    keymap: Keymap,
    canRecord: Boolean,
    onChange: (Keymap) -> Unit,
    onDismiss: () -> Unit,
) {
    var recording by remember(action) { mutableStateOf(false) }
    var recorded by remember(action) { mutableStateOf<Recorded?>(null) }
    val chords = keymap.bindings(action)

    fun offer(chord: KeyChord) {
        val holder = keymap.holderOf(chord, action)
        recorded = when {
            !Keymap.accepts(action, chord) -> Recorded.Refused(chord)
            holder != null -> Recorded.Taken(chord, holder)
            else -> {
                onChange(keymap.assign(action, chord))
                recording = false
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NotesIcons.Keyboard, contentDescription = null) },
        title = { Text(action.label(), textAlign = TextAlign.Center) },
        text = {
            Column(
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    action.hint()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        text = action.group.whereLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (chords.isEmpty()) {
                    Text(
                        text = stringResource(R.string.keymap_no_keys),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (chord in chords) {
                            val name = chord.label()
                            InputChip(
                                selected = false,
                                onClick = { onChange(keymap.unbind(action, chord)) },
                                label = { KeyCaps(chord, Modifier.padding(vertical = 6.dp)) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = NotesIcons.Close,
                                        contentDescription = stringResource(R.string.keymap_remove_x, name),
                                        modifier = Modifier.size(InputChipDefaults.IconSize),
                                    )
                                },
                            )
                        }
                    }
                }

                AnimatedContent(
                    targetState = recording,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "recorder",
                ) { listening ->
                    if (listening) {
                        Recorder(onChord = ::offer, onCancel = {
                            recording = false
                            recorded = null
                        })
                    } else {
                        FilledTonalButton(
                            onClick = {
                                recorded = null
                                recording = true
                            },
                            enabled = canRecord,
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(
                                imageVector = NotesIcons.Add,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.keymap_add_key))
                        }
                    }
                }

                recorded?.let { message ->
                    RecordedMessage(
                        message = message,
                        onReplace = { chord ->
                            onChange(keymap.assign(action, chord))
                            recorded = null
                            recording = false
                        },
                    )
                }

                Text(
                    text = if (action.defaults.isEmpty()) stringResource(R.string.keymap_default_none)
                    else stringResource(
                        R.string.keymap_default_x,
                        action.defaults.map { it.label() }.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.ui_done)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onChange(keymap.reset(action))
                    recorded = null
                    recording = false
                },
                enabled = !keymap.isDefault(action),
            ) { Text(stringResource(R.string.keymap_reset_default)) }
        },
    )
}

/**
 * The field that listens for the next key.
 *
 * Shows the modifiers as they go down, so holding Ctrl and Shift reads "Ctrl Shift …" before the
 * last key is found — the recording is visibly *happening*, rather than a box that might be broken.
 * The outline breathes for the same reason.
 */
@Composable
private fun Recorder(onChord: (KeyChord) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    var held by remember { mutableStateOf(emptyList<Boolean>()) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val pulse = rememberInfiniteTransition(label = "recording")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "outline",
    )

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                held = listOf(native.isCtrlPressed, native.isAltPressed, native.isShiftPressed, native.isMetaPressed)
                if (event.type == KeyEventType.KeyDown) {
                    native.toChord()?.let(onChord)
                }
                // Everything, Tab and Escape included: a key the recorder let through would move
                // focus or close the dialog instead of being recorded. Back is not a key a shortcut
                // can use, so it still leaves.
                native.keyCode != android.view.KeyEvent.KEYCODE_BACK
            }
            .focusable(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.keymap_listening),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val modifiers = if (held.size == 4) modifierLabels(held[0], held[1], held[2], held[3]) else emptyList()
            if (modifiers.isEmpty()) {
                Text(
                    text = stringResource(R.string.keymap_listening_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                KeyCaps(modifiers + "…")
            }
            TextButton(onClick = onCancel) { Text(stringResource(UiR.string.ui_cancel)) }
        }
    }
}

@Composable
private fun RecordedMessage(message: Recorded, onReplace: (KeyChord) -> Unit) {
    val (text, chord) = when (message) {
        is Recorded.Refused -> stringResource(R.string.keymap_refused_x, message.chord.label()) to null
        is Recorded.Taken -> stringResource(
            R.string.keymap_conflict_x_y,
            message.chord.label(),
            message.holder.label(),
        ) to message.chord
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (chord == null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (chord == null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (chord == null) 12.dp else 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(NotesIcons.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            }
            if (chord != null) {
                TextButton(onClick = { onReplace(chord) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.keymap_conflict_replace))
                }
            }
        }
    }
}
