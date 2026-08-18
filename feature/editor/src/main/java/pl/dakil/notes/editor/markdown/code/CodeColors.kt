package pl.dakil.notes.editor.markdown.code

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours code is written in — which are not the colours the app is written in.
 *
 * Everything else in a note takes its colour from the Material scheme, and code used to as well:
 * keywords in `primary`, strings in `tertiary`, numbers in `secondary`. It was consistent and it
 * was useless. A syntax scheme's job is to tell one kind of token from another at a glance, and a
 * Material palette is built to harmonise instead — three of its roles in the same note were close
 * enough in hue that a string and a number read as the same thing.
 *
 * So these are ordinary syntax-highlighting colours, the ones anybody who writes code already knows
 * from an editor: purple keywords, yellow strings, green numbers, orange calls. Two sets, because a
 * hue that reads on a dark page is washed out on a white one and the light set has to be darker and
 * more saturated to carry the same distinction.
 */
@Immutable
class CodeColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val function: Color,
    val comment: Color,
) {
    companion object {
        val Dark = CodeColors(
            keyword = Color(0xFFC678DD),
            string = Color(0xFFE5C07B),
            number = Color(0xFF98C379),
            function = Color(0xFFE59A5C),
            comment = Color(0xFF7F8C98),
        )

        val Light = CodeColors(
            keyword = Color(0xFF8B22B5),
            string = Color(0xFF8A6100),
            number = Color(0xFF157A3C),
            function = Color(0xFFB1500A),
            comment = Color(0xFF6B7480),
        )
    }
}
