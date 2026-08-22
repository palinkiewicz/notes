package pl.dakil.notes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography, on the platform's own font.
 *
 * No bundled typeface: a variable font is several hundred kilobytes, and the system font is what
 * the rest of the user's device already reads like. Body text is set slightly larger and looser
 * than the Material default, because this app's body text is the content rather than a label.
 *
 * `titleLarge` is deliberately left at the Material weight: it is what `TopAppBar` sets its title in,
 * and a bold title on every screen shouts over the content it is naming.
 */
internal val NotesTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 25.sp,
        ),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 34.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            lineHeight = 30.sp,
        ),
    )
}

/** Monospace style shared by code fences and preserved math spans. */
val MonospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)
