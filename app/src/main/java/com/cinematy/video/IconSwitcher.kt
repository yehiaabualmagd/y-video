package com.cinematy.video

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconSwitcher {
    private val aliases = arrayOf(".OceanAlias", ".SunsetAlias", ".RoseAlias")

    /** Enables the palette's launcher-icon alias, disables the others.
     *  MainActivity's own entry stays enabled (the violet default icon). */
    fun apply(context: Context, palette: Int) {
        try {
            val pm = context.packageManager
            val pkg = context.packageName
            aliases.forEachIndexed { i, alias ->
                val state = if (palette == i + 1) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, pkg + alias),
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }
        } catch (_: Throwable) {
        }
    }
}
