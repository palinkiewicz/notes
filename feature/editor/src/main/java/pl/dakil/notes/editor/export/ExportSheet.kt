package pl.dakil.notes.editor.export

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.R
import pl.dakil.notes.model.ExportColorPreset
import pl.dakil.notes.model.ExportFormat
import pl.dakil.notes.model.ExportOptions
import pl.dakil.notes.model.JpegQuality
import pl.dakil.notes.model.PageSelection
import pl.dakil.notes.model.PdfImageQuality
import pl.dakil.notes.model.RasterScale
import pl.dakil.notes.model.RasterSize
import pl.dakil.notes.ui.icons.NotesIcons

/** What happens to an export once it is rendered: written to a file, or handed to another app. */
enum class ExportAction { SAVE, SHARE }

/**
 * Export as PDF, PNG or JPEG, with an optional colour transform and the size of what comes out.
 *
 * The colour section only appears for an ink note: a `.md` note's text has no stored colour to
 * transform — it is theme-rendered — so there is nothing here for the preset to act on. The image
 * quality only appears for a PDF of a note that has pictures, the one case where it changes anything.
 *
 * Every choice but the pages is handed to [onOptionsChange] the moment it is made, so the sheet
 * opens next time as it was left even when it was dismissed without exporting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportSheet(
    isInk: Boolean,
    exporting: Boolean,
    /** How many pages there are to pick from, or null while that is still being worked out. */
    pageCount: Int?,
    /** The choices as last left. */
    initial: ExportOptions,
    /** Whether the note has any pictures, for the PDF's image quality to be worth offering. */
    hasImages: Boolean,
    /** One page's size in points, for saying how many pixels a PNG or JPEG page comes to. */
    pageSizePt: Size,
    onOptionsChange: (ExportOptions) -> Unit,
    onDismiss: () -> Unit,
    /** The pages are 0-based and ascending, or null for every page. */
    onExport: (ExportOptions, ExportAction, pages: List<Int>?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var options by remember { mutableStateOf(initial) }
    fun update(changed: ExportOptions) {
        options = changed
        onOptionsChange(changed)
    }
    var pagesText by remember { mutableStateOf("") }
    var widthText by remember { mutableStateOf(initial.customWidthPx.toString()) }
    val pages = pageCount?.let { PageSelection.parse(pagesText, it) }
    val pagesInvalid = pageCount != null && pages == null
    val widthInvalid = options.format.isRaster && options.rasterSize == RasterSize.CUSTOM &&
        RasterScale.parseWidth(widthText) == null
    val ready = !exporting && pages != null && !widthInvalid
    // Blank is "every page", passed on as null so a note that grows a page while the sheet is open
    // still exports whole.
    fun export(action: ExportAction) = onExport(options, action, pages.takeIf { pagesText.isNotBlank() })

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isInk) {
                SectionLabel(stringResource(R.string.editor_export_colors))
                Column(Modifier.padding(horizontal = 24.dp)) {
                    for (preset in ExportColorPreset.entries) {
                        ColorPresetRow(
                            label = stringResource(preset.labelRes()),
                            selected = options.preset == preset,
                            onClick = { update(options.copy(preset = preset)) },
                        )
                    }
                }
            }

            SectionLabel(stringResource(R.string.editor_export_format))
            SegmentedChoice(
                choices = ExportFormat.entries,
                selected = options.format,
                label = { stringResource(it.labelRes()) },
                onSelect = { update(options.copy(format = it)) },
            )

            if (options.format == ExportFormat.PDF && hasImages) {
                SectionLabel(stringResource(R.string.editor_export_images))
                ChipChoice(
                    choices = PdfImageQuality.entries,
                    selected = options.pdfImages,
                    label = { stringResource(it.labelRes()) },
                    onSelect = { update(options.copy(pdfImages = it)) },
                )
                Hint(stringResource(options.pdfImages.hintRes()))
            }

            if (options.format.isRaster) {
                SectionLabel(stringResource(R.string.editor_export_size))
                ChipChoice(
                    choices = RasterSize.entries,
                    selected = options.rasterSize,
                    label = { stringResource(it.labelRes()) },
                    onSelect = { update(options.copy(rasterSize = it)) },
                )
                if (options.rasterSize == RasterSize.CUSTOM) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = { text ->
                            widthText = text
                            RasterScale.parseWidth(text)?.let { update(options.copy(customWidthPx = it)) }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        singleLine = true,
                        label = { Text(stringResource(R.string.editor_export_width)) },
                        isError = widthInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    )
                }
                if (widthInvalid) {
                    Hint(
                        stringResource(
                            R.string.editor_export_width_invalid,
                            ExportOptions.MIN_WIDTH_PX,
                            ExportOptions.MAX_WIDTH_PX,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    val raster = RasterScale.of(pageSizePt.width, pageSizePt.height, options)
                    // Said when the size was cut down, so the number is never a surprise in the file.
                    val res = if (raster.capped) R.string.editor_export_size_capped else R.string.editor_export_size_pixels
                    Hint(stringResource(res, raster.widthPx, raster.heightPx))
                }
            }

            if (options.format == ExportFormat.JPEG) {
                SectionLabel(stringResource(R.string.editor_export_quality))
                ChipChoice(
                    choices = JpegQuality.entries,
                    selected = options.jpegQuality,
                    label = { stringResource(it.labelRes()) },
                    onSelect = { update(options.copy(jpegQuality = it)) },
                )
            }

            if (options.format == ExportFormat.PNG) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = options.transparent,
                            role = Role.Switch,
                            onValueChange = { update(options.copy(transparent = it)) },
                        )
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.editor_export_transparent), Modifier.weight(1f))
                    Switch(checked = options.transparent, onCheckedChange = null)
                }
            }

            SectionLabel(stringResource(R.string.editor_export_pages))
            OutlinedTextField(
                value = pagesText,
                onValueChange = { pagesText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.editor_export_pages_all)) },
                isError = pagesInvalid,
                supportingText = {
                    when {
                        pagesInvalid -> Text(stringResource(R.string.editor_export_pages_invalid, pageCount!!))
                        pageCount != null -> Text(
                            pluralStringResource(R.plurals.editor_export_pages_hint, pageCount, pageCount),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            )

            // Saving is the main action, so it gets the width; sharing is the same render handed to
            // another app, and sits beside it as the icon everyone already reads as "share".
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { export(ExportAction.SAVE) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.editor_export_action))
                    }
                }
                FilledTonalIconButton(
                    onClick = { export(ExportAction.SHARE) },
                    enabled = ready,
                ) {
                    Icon(NotesIcons.Share, contentDescription = stringResource(R.string.editor_export_share))
                }
            }
        }
    }
}

