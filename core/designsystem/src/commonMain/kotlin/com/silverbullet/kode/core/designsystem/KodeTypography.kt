package com.silverbullet.kode.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The mobile type scale, ported verbatim from
 * `apps/mobile/src/lib/typography.ts`.
 *
 * These are the sizes T3 Code's mobile app actually renders at, so the app
 * reads at the same density rather than at Material's defaults (which are
 * noticeably larger).
 */
object KodeTextSizes {
    // fontSize to lineHeight, exactly as MOBILE_TYPOGRAPHY declares them.
    val micro = 11.sp to 14.sp
    val caption = 12.sp to 16.sp
    val label = 13.sp to 17.sp
    val footnote = 14.sp to 19.sp
    val body = 16.sp to 23.sp
    val headline = 18.sp to 23.sp
    val title = 21.sp to 28.sp
    val largeTitle = 26.sp to 32.sp
}

private fun style(
    size: Pair<androidx.compose.ui.unit.TextUnit, androidx.compose.ui.unit.TextUnit>,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(fontSize = size.first, lineHeight = size.second, fontWeight = weight)

/**
 * Material slots mapped onto the T3 Code scale.
 *
 * `bodyLarge` is the 16/23 body size, which is what markdown paragraphs and
 * message text render at.
 */
internal val KodeTypography = Typography(
    displayLarge = style(KodeTextSizes.largeTitle, FontWeight.Bold),
    displayMedium = style(KodeTextSizes.title, FontWeight.Bold),
    displaySmall = style(KodeTextSizes.headline, FontWeight.Bold),
    headlineLarge = style(KodeTextSizes.title, FontWeight.Bold),
    headlineMedium = style(KodeTextSizes.headline, FontWeight.Bold),
    headlineSmall = style(KodeTextSizes.body, FontWeight.Bold),
    titleLarge = style(KodeTextSizes.headline, FontWeight.SemiBold),
    titleMedium = style(KodeTextSizes.body, FontWeight.SemiBold),
    titleSmall = style(KodeTextSizes.footnote, FontWeight.SemiBold),
    bodyLarge = style(KodeTextSizes.body),
    bodyMedium = style(KodeTextSizes.footnote),
    bodySmall = style(KodeTextSizes.label),
    labelLarge = style(KodeTextSizes.label, FontWeight.Medium),
    labelMedium = style(KodeTextSizes.caption, FontWeight.Medium),
    labelSmall = style(KodeTextSizes.micro, FontWeight.Medium),
)

/**
 * Markdown-specific sizes, ported from `resolveMarkdownFontSizes` in
 * `apps/mobile/src/lib/appearancePreferences.ts` at the default base size of 16.
 */
object KodeMarkdownSizes {
    val body = 16.sp
    val bodyLineHeight = 23.sp
    val small = 14.sp

    val h1 = 21.sp
    val h2 = 19.sp
    val h3 = 17.sp
    val h4 = 15.sp
    val h5 = 15.sp
    val h6 = 15.sp

    /** Headings get a slightly tighter leading than body text. */
    val h1LineHeight = 28.sp
    val h2LineHeight = 26.sp
    val h3LineHeight = 24.sp
    val hSmallLineHeight = 22.sp

    val code = 13.sp
    val codeLineHeight = 19.sp

    val monospace = FontFamily.Monospace
}
