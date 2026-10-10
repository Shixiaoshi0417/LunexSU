// Adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package me.weishu.kernelsu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The type ladder the Material home cards are written against. The family is never set here - it
 * comes from the active [MaterialTheme.typography], where the font the user picked is applied - and
 * how a line breaks is part of a token, so a long word hyphenates instead of overflowing its box.
 */
object FolkType {

    /** The name of a thing: 17sp over a 22sp line box. */
    val Title: TextStyle
        @Composable get() = scale(size = 17.sp, lineHeight = 22.sp, weight = FontWeight.Medium, lineBreak = LineBreak.Heading)

    /** What that thing is or does: 15sp over a 20sp line box. */
    val Summary: TextStyle
        @Composable get() = scale(size = 15.sp, lineHeight = 20.sp)

    /** The small print: 14sp over an 18sp line box. */
    val Caption: TextStyle
        @Composable get() = scale(size = 14.sp, lineHeight = 18.sp)

    /** [Summary] with tabular figures, so a changing number does not push its neighbours around. */
    val Numeral: TextStyle
        @Composable get() = Summary.copy(fontFeatureSettings = "tnum")

    /**
     * [Caption] for machine strings - version numbers, build ids, fingerprints. These are read as
     * whole tokens, so hyphenation is off and the line breaker balances between phrases.
     */
    val Machine: TextStyle
        @Composable get() = Caption.copy(
            hyphens = Hyphens.None,
            lineBreak = LineBreak.Heading,
        )
}

/**
 * How every style breaks a line: a heading breaks where a phrase ends, everything else fills its
 * line, and both hyphenate.
 */
fun TextStyle.wrapAware(lineBreak: LineBreak = LineBreak.Paragraph): TextStyle = copy(
    lineBreak = lineBreak,
    hyphens = Hyphens.Auto,
)

@Composable
private fun scale(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    lineBreak: LineBreak = LineBreak.Paragraph,
): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontSize = size,
    lineHeight = lineHeight,
    fontWeight = weight,
    letterSpacing = 0.sp,
).wrapAware(lineBreak)
