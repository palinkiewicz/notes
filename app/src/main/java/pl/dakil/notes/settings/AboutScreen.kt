package pl.dakil.notes.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import pl.dakil.notes.BuildConfig
import pl.dakil.notes.R
import pl.dakil.notes.ui.designsystem.MorphShape
import pl.dakil.notes.ui.designsystem.ShapedIcon
import pl.dakil.notes.ui.designsystem.decorativeMotion
import pl.dakil.notes.ui.designsystem.decorativeSpec
import pl.dakil.notes.ui.designsystem.decorativeTurn
import pl.dakil.notes.ui.designsystem.pressScale
import pl.dakil.notes.ui.designsystem.rememberHaptics
import pl.dakil.notes.ui.icons.NotesIcons
import pl.dakil.notes.ui.theme.Spacing

private const val REPOSITORY_URL = "https://github.com/palinkiewicz/notes"
private const val AUTHOR_URL = "https://github.com/palinkiewicz/"
private const val AUTHOR_NAME = "palinkiewicz"
private const val CREDIT_URL = "https://github.com/Fxyow"
private const val CREDIT_NAME = "fxyow"
private const val FONT_LICENCE_URL = "https://openfontlicense.org"
private const val SYMBOLS_LICENCE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

/** Who made this, where it lives, and whose work it stands on. */
@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    SettingsSubscreen(stringResource(R.string.about_title), onNavigateBack) {
        AboutHero()

        SettingsGroup(stringResource(R.string.about_section_project)) {
            LinkRow(
                title = stringResource(R.string.about_repository),
                summary = stringResource(R.string.about_repository_link),
                url = REPOSITORY_URL,
                icon = NotesIcons.InlineCode,
                polygon = MaterialShapes.Cookie6Sided,
            )
            LinkRow(
                title = stringResource(R.string.about_author),
                summary = AUTHOR_NAME,
                url = AUTHOR_URL,
                icon = NotesIcons.Person,
                polygon = MaterialShapes.Cookie9Sided,
            )
            LinkRow(
                title = stringResource(R.string.about_credit),
                summary = CREDIT_NAME,
                url = CREDIT_URL,
                icon = NotesIcons.FavoriteFill,
                polygon = MaterialShapes.Clover4Leaf,
            )
        }

        SettingsGroup(stringResource(R.string.about_section_made_with)) {
            LinkRow(
                title = stringResource(R.string.about_font_name),
                summary = stringResource(R.string.about_font_licence),
                url = FONT_LICENCE_URL,
            )
            LinkRow(
                title = stringResource(R.string.about_symbols_name),
                summary = stringResource(R.string.about_symbols_licence),
                url = SYMBOLS_LICENCE_URL,
            )
        }
    }
}

/**
 * The app's mark, which turns into place on arrival and changes shape when poked.
 *
 * Purely for the pleasure of it, so it stays out of the accessibility tree and has no state worth
 * keeping. With decorative motion off the shapes simply swap.
 */
@Composable
private fun AboutHero() {
    val shapes = remember {
        listOf(
            MaterialShapes.Cookie12Sided,
            MaterialShapes.Flower,
            MaterialShapes.Clover8Leaf,
            MaterialShapes.Sunny,
            MaterialShapes.Cookie7Sided,
        )
    }
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    var step by remember { mutableIntStateOf(0) }
    val morph = remember(step) { Morph(shapes[(step - 1).mod(shapes.size)], shapes[step.mod(shapes.size)]) }
    val progress = remember(step) { Animatable(if (step == 0) 1f else 0f) }
    val turn = remember { Animatable(ARRIVAL_TURN) }
    val morphSpec = decorativeSpec(MaterialTheme.motionScheme.defaultSpatialSpec<Float>())
    val turnSpec = decorativeSpec(MaterialTheme.motionScheme.slowSpatialSpec<Float>())
    val turnTarget = decorativeTurn(step * STEP_TURN)
    val startStill = !decorativeMotion
    LaunchedEffect(step) {
        if (startStill) turn.snapTo(0f)
        progress.animateTo(1f, morphSpec)
    }
    LaunchedEffect(turnTarget) { turn.animateTo(turnTarget, turnSpec) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.l, horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(bottom = Spacing.s)
                .size(112.dp)
                .clearAndSetSemantics {}
                .pressScale(interaction, pressedScale = 0.92f)
                .clickable(interactionSource = interaction, indication = null) {
                    haptics.tick()
                    step++
                },
        ) {
            val container = MaterialTheme.colorScheme.primaryContainer
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        rotationZ = turn.value
                        shape = MorphShape(morph, progress.value.coerceIn(0f, 1f))
                        clip = true
                    }
                    .background(container),
            )
            Icon(
                imageVector = NotesIcons.NoteFill,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(52.dp),
            )
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMediumEmphasized,
        )
        Text(
            text = stringResource(R.string.about_version_x, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        )
        Text(
            text = stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** A row that leaves the app for a web page, and says so with its trailing arrow. */
@Composable
private fun LinkRow(
    title: String,
    summary: String,
    url: String,
    icon: ImageVector? = null,
    polygon: RoundedPolygon = MaterialShapes.Cookie9Sided,
) {
    val uriHandler = LocalUriHandler.current
    SettingRow(
        title = title,
        summary = summary,
        // A device with nothing that can open a link has nothing useful to be told about it here.
        onClick = { runCatching { uriHandler.openUri(url) } },
        leading = icon?.let { { ShapedIcon(icon = it, polygon = polygon) } },
        trailing = {
            Icon(
                imageVector = NotesIcons.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

private const val ARRIVAL_TURN = -60f
private const val STEP_TURN = 72f
