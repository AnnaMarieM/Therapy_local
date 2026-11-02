package com.therapylocal.app.ui.logic

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** The text a logic class needs, behind an interface so its rules stay unit-testable without Android resources. */
interface UiText {
    /** The locale numbers and durations are shown in. */
    val locale: Locale
    fun str(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String
}

class ResourcesText(private val resources: Resources) : UiText {
    override val locale: Locale get() = resources.configuration.locales[0]
    override fun str(id: Int, vararg args: Any): String = resources.getString(id, *args)
    override fun plural(id: Int, quantity: Int, vararg args: Any): String = resources.getQuantityString(id, quantity, *args)
}

fun Context.uiText(): UiText = ResourcesText(resources)
