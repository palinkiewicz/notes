package pl.dakil.notes.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PaperTheme
import pl.dakil.notes.model.PaperThemes
import pl.dakil.notes.ui.icons.NotesIcons

/** What can be done to one saved theme, from its long-press menu or a settings row. */
class PaperThemeActions(
    val onApply: (PaperTheme) -> Unit,
    val onSaveCurrent: (name: String) -> Unit,
    val onUpdate: (id: String) -> Unit,
    val onRename: (id: String, name: String) -> Unit,
    val onSetDefault: (id: String?) -> Unit,
    val onDelete: (id: String) -> Unit,
)

/**
 * The saved paper themes, one horizontally scrolling row, led by "Save current".
 *
 * Save comes first rather than last: on a phone the end of the row is off screen, and the first
 * time someone opens this the row has nothing else in it to scroll past.
 *
 * A theme is shown selected when the page's paper is exactly it, so tweaking a colour after
 * applying one visibly lets go of the card — the page is no longer that theme, and the menu's
 * "Update to this page" is how to make it so.
 */
@Composable
fun PaperThemeCards(
    themes: List<PaperTheme>,
    defaultId: String?,
    current: PageBackground,
    pageSize: PageSize,
    actions: PaperThemeActions,
    modifier: Modifier = Modifier,
) {
    var naming by remember { mutableStateOf<NameRequest?>(null) }
    var deleting by remember { mutableStateOf<PaperTheme?>(null) }
    val wearing = PaperThemes.matching(themes, current)
    val suggested = current.pattern.type.label()

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "save") {
            SaveCurrentCard(
                // Already saved: another copy of the same paper is a second card that can drift.
                enabled = wearing == null && themes.size < PaperTheme.MAX_THEMES,
                onClick = { naming = NameRequest.New(uniqueName(suggested, themes)) },
            )
        }
        items(themes, key = { it.id }) { theme ->
            ThemeCard(
                theme = theme,
                pageSize = pageSize,
                selected = theme.id == wearing?.id,
                isDefault = theme.id == defaultId,
                canUpdate = theme.background != current,
                onApply = { actions.onApply(theme) },
                onUpdate = { actions.onUpdate(theme.id) },
                onRename = { naming = NameRequest.Rename(theme) },
                onToggleDefault = { actions.onSetDefault(if (theme.id == defaultId) null else theme.id) },
                onDelete = { deleting = theme },
            )
        }
    }

    naming?.let { request ->
        ThemeNameDialog(
            title = stringResource(
                if (request is NameRequest.Rename) R.string.editor_theme_rename else R.string.editor_theme_save
            ),
            initial = when (request) {
                is NameRequest.New -> request.suggestion
                is NameRequest.Rename -> request.theme.name
            },
            onConfirm = { name ->
                when (request) {
                    is NameRequest.New -> actions.onSaveCurrent(name)
                    is NameRequest.Rename -> actions.onRename(request.theme.id, name)
                }
                naming = null
            },
            onDismiss = { naming = null },
        )
    }

    // Confirmed here rather than undone from a snackbar: this row lives in a modal sheet, and a
    // snackbar would surface on the screen behind it, under the scrim, where nobody would see it.
    deleting?.let { theme ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.editor_theme_delete_title, theme.name)) },
            text = {
                Text(
                    stringResource(
                        if (theme.id == defaultId) R.string.editor_theme_delete_default
                        else R.string.editor_theme_delete_body
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(theme.id)
                    deleting = null
                }) { Text(stringResource(R.string.editor_theme_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.editor_cancel)) }
            },
        )
    }
}

private sealed interface NameRequest {
    data class New(val suggestion: String) : NameRequest
    data class Rename(val theme: PaperTheme) : NameRequest
}

/** "Grid", then "Grid 2": a suggestion that is already taken is one the user has to edit. */
private fun uniqueName(base: String, themes: List<PaperTheme>): String {
    val taken = themes.mapTo(HashSet()) { it.name }
    if (base !in taken) return base
    return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
}

@Composable
private fun SaveCurrentCard(enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(CARD_WIDTH)) {
        OutlinedCard(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(width = CARD_WIDTH, height = CARD_HEIGHT),
        ) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Icon(NotesIcons.Add, contentDescription = null)
            }
        }
        CardLabel(stringResource(R.string.editor_theme_save_current))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeCard(
    theme: PaperTheme,
    pageSize: PageSize,
    selected: Boolean,
    isDefault: Boolean,
    canUpdate: Boolean,
    onApply: () -> Unit,
    onUpdate: () -> Unit,
    onRename: () -> Unit,
    onToggleDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    var menuOpen by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(CARD_WIDTH)) {
        Box {
            PaperPreview(
                background = theme.background,
                pageSize = pageSize,
                modifier = Modifier
                    .size(width = CARD_WIDTH, height = CARD_HEIGHT)
                    .clip(shape)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) scheme.primary else scheme.outlineVariant,
                        shape = shape,
                    )
                    .combinedClickable(
                        onClick = onApply,
                        onLongClick = { menuOpen = true },
                        onLongClickLabel = stringResource(R.string.editor_theme_options),
                    ),
            ) {
                if (isDefault) {
                    // On its own ground, like the pattern labels: the paper can be any colour.
                    Surface(
                        shape = CircleShape,
                        color = scheme.surface.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    ) {
                        Icon(
                            imageVector = NotesIcons.Default,
                            contentDescription = stringResource(R.string.editor_theme_is_default),
                            tint = scheme.primary,
                            modifier = Modifier.padding(3.dp).size(14.dp),
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_theme_update)) },
                    enabled = canUpdate,
                    onClick = { menuOpen = false; onUpdate() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isDefault) R.string.editor_theme_unset_default
                                else R.string.editor_theme_set_default
                            )
                        )
                    },
                    leadingIcon = { Icon(NotesIcons.Default, contentDescription = null) },
                    onClick = { menuOpen = false; onToggleDefault() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_theme_rename)) },
                    leadingIcon = { Icon(NotesIcons.Rename, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_theme_delete)) },
                    leadingIcon = { Icon(NotesIcons.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
        CardLabel(theme.name)
    }
}

/** Under the card rather than on it: a name is the user's and can be long, a pattern label is not. */
@Composable
private fun CardLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
    )
}

/** Asks for a theme's name, with the suggestion selected so typing replaces it. */
@Composable
fun ThemeNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val clean = PaperThemes.cleanName(value.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.text.length <= PaperTheme.MAX_NAME_LENGTH) value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.editor_theme_name)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { clean?.let(onConfirm) },
                ),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { clean?.let(onConfirm) }, enabled = clean != null) {
                Text(stringResource(R.string.editor_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

private val CARD_WIDTH = 96.dp
private val CARD_HEIGHT = 128.dp
