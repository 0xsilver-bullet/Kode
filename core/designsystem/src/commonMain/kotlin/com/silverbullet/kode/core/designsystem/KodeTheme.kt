package com.silverbullet.kode.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours the Material scheme has no slot for: agent activity tones, diff
 * accents, and the syntax-ish colours markdown needs.
 *
 * `@Immutable` matters here — this is read during composition on every timeline
 * row, and an unstable type would defeat skipping.
 */
@Immutable
data class KodeExtendedColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val toolAccent: Color,
    val thinkingAccent: Color,
    val muted: Color,
    val codeBackground: Color,
    val inlineCodeBackground: Color,
    val inlineCodeText: Color,
    val quoteBar: Color,
    val quoteText: Color,
    val link: Color,
    val strong: Color,
    val divider: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val assistantSurface: Color,
    /** Whole-line background of an added diff line. */
    val diffAddedBackground: Color,
    /** Whole-line background of a removed diff line. */
    val diffRemovedBackground: Color,
    /** Stronger span behind the word-level changed ranges of an added line. */
    val diffAddedHighlight: Color,
    /** Stronger span behind the word-level changed ranges of a removed line. */
    val diffRemovedHighlight: Color,
)

val LocalKodeColors = staticCompositionLocalOf<KodeExtendedColors> {
    error("KodeExtendedColors requested outside of KodeTheme.")
}

/**
 * Which variant is active.
 *
 * Read rather than `isSystemInDarkTheme()` so a host that pins [KodeTheme]'s
 * `darkTheme` is not disagreed with. It exists for the few things that are not
 * a colour to resolve — a brand mark published once per colour scheme.
 */
private val LocalKodeDarkTheme = staticCompositionLocalOf<Boolean> {
    error("KodeTheme's variant requested outside of KodeTheme.")
}

private val WaveColorScheme = darkColorScheme(
    primary = Kanagawa.Wave.crystalBlue,
    onPrimary = Kanagawa.Wave.sumiInk0,
    primaryContainer = Kanagawa.Wave.waveBlue1,
    onPrimaryContainer = Kanagawa.Wave.fujiWhite,
    secondary = Kanagawa.Wave.waveAqua2,
    onSecondary = Kanagawa.Wave.sumiInk0,
    secondaryContainer = Kanagawa.Wave.waveBlue2,
    onSecondaryContainer = Kanagawa.Wave.fujiWhite,
    tertiary = Kanagawa.Wave.carpYellow,
    onTertiary = Kanagawa.Wave.sumiInk0,
    tertiaryContainer = Kanagawa.Wave.winterYellow,
    onTertiaryContainer = Kanagawa.Wave.fujiWhite,
    error = Kanagawa.Wave.samuraiRed,
    onError = Kanagawa.Wave.fujiWhite,
    errorContainer = Kanagawa.Wave.winterRed,
    onErrorContainer = Kanagawa.Wave.fujiWhite,
    background = Kanagawa.Wave.sumiInk3,
    onBackground = Kanagawa.Wave.fujiWhite,
    surface = Kanagawa.Wave.sumiInk3,
    onSurface = Kanagawa.Wave.fujiWhite,
    surfaceVariant = Kanagawa.Wave.sumiInk4,
    onSurfaceVariant = Kanagawa.Wave.oldWhite,
    surfaceContainerLowest = Kanagawa.Wave.sumiInk0,
    surfaceContainerLow = Kanagawa.Wave.sumiInk1,
    surfaceContainer = Kanagawa.Wave.sumiInk2,
    surfaceContainerHigh = Kanagawa.Wave.sumiInk4,
    surfaceContainerHighest = Kanagawa.Wave.sumiInk5,
    outline = Kanagawa.Wave.sumiInk6,
    outlineVariant = Kanagawa.Wave.sumiInk5,
    inverseSurface = Kanagawa.Wave.fujiWhite,
    inverseOnSurface = Kanagawa.Wave.sumiInk3,
    inversePrimary = Kanagawa.Wave.waveBlue2,
    scrim = Kanagawa.Wave.sumiInk0,
)

private val LotusColorScheme = lightColorScheme(
    primary = Kanagawa.Lotus.lotusBlue4,
    onPrimary = Kanagawa.Lotus.lotusWhite3,
    primaryContainer = Kanagawa.Lotus.lotusBlue1,
    onPrimaryContainer = Kanagawa.Lotus.lotusInk1,
    secondary = Kanagawa.Lotus.lotusAqua,
    onSecondary = Kanagawa.Lotus.lotusWhite3,
    secondaryContainer = Kanagawa.Lotus.lotusBlue2,
    onSecondaryContainer = Kanagawa.Lotus.lotusInk1,
    tertiary = Kanagawa.Lotus.lotusOrange,
    onTertiary = Kanagawa.Lotus.lotusWhite3,
    tertiaryContainer = Kanagawa.Lotus.lotusYellow4,
    onTertiaryContainer = Kanagawa.Lotus.lotusInk1,
    error = Kanagawa.Lotus.lotusRed,
    onError = Kanagawa.Lotus.lotusWhite3,
    errorContainer = Kanagawa.Lotus.lotusRed4,
    onErrorContainer = Kanagawa.Lotus.lotusInk1,
    background = Kanagawa.Lotus.lotusWhite3,
    onBackground = Kanagawa.Lotus.lotusInk1,
    surface = Kanagawa.Lotus.lotusWhite3,
    onSurface = Kanagawa.Lotus.lotusInk1,
    surfaceVariant = Kanagawa.Lotus.lotusWhite2,
    onSurfaceVariant = Kanagawa.Lotus.lotusGray2,
    surfaceContainerLowest = Kanagawa.Lotus.lotusWhite3,
    surfaceContainerLow = Kanagawa.Lotus.lotusWhite2,
    surfaceContainer = Kanagawa.Lotus.lotusWhite1,
    surfaceContainerHigh = Kanagawa.Lotus.lotusWhite0,
    surfaceContainerHighest = Kanagawa.Lotus.lotusWhite4,
    outline = Kanagawa.Lotus.lotusGray3,
    outlineVariant = Kanagawa.Lotus.lotusGray,
    inverseSurface = Kanagawa.Lotus.lotusInk1,
    inverseOnSurface = Kanagawa.Lotus.lotusWhite3,
    inversePrimary = Kanagawa.Lotus.lotusBlue3,
    scrim = Kanagawa.Lotus.lotusInk2,
)

