package pl.dakil.notes.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.ViewMode
import pl.dakil.notes.ui.color.ColorSettingRow
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PagePattern
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PatternType

/** Which page colour a picker launched from [PageSetupSheet] is editing. */
enum class PageColorTarget { PAPER, LINES, MARGIN }

/**
 * How the sheet is presented, and how many pages it currently has.
 *
 * Pages and Scroll are the same document with the same page breaks and the same printed output —
 * only the furniture differs — so this belongs with the other page properties rather than in the
 * app bar competing with undo. Adding, duplicating and removing pages happens on the pages
 * themselves, where you can see which one you are acting on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresentationSection(
    pageCount: Int,
    view: ViewMode,
    onViewChange: (ViewMode) -> Unit,
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text("View", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 4.dp)) {
            SegmentedButton(
                selected = view == ViewMode.PAGED,
                onClick = { onViewChange(ViewMode.PAGED) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Pages") }
            SegmentedButton(
                selected = view == ViewMode.CONTINUOUS,
                onClick = { onViewChange(ViewMode.CONTINUOUS) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Scroll") }
        }
        Text(
            text = if (pageCount == 1) "1 page" else "$pageCount pages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
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
    view: ViewMode,
    onViewChange: (ViewMode) -> Unit,
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

            PresentationSection(pageCount = pageCount, view = view, onViewChange = onViewChange)

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
            ColorSettingRow(
                label = "Paper",
                color = background.color,
                supporting = ColorCodec.toHex(background.color, includeAlpha = false),
                onClick = { onEditColor(PageColorTarget.PAPER) },
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

                // No separate strength slider: the line colour carries its own alpha, and two
                // controls over one visual result only ever disagree.
                ColorSettingRow(
                    label = "Line colour",
                    color = background.pattern.color,
                    supporting = ColorCodec.toHex(background.pattern.color, includeAlpha = false),
                    onClick = { onEditColor(PageColorTarget.LINES) },
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
