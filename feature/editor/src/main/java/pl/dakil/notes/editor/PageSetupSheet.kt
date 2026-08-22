package pl.dakil.notes.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.R
import pl.dakil.notes.model.ColorCodec
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PatternType
import pl.dakil.notes.model.ViewMode
import pl.dakil.notes.ui.color.ColorSettingRow

/** Which page colour a picker launched from [PageSetupSheet] is editing. */
enum class PageColorTarget { PAPER, LINES, MARGIN }

// Ranges every length control is clamped to, in points. Generous at the top so unusual paper is
// still reachable, but bounded so a slider stays useful over the values anyone actually wants.
private val MARGIN_RANGE = 0f..283f          // 0–10 cm
private val SPACING_RANGE = 6f..71f          // ≈2–25 mm
private val MARGIN_RULE_RANGE = 0f..170f     // 0–6 cm
private val STAFF_GAP_RANGE = 0f..113f       // 0–4 cm, 0 meaning "work it out from the staff"

/**
 * Page setup: paper size, rule pattern and every colour and length on the sheet.
 *
 * The pattern engine draws these procedurally in the canvas, so a colour or a spacing takes effect
 * the moment it is committed — there is no bitmap to regenerate and nothing to wait for. That is
 * also what lets the paper-type cards preview the real thing rather than a stock illustration.
 *
 * Lengths are set in the user's unit through [LengthRow] rather than on bare sliders. Paper is a
 * physical object: "7 mm ruled" and "a 2 cm margin" are the things people are actually after, and a
 * dimensionless drag can only approximate them. Confirming in a dialog has a second benefit — one
 * document edit per change instead of one per slider sample, so undo steps back over a decision
 * rather than over a gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    unit: MeasurementUnit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pattern = background.pattern

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Pages and Scroll are the same document with the same page breaks and the same
            // printed output — only the furniture differs. A two-state toggle for that does not
            // deserve a labelled block of its own, so it rides on the title line.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.editor_page), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = view == ViewMode.PAGED,
                        onClick = { onViewChange(ViewMode.PAGED) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.editor_pages_paged)) }
                    SegmentedButton(
                        selected = view == ViewMode.CONTINUOUS,
                        onClick = { onViewChange(ViewMode.CONTINUOUS) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.editor_pages_scroll)) }
                }
            }
            Text(
                text = pluralStringResource(R.plurals.editor_page_count_x, pageCount, pageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            SectionLabel(stringResource(R.string.editor_size))
            // Not padded: the cards run to the panel edge so it is obvious the row scrolls.
            SizeCards(selected = size, unit = unit, onSelect = onSizeChange)

            Column(Modifier.padding(horizontal = 24.dp)) {
                ColorSettingRow(
                    label = stringResource(R.string.editor_paper_colour),
                    color = background.color,
                    supporting = ColorCodec.toHex(background.color, includeAlpha = false),
                    onClick = { onEditColor(PageColorTarget.PAPER) },
                    modifier = Modifier.padding(top = 12.dp),
                )
                MarginsRow(
                    margins = margins,
                    unit = unit,
                    range = MARGIN_RANGE,
                    onCommit = onMarginsChange,
                )
            }

            SectionLabel(stringResource(R.string.editor_paper))
            PatternCards(
                background = background,
                pageSize = size,
                onSelect = { type ->
                    onBackgroundChange(
                        background.copy(
                            pattern = pattern.copy(
                                type = type,
                                // Staves need a much wider default gap than ruled paper, or the
                                // five lines collapse into a smear.
                                spacing = if (type == PatternType.STAVES) 8f
                                else pattern.spacing.coerceAtLeast(12f),
                            )
                        )
                    )
                },
            )

            if (pattern.type != PatternType.NONE) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    // No separate strength slider: the line colour carries its own alpha, and two
                    // controls over one visual result only ever disagree.
                    ColorSettingRow(
                        label = stringResource(R.string.editor_line_colour),
                        color = pattern.color,
                        supporting = ColorCodec.toHex(pattern.color, includeAlpha = false),
                        onClick = { onEditColor(PageColorTarget.LINES) },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    LengthRow(
                        label = stringResource(R.string.editor_spacing),
                        valuePt = pattern.spacing,
                        unit = unit,
                        range = SPACING_RANGE,
                        supporting = when (pattern.type) {
                            PatternType.STAVES -> stringResource(R.string.editor_spacing_staves)
                            PatternType.DOTTED -> stringResource(R.string.editor_spacing_dotted)
                            else -> stringResource(R.string.editor_spacing_rules)
                        },
                        onCommit = {
                            onBackgroundChange(background.copy(pattern = pattern.copy(spacing = it)))
                        },
                    )

                    when (pattern.type) {
                        PatternType.RULED -> {
                            LengthRow(
                                label = stringResource(R.string.editor_margin_rule),
                                valuePt = pattern.margin,
                                unit = unit,
                                range = MARGIN_RULE_RANGE,
                                supporting = stringResource(R.string.editor_margin_rule_summary),
                                zeroLabel = stringResource(R.string.editor_none),
                                onCommit = {
                                    onBackgroundChange(
                                        background.copy(pattern = pattern.copy(margin = it))
                                    )
                                },
                            )
                            if (pattern.margin > 0f) {
                                ColorSettingRow(
                                    label = stringResource(R.string.editor_margin_colour),
                                    color = pattern.marginColor,
                                    supporting =
                                        ColorCodec.toHex(pattern.marginColor, includeAlpha = false),
                                    onClick = { onEditColor(PageColorTarget.MARGIN) },
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }

                        PatternType.STAVES -> LengthRow(
                            label = stringResource(R.string.editor_staff_spacing),
                            valuePt = pattern.groupSpacing,
                            unit = unit,
                            range = STAFF_GAP_RANGE,
                            supporting = stringResource(R.string.editor_staff_spacing_summary),
                            // Zero is not "no gap" here — it hands the spacing back to the painter,
                            // which derives it from the staff height so it scales with the rules.
                            zeroLabel = stringResource(R.string.editor_auto),
                            onCommit = {
                                onBackgroundChange(
                                    background.copy(pattern = pattern.copy(groupSpacing = it))
                                )
                            },
                        )

                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )
}
