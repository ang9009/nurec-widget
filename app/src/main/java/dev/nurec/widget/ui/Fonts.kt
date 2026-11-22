package dev.nurec.widget.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.nurec.widget.R

/** Bundled fonts from the mockup (both SIL OFL 1.1, see /licenses).
 * The widget uses the same fonts from XML layouts (res/layout/text_*.xml). */
object Fonts {
    val Archivo = FontFamily(
        Font(R.font.archivo_regular, FontWeight.Normal),
        Font(R.font.archivo_medium, FontWeight.Medium),
        Font(R.font.archivo_semibold, FontWeight.SemiBold),
        Font(R.font.archivo_bold, FontWeight.Bold),
    )
    val Barlow = FontFamily(
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )

}
