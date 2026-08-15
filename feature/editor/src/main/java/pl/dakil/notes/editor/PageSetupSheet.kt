package pl.dakil.notes.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.dakil.notes.ui.icons.NotesIcons
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.ui.color.ColorSettingRow
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PatternType

/** Which page colour a picker launched from [PageSetupSheet] is editing. */
enum class PageColorTarget { PAPER_LIGHT, PAPER_DARK, LINES, MARGIN }

/**
 * How many pages the note has, and the controls to change it.
 *
 * Removing is offered but refused rather than hidden when the last page holds something. A control
 * that silently disappears leaves the user hunting for it; one that is visibly disabled *and says
 * why* answers the question on the spot. And the refusal itself matters: decrementing a counter is
 * not a gesture that means "throw that work away", so it must never be able to.
 */
@Composable
private fun PagesSection(
    pageCount: Int,
    contentPageCount: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
) {
    val canRemove = pageCount > 1 && pageCount > contentPageCount

    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Pages", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = if (pageCount == 1) "1 page" else "$pageCount pages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedIconButton(onClick = onRemovePage, enabled = canRemove) {
                    Icon(NotesIcons.RemovePage, contentDescription = "Remove last page")
                }
                FilledIconButton(onClick = onAddPage) {
                    Icon(NotesIcons.AddPage, contentDescription = "Add page")
                }
            }
        }
        if (!canRemove && pageCount > 1) {
            Text(
                text = "The last page has something on it. Clear it to remove the page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Page setup: paper size, rule pattern and every colour on the sheet.
 *
 * The pattern engine draws these procedurally in the canvas, so spacing and opacity are live —
 * there is no bitmap to regenerate and nothing to wait for.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PageSetupSheet(
    size: PageSize,
    background: PageBackground,
    onSizeChange: (PageSize) -> Unit,
    onBackgroundChange: (PageBackground) -> Unit,
    margins: PageMargins,
    onMarginsChange: (PageMargins) -> Unit,
    onEditColor: (PageColorTarget) -> Unit,
    pageCount: Int,
    contentPageCount: Int,
    onAddPage: () -> Unit,
    onRemovePage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Page", style = MaterialTheme.typography.titleLarge)

            PagesSection(
                pageCount = pageCount,
                contentPageCount = contentPageCount,
                onAddPage = onAddPage,
                onRemovePage = onRemovePage,
            )

            Text("Size", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in PageSize.Kind.entries) {
                    FilterChip(
                        selected = (size as? PageSize.Fixed)?.kind == kind,
                        onClick = { onSizeChange(PageSize.Fixed(kind)) },
                        label = { Text(kind.name) },
                    )
                }
            }

            Text(
                text = "Paper",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (type in PatternType.entries) {
                    FilterChip(
                        selected = background.pattern.type == type,
                        onClick = {
                            onBackgroundChange(
                                background.copy(
                                    pattern = background.pattern.copy(
                                        type = type,
                                        // Staves need a much wider default gap than ruled paper,
                                        // or the five lines collapse into a smear.
                                        spacing = if (type == PatternType.STAVES) 8f
                                        else background.pattern.spacing.coerceAtLeast(12f),
                                    )
                                )
                            )
                        },
                        label = { Text(type.label) },
                    )
                }
            }

            Text(
                text = "Margins",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "The column the text flows in. Ink can go anywhere on the sheet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = margins.left,
                onValueChange = { onMarginsChange(PageMargins(it, margins.top, it, margins.bottom)) },
                valueRange = 0f..160f,
            )

            Text(
                text = "Paper colour",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            // Both are exposed rather than "the current one": a note carries its own paper colour
            // for each theme, and hiding one means the user cannot fix a page that looks wrong in
            // the mode they are not currently in.
            ColorSettingRow(
                label = "Light mode",
                color = background.color,
                supporting = ColorCodec.toHex(background.color, includeAlpha = false),
                onClick = { onEditColor(PageColorTarget.PAPER_LIGHT) },
            )
            ColorSettingRow(
                label = "Dark mode",
                color = background.darkColor,
                supporting = ColorCodec.toHex(background.darkColor, includeAlpha = false),
                onClick = { onEditColor(PageColorTarget.PAPER_DARK) },
            )

            if (background.pattern.type != PatternType.NONE) {
                Text(
                    text = "Spacing",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = background.pattern.spacing,
                    onValueChange = {
                        onBackgroundChange(background.copy(pattern = background.pattern.copy(spacing = it)))
                    },
                    valueRange = 6f..64f,
                )

                Text("Line strength", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = background.pattern.opacity,
                    onValueChange = {
                        onBackgroundChange(background.copy(pattern = background.pattern.copy(opacity = it)))
                    },
                    valueRange = 0.05f..1f,
                )

                ColorSettingRow(
                    label = "Line colour",
                    color = background.pattern.color,
                    supporting = ColorCodec.toHex(background.pattern.color, includeAlpha = false),
                    onClick = { onEditColor(PageColorTarget.LINES) },
                )

                ListItem(
                    headlineContent = { Text("Lighten lines on dark paper") },
                    supportingContent = { Text("Turn off to use your chosen colour exactly as picked.") },
                    trailingContent = {
                        Switch(
                            checked = background.adaptPatternToDark,
                            onCheckedChange = { onBackgroundChange(background.copy(adaptPatternToDark = it)) },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

                if (background.pattern.type == PatternType.RULED) {
                    Text("Margin rule", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = background.pattern.margin,
                        onValueChange = {
                            onBackgroundChange(background.copy(pattern = background.pattern.copy(margin = it)))
                        },
                        valueRange = 0f..160f,
                    )
                    if (background.pattern.margin > 0f) {
                        ColorSettingRow(
                            label = "Margin colour",
                            color = background.pattern.marginColor,
                            supporting = ColorCodec.toHex(background.pattern.marginColor, includeAlpha = false),
                            onClick = { onEditColor(PageColorTarget.MARGIN) },
                        )
                    }
                }
            }
        }
    }
}

private val PatternType.label: String
    get() = when (this) {
        PatternType.NONE -> "Plain"
        PatternType.GRID -> "Grid"
        PatternType.RULED -> "Ruled"
        PatternType.DOTTED -> "Dotted"
        PatternType.ISOMETRIC -> "Isometric"
        PatternType.STAVES -> "Staves"
    }
