package pl.dakil.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import pl.dakil.notes.ui.theme.NotesTheme
import pl.dakil.notes.ui.theme.resolveDark

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppGraph.get(this)

        enableEdgeToEdge()
        setContent {
            // Read straight from the repository rather than through a view model: this is the one
            // place that has to see a theme change immediately, and routing it through a saved state
            // holder would only add a frame of the old colours.
            val settings by container.settings.settings
                .collectAsState(initial = remember { container.settings.read() })

            val dark = settings.darkTheme.resolveDark()

            NotesTheme(
                colorTheme = settings.colorTheme,
                darkThemeOption = settings.darkTheme,
                pureBlack = settings.pureBlack,
            ) {
                // The canvas needs to know the theme directly: page colours come from the document,
                // not from the M3 scheme, so it picks the light or dark paper itself.
                NotesApp(container = container, darkTheme = dark)
            }
        }
    }
}