/** For the format: three peers, not a scale, whose labels fit side by side at phone width. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedChoice(
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        choices.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
            ) { Text(label(choice)) }
        }
    }
}

/**
 * For a choice of a size or a quality — five sizes, or four image qualities whose Polish names are
 * twice their English ones, too long to sit in one segmented row at phone width. Chips wrap where
 * segments would clip, and every such choice in the sheet uses them so the three look alike.
 *
 * Without the minimum touch target: each chip would otherwise reserve 48 dp of height round its
 * 32 dp, and a wrapped row stood a visible 16 dp further from the one above than chips are from
 * each other across a row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipChoice(
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (choice in choices) {
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    label = { Text(label(choice)) },
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

private fun ExportColorPreset.labelRes(): Int = when (this) {
    ExportColorPreset.AS_IS -> R.string.editor_export_as_is
    ExportColorPreset.INVERSION -> R.string.editor_export_inversion
    ExportColorPreset.INTELLIGENT -> R.string.editor_export_intelligent
}

private fun ExportFormat.labelRes(): Int = when (this) {
    ExportFormat.PDF -> R.string.editor_export_pdf
    ExportFormat.PNG -> R.string.editor_export_png
    ExportFormat.JPEG -> R.string.editor_export_jpeg
}

private fun PdfImageQuality.labelRes(): Int = when (this) {
    PdfImageQuality.ORIGINAL -> R.string.editor_export_quality_original
    PdfImageQuality.HIGH -> R.string.editor_export_quality_high
    PdfImageQuality.MEDIUM -> R.string.editor_export_quality_medium
    PdfImageQuality.LOW -> R.string.editor_export_quality_low
}

private fun PdfImageQuality.hintRes(): Int = when (this) {
    PdfImageQuality.ORIGINAL -> R.string.editor_export_images_original_hint
    PdfImageQuality.HIGH -> R.string.editor_export_images_high_hint
    PdfImageQuality.MEDIUM -> R.string.editor_export_images_medium_hint
    PdfImageQuality.LOW -> R.string.editor_export_images_low_hint
}

private fun JpegQuality.labelRes(): Int = when (this) {
    JpegQuality.HIGH -> R.string.editor_export_quality_high
    JpegQuality.MEDIUM -> R.string.editor_export_quality_medium
    JpegQuality.LOW -> R.string.editor_export_quality_low
}

private fun RasterSize.labelRes(): Int = when (this) {
    RasterSize.SMALL -> R.string.editor_export_size_small
    RasterSize.MEDIUM -> R.string.editor_export_size_medium
    RasterSize.LARGE -> R.string.editor_export_size_large
    RasterSize.EXTRA_LARGE -> R.string.editor_export_size_extra_large
    RasterSize.CUSTOM -> R.string.editor_export_size_custom
}

@Composable
private fun ColorPresetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
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
