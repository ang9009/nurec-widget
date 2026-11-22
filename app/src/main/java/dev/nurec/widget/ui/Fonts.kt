package dev.nurec.widget.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.nurec.widget.R

/**
 * Lato (SIL OFL 1.1, see /licenses): the fallback font northeastern.edu declares for FF Real,
 * which is commercial. The widget draws the same font into text masks (widget/Glyphs.kt).
 */
object Fonts {
    val Lato = FontFamily(
        Font(R.font.lato_regular, FontWeight.Normal),
        Font(R.font.lato_bold, FontWeight.Bold),
        Font(R.font.lato_black, FontWeight.Black),
    )
}
