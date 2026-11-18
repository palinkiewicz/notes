package pl.dakil.notes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import pl.dakil.notes.ui.R

/**
 * The app's chrome type: Google Sans Flex, the face the author's other apps share.
 *
 * A bundled subset (OFL-1.1, ~170 KB; rebuilt by `tools/subset-font.sh`) with two axes kept. Display
 * and headline styles use the fully rounded `ROND` axis for friendly big type; everything else stays
 * crisp at `ROND` 0.
 *
 * **Notes do not use this.** Everything inside a note — Markdown text, headings, text boxes on a
 * sheet, thumbnails — is laid out in [NoteContentTypography] under [NoteContentTheme], so changing
 * the look of the app can never reflow anybody's notes.
 */
private fun googleSansFlex(weight: Int, roundness: Float) = Font(
    resId = R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("ROND", roundness),
    ),
)

private val RoundedDisplayFamily: FontFamily =
    FontFamily((300..900 step 100).map { googleSansFlex(it, roundness = 100f) })
private val TextFamily: FontFamily =
    FontFamily((300..900 step 100).map { googleSansFlex(it, roundness = 0f) })

private fun TextStyle.rounded() = copy(fontFamily = RoundedDisplayFamily)
private fun TextStyle.text() = copy(fontFamily = TextFamily)

/** Tabular (fixed-width) figures, so a live value does not shift sideways as its digits change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

internal val ChromeTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.rounded(),
        displayMedium = base.displayMedium.rounded(),
        displaySmall = base.displaySmall.rounded(),
        headlineLarge = base.headlineLarge.rounded(),
        headlineMedium = base.headlineMedium.rounded(),
        headlineSmall = base.headlineSmall.rounded(),
        titleLarge = base.titleLarge.text(),
        titleMedium = base.titleMedium.text(),
        titleSmall = base.titleSmall.text(),
        bodyLarge = base.bodyLarge.text(),
        bodyMedium = base.bodyMedium.text(),
        bodySmall = base.bodySmall.text(),
        labelLarge = base.labelLarge.text(),
        labelMedium = base.labelMedium.text(),
        labelSmall = base.labelSmall.text(),
        displayLargeEmphasized = base.displayLargeEmphasized.rounded().copy(fontWeight = FontWeight.SemiBold),
        displayMediumEmphasized = base.displayMediumEmphasized.rounded().copy(fontWeight = FontWeight.SemiBold),
        displaySmallEmphasized = base.displaySmallEmphasized.rounded().copy(fontWeight = FontWeight.SemiBold),
        headlineLargeEmphasized = base.headlineLargeEmphasized.rounded().copy(fontWeight = FontWeight.SemiBold),
        headlineMediumEmphasized = base.headlineMediumEmphasized.rounded().copy(fontWeight = FontWeight.SemiBold),
        headlineSmallEmphasized = base.headlineSmallEmphasized.rounded().copy(fontWeight = FontWeight.SemiBold),
        titleLargeEmphasized = base.titleLargeEmphasized.text(),
        titleMediumEmphasized = base.titleMediumEmphasized.text(),
        titleSmallEmphasized = base.titleSmallEmphasized.text(),
        bodyLargeEmphasized = base.bodyLargeEmphasized.text(),
        bodyMediumEmphasized = base.bodyMediumEmphasized.text(),
        bodySmallEmphasized = base.bodySmallEmphasized.text(),
        labelLargeEmphasized = base.labelLargeEmphasized.text(),
        labelMediumEmphasized = base.labelMediumEmphasized.text(),
        labelSmallEmphasized = base.labelSmallEmphasized.text(),
    )
}

/** Monospace style shared by code fences and preserved math spans. */
val MonospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)
