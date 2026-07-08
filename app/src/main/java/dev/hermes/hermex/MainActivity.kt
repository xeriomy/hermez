package dev.hermes.hermex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import dev.hermes.hermex.ui.HermexApp

/**
 * The single activity hosting the entire Compose UI.
 *
 * Uses a dark Material 3 color scheme as the default (and only) theme.
 * Light theme is intentionally omitted for now per the product direction
 * — we'll add a proper light/dark toggle in a later polish task.
 *
 * The actual navigation graph lives in [HermexApp].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermexTheme {
                HermexApp()
            }
        }
    }
}

/**
 * Dark-only Material 3 theme wrapper.
 *
 * Uses [darkColorScheme] with default Material 3 dark colors. Dynamic
 * color (Material You) is deliberately NOT enabled — we want a
 * consistent dark palette across all devices for now. Will revisit
 * in the polish phase.
 */
@Composable
private fun HermexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}
