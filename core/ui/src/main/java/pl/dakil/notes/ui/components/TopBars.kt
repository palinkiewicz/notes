package pl.dakil.notes.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * A top app bar that sits on the page rather than above it.
 *
 * The default bar tints itself as content scrolls under it, which draws a line across a screen that
 * is one continuous list. Matching the background instead leaves the title floating over the content
 * it names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun flatTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)