private val WaveExtendedColors = KodeExtendedColors(
    success = Kanagawa.Wave.springGreen,
    warning = Kanagawa.Wave.roninYellow,
    danger = Kanagawa.Wave.waveRed,
    info = Kanagawa.Wave.springBlue,
    toolAccent = Kanagawa.Wave.waveAqua2,
    thinkingAccent = Kanagawa.Wave.oniViolet,
    muted = Kanagawa.Wave.fujiGray,
    codeBackground = Kanagawa.Wave.sumiInk1,
    inlineCodeBackground = Kanagawa.Wave.sumiInk4,
    inlineCodeText = Kanagawa.Wave.carpYellow,
    quoteBar = Kanagawa.Wave.sumiInk6,
    quoteText = Kanagawa.Wave.oldWhite,
    link = Kanagawa.Wave.crystalBlue,
    strong = Kanagawa.Wave.oldWhite,
    divider = Kanagawa.Wave.sumiInk5,
    userBubble = Kanagawa.Wave.waveBlue1,
    userBubbleText = Kanagawa.Wave.fujiWhite,
    assistantSurface = Color.Transparent,
    diffAddedBackground = Kanagawa.Wave.winterGreen,
    diffRemovedBackground = Kanagawa.Wave.winterRed,
    diffAddedHighlight = Kanagawa.Wave.springGreen.copy(alpha = 0.32f),
    diffRemovedHighlight = Kanagawa.Wave.waveRed.copy(alpha = 0.32f),
)

private val LotusExtendedColors = KodeExtendedColors(
    success = Kanagawa.Lotus.lotusGreen,
    warning = Kanagawa.Lotus.lotusOrange,
    danger = Kanagawa.Lotus.lotusRed,
    info = Kanagawa.Lotus.lotusBlue4,
    toolAccent = Kanagawa.Lotus.lotusAqua,
    thinkingAccent = Kanagawa.Lotus.lotusViolet4,
    muted = Kanagawa.Lotus.lotusGray3,
    codeBackground = Kanagawa.Lotus.lotusWhite1,
    inlineCodeBackground = Kanagawa.Lotus.lotusWhite0,
    inlineCodeText = Kanagawa.Lotus.lotusYellow2,
    quoteBar = Kanagawa.Lotus.lotusGray3,
    quoteText = Kanagawa.Lotus.lotusGray2,
    link = Kanagawa.Lotus.lotusBlue4,
    strong = Kanagawa.Lotus.lotusInk2,
    divider = Kanagawa.Lotus.lotusGray,
    userBubble = Kanagawa.Lotus.lotusBlue1,
    userBubbleText = Kanagawa.Lotus.lotusInk1,
    assistantSurface = Color.Transparent,
    diffAddedBackground = Kanagawa.Lotus.lotusGreen3.copy(alpha = 0.45f),
    diffRemovedBackground = Kanagawa.Lotus.lotusRed4.copy(alpha = 0.40f),
    diffAddedHighlight = Kanagawa.Lotus.lotusGreen3,
    diffRemovedHighlight = Kanagawa.Lotus.lotusRed4,
)

/**
 * The app theme. Wraps [MaterialTheme] so every existing `MaterialTheme.*`
 * lookup picks up Kanagawa, and adds [KodeTheme.colors] for the slots Material
 * does not model.
 */
@Composable
fun KodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    markdownParseCache: MarkdownParseCache = rememberDefaultMarkdownParseCache(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKodeColors provides if (darkTheme) WaveExtendedColors else LotusExtendedColors,
        LocalKodeDarkTheme provides darkTheme,
        LocalMarkdownParseCache provides markdownParseCache,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) WaveColorScheme else LotusColorScheme,
            typography = KodeTypography,
        ) {
            // Built here, inside the Material theme, so it is constructed once
            // rather than per rendered message.
            CompositionLocalProvider(
                LocalKodeMarkdownConfig provides rememberKodeMarkdownConfig(),
                content = content,
            )
        }
    }
}

/**
 * A cache scoped to the composition. Hosts that want it to outlive theme
 * changes should pass their own instance.
 */
@Composable
private fun rememberDefaultMarkdownParseCache(): MarkdownParseCache =
    remember { MarkdownParseCache() }

object KodeTheme {
    val colors: KodeExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKodeColors.current

    /** True under the Wave (dark) variant, false under Lotus (light). */
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalKodeDarkTheme.current
}
