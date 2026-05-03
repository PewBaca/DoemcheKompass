package de.doem.kompass

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Manages the two-palette theme system:
 *   06:00 – 19:59  →  Tag-Theme  (Kölner Rot & Weiß)
 *   20:00 – 05:59  →  Nacht-Theme (Anthrazit & Gold)
 */
object ThemeHelper {

    enum class AppTheme { DAY, NIGHT }

    /** Returns the theme for the current time of day. */
    fun currentTheme(): AppTheme {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (hour in 6..19) AppTheme.DAY else AppTheme.NIGHT
    }

    /** Returns the correct style resource ID. */
    fun themeResId(theme: AppTheme): Int = when (theme) {
        AppTheme.DAY   -> R.style.Theme_DoemcheKompass_Day
        AppTheme.NIGHT -> R.style.Theme_DoemcheKompass_Night
    }

    /** Convenience: resolve a color attribute from the current theme. */
    fun colorFor(context: Context, theme: AppTheme): Colors = Colors(context, theme)

    /** Holds all resolved colors for one theme so MainActivity can use them. */
    class Colors(ctx: Context, theme: AppTheme) {
        val bgDark        : Int
        val bgMid         : Int
        val cardBg        : Int
        val cardBorder    : Int
        val primary       : Int   // gold or red
        val primaryLight  : Int
        val primaryDim    : Int
        val textPrimary   : Int
        val textSecondary : Int
        val textDim       : Int
        val needle        : Int
        val north         : Int
        val btnBg         : Int
        val btnText       : Int
        val divider       : Int

        init {
            fun c(id: Int) = ContextCompat.getColor(ctx, id)
            when (theme) {
                AppTheme.NIGHT -> {
                    bgDark        = c(R.color.night_bg_dark)
                    bgMid         = c(R.color.night_bg_mid)
                    cardBg        = c(R.color.night_card_bg)
                    cardBorder    = c(R.color.night_card_border)
                    primary       = c(R.color.night_gold)
                    primaryLight  = c(R.color.night_gold_light)
                    primaryDim    = c(R.color.night_gold_dim)
                    textPrimary   = c(R.color.night_text_primary)
                    textSecondary = c(R.color.night_text_secondary)
                    textDim       = c(R.color.night_text_dim)
                    needle        = c(R.color.night_needle)
                    north         = c(R.color.night_north)
                    btnBg         = c(R.color.night_btn_bg)
                    btnText       = c(R.color.night_btn_text)
                    divider       = c(R.color.night_divider)
                }
                AppTheme.DAY -> {
                    bgDark        = c(R.color.day_bg_dark)
                    bgMid         = c(R.color.day_bg_mid)
                    cardBg        = c(R.color.day_card_bg)
                    cardBorder    = c(R.color.day_card_border)
                    primary       = c(R.color.day_red)
                    primaryLight  = c(R.color.day_red_light)
                    primaryDim    = c(R.color.day_red_dim)
                    textPrimary   = c(R.color.day_text_primary)
                    textSecondary = c(R.color.day_text_secondary)
                    textDim       = c(R.color.day_text_dim)
                    needle        = c(R.color.day_needle)
                    north         = c(R.color.day_north)
                    btnBg         = c(R.color.day_btn_bg)
                    btnText       = c(R.color.day_btn_text)
                    divider       = c(R.color.day_divider)
                }
            }
        }
    }
}
