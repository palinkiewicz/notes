package pl.dakil.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pl.dakil.notes.editor.canvas.SheetPainter.drawPattern
import pl.dakil.notes.model.MeasurementUnit
import pl.dakil.notes.model.PageBackground
import pl.dakil.notes.model.PageFormat
import pl.dakil.notes.model.PageMargins
import pl.dakil.notes.model.PageSize
import pl.dakil.notes.model.PatternType

private val CARD_SHAPE_RADIUS = 12.dp

/**
 * How much paper a pattern preview shows, in points.
 *
 * A crop, not a shrunk page. Scaling a whole A4 into a thumbnail drops [SheetPainter]'s sub-pixel
 * rule coverage to about a fifth, and the cards come out as blank grey rectangles — and the rules
 * would be too close together to tell grid from dots anyway. At this width the default 56 pt margin
 * rule also falls inside the frame, so ruled paper looks like ruled paper.
 */
private const val PREVIEW_CROP_PT = 180f

/**
 * The paper sizes, one horizontally scrolling row.
 *
 * A wrapping grid of chips would be four rows tall once the B series is on it, and would push
 * everything below out of reach. The families are kept apart by a divider rather than by tabs: the
 * whole list stays one gesture away, and nobody has to know which tab Legal lives under.
 */
@Composable
fun SizeCards(
    selected: PageSize,
    unit: MeasurementUnit,
    onSelect: (PageSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kinds = PageSize.Kind.entries
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(kinds, key = { _, kind -> kind.name }) { i, kind ->
            if (i > 0 && kinds[i - 1].group != kind.group) {
                VerticalDivider(Modifier.height(48.dp).padding(end = 8.dp))
            }
            SizeCard(
                kind = kind,
                unit = unit,
                selected = (selected as? PageSize.Fixed)?.kind == kind,
                onClick = { onSelect(PageSize.Fixed(kind)) },
            )
        }
    }
}

@Composable
private fun SizeCard(
    kind: PageSize.Kind,
    unit: MeasurementUnit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(CARD_SHAPE_RADIUS))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(CARD_SHAPE_RADIUS),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // A silhouette at the sheet's real aspect ratio, so Legal reads as taller than Letter
        // without anyone having to compare two numbers.
        val height = 34.dp
        Box(
            Modifier
                .height(height)
                .width(height * (kind.width / kind.height))
                .background(scheme.surface)
                .border(1.dp, scheme.outline)
        )
        Text(
            text = kind.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        )
        Text(
            text = "${unit.format(kind.width, withSuffix = false)} × ${unit.format(kind.height)}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * The paper types, each drawn as the paper it will actually produce.
 *
 * The previews use the note's live colours and spacing, so changing the rule spacing redraws every
 * card and the choice is made by looking rather than by reading six nouns.
 */
@Composable
fun PatternCards(
    background: PageBackground,
    pageSize: PageSize,
    onSelect: (PatternType) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PatternType.entries, key = { it.name }) { type ->
            PatternCard(
                type = type,
                background = background,
                pageSize = pageSize,
                selected = background.pattern.type == type,
                onClick = { onSelect(type) },
            )
        }
    }
}

@Composable
private fun PatternCard(
    type: PatternType,
    background: PageBackground,
    pageSize: PageSize,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CARD_SHAPE_RADIUS)
    // The candidate paper, not the current one: each card previews the type it offers, with
    // everything else about the page held as it is.
    val preview = PageFormat(
        size = pageSize,
        background = background.copy(pattern = background.pattern.copy(type = type)),
        margins = PageMargins.NONE,
    )

    Box(
        Modifier
            .size(width = 96.dp, height = 128.dp)
            .clip(shape)
            .background(Color(background.color))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (type != PatternType.NONE) {
                drawPattern(
                    format = preview,
                    ptToPx = size.width / PREVIEW_CROP_PT,
                    pageTopPx = 0f,
                    pageHeightPx = size.height,
                    zoom = 1f,
                )
            }
        }
        Text(
            text = type.label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            modifier = Modifier
                .align(Alignment.Center)
                // The label sits on the paper, so it needs its own ground to stay readable over
                // whatever colour and rules the user has chosen.
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

internal val PatternType.label: String
    get() = when (this) {
        PatternType.NONE -> "Plain"
        PatternType.GRID -> "Grid"
        PatternType.RULED -> "Ruled"
        PatternType.DOTTED -> "Dotted"
        PatternType.ISOMETRIC -> "Isometric"
        PatternType.STAVES -> "Staves"
    }
