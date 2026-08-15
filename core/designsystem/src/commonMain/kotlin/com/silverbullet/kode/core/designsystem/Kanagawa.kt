package com.silverbullet.kode.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The Kanagawa palette, after Hokusai's *The Great Wave off Kanagawa*.
 *
 * Names are the upstream ones (`rebelot/kanagawa.nvim`) so a colour can be
 * cross-checked against the reference theme rather than guessed at. `Wave` is
 * the dark variant; `Lotus` is the light one.
 */
object Kanagawa {

    /** Dark variant. */
    object Wave {
        // Backgrounds, darkest to lightest.
        val sumiInk0 = Color(0xFF16161D)
        val sumiInk1 = Color(0xFF181820)
        val sumiInk2 = Color(0xFF1A1A22)
        val sumiInk3 = Color(0xFF1F1F28) // default background
        val sumiInk4 = Color(0xFF2A2A37)
        val sumiInk5 = Color(0xFF363646)
        val sumiInk6 = Color(0xFF54546D) // borders, dividers

        // Foregrounds.
        val fujiWhite = Color(0xFFDCD7BA) // default foreground
        val oldWhite = Color(0xFFC8C093)
        val fujiGray = Color(0xFF727169) // comments, muted text

        // Accents.
        val crystalBlue = Color(0xFF7E9CD8) // functions, links
        val springBlue = Color(0xFF7FB4CA)
        val dragonBlue = Color(0xFF658594)
        val waveAqua1 = Color(0xFF6A9589)
        val waveAqua2 = Color(0xFF7AA89F)
        val springGreen = Color(0xFF98BB6C) // strings, success
        val autumnGreen = Color(0xFF76946A)
        val carpYellow = Color(0xFFE6C384) // identifiers
        val boatYellow2 = Color(0xFFC0A36E)
        val autumnYellow = Color(0xFFDCA561) // warnings
        val roninYellow = Color(0xFFFF9E3B)
        val surimiOrange = Color(0xFFFFA066)
        val sakuraPink = Color(0xFFD27E99)
        val waveRed = Color(0xFFE46876)
        val peachRed = Color(0xFFFF5D62)
        val samuraiRed = Color(0xFFE82424) // errors
        val autumnRed = Color(0xFFC34043)
        val oniViolet = Color(0xFF957FB8) // keywords
        val springViolet1 = Color(0xFF938AA9)

        // Muted surfaces used for diagnostics and selections.
        val waveBlue1 = Color(0xFF223249)
        val waveBlue2 = Color(0xFF2D4F67)
        val winterGreen = Color(0xFF2B3328)
        val winterYellow = Color(0xFF49443C)
        val winterRed = Color(0xFF43242B)
        val winterBlue = Color(0xFF252535)
    }

    /** Light variant. */
    object Lotus {
        val lotusWhite0 = Color(0xFFD5CEA3)
        val lotusWhite1 = Color(0xFFDCD5AC)
        val lotusWhite2 = Color(0xFFE5DDB0)
        val lotusWhite3 = Color(0xFFF2ECBC) // default background
        val lotusWhite4 = Color(0xFFE7DBA0)
        val lotusWhite5 = Color(0xFFE4D794)

        val lotusInk1 = Color(0xFF545464) // default foreground
        val lotusInk2 = Color(0xFF43436C)
        val lotusGray = Color(0xFFDCD7BA)
        val lotusGray2 = Color(0xFF716E61)
        val lotusGray3 = Color(0xFF8A8980) // muted text

        val lotusBlue1 = Color(0xFFC7D7E0)
        val lotusBlue2 = Color(0xFFB5CBD2)
        val lotusBlue3 = Color(0xFF9FB5C9)
        val lotusBlue4 = Color(0xFF4D699B) // links
        val lotusBlue5 = Color(0xFF5D57A3)

        val lotusGreen = Color(0xFF6F894E) // success
        val lotusGreen2 = Color(0xFF6E915F)
        val lotusGreen3 = Color(0xFFB7D0AE)

        val lotusOrange = Color(0xFFCC6D00)
        val lotusOrange2 = Color(0xFFE98A00)
        val lotusYellow = Color(0xFF77713F)
        val lotusYellow2 = Color(0xFF836F4A)
        val lotusYellow3 = Color(0xFFDE9800) // warnings
        val lotusYellow4 = Color(0xFFF9D791)

        val lotusRed = Color(0xFFC84053) // errors
        val lotusRed2 = Color(0xFFD7474B)
        val lotusRed3 = Color(0xFFE82424)
        val lotusRed4 = Color(0xFFD9A594)

        val lotusPink = Color(0xFFB35B79)
        val lotusAqua = Color(0xFF597B75)
        val lotusAqua2 = Color(0xFF5E857A)
        val lotusViolet1 = Color(0xFFA09CAC)
        val lotusViolet2 = Color(0xFF766B90)
        val lotusViolet3 = Color(0xFFC9CBD1)
        val lotusViolet4 = Color(0xFF624C83)
    }
}
