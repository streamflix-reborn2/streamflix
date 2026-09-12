package com.streamflixreborn.streamflix.fragments.settings

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences

internal object SettingsListStyler {
    private data class DefaultRowStyle(
        val background: Drawable?,
        val minHeight: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val marginLeft: Int,
        val marginTop: Int,
        val marginRight: Int,
        val marginBottom: Int,
        val titleColor: Int,
        val titleSizePx: Float,
        val summaryColor: Int?,
        val summarySizePx: Float?,
    )

    fun attach(root: View, isTv: Boolean) {
        val recyclerView = findRecyclerView(root) ?: return
        if (recyclerView.getTag(R.id.settings_list_styler_tag) == true) return

        val backgroundColor = resolveThemeColor(root, R.attr.app_background_color, 0xFF181818.toInt())
        root.setBackgroundColor(backgroundColor)
        recyclerView.setTag(R.id.settings_list_styler_tag, true)
        recyclerView.clipToPadding = false
        recyclerView.setBackgroundColor(backgroundColor)
        recyclerView.setPadding(
            0,
            recyclerView.context.dp(if (isTv) 18 else 10),
            0,
            recyclerView.context.dp(if (isTv) 28 else 18),
        )

        recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                styleRow(view, isTv)
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })

        recyclerView.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restyle()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = restyle()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = restyle()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = restyle()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = restyle()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = restyle()

            private fun restyle() {
                recyclerView.post {
                    styleVisibleRows(recyclerView, isTv)
                }
            }
        })

        recyclerView.post {
            styleVisibleRows(recyclerView, isTv)
        }
    }

    private fun styleVisibleRows(recyclerView: RecyclerView, isTv: Boolean) {
        for (index in 0 until recyclerView.childCount) {
            styleRow(recyclerView.getChildAt(index), isTv)
        }
    }

    private fun styleRow(view: View, isTv: Boolean) {
        val title = view.findViewById<TextView>(android.R.id.title) ?: return
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val icon = view.findViewById<ImageView>(android.R.id.icon)
        val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams
        val context = view.context
        val defaults = (view.getTag(R.id.settings_row_defaults) as? DefaultRowStyle) ?: DefaultRowStyle(
            background = view.background,
            minHeight = view.minimumHeight,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            marginLeft = layoutParams?.leftMargin ?: 0,
            marginTop = layoutParams?.topMargin ?: 0,
            marginRight = layoutParams?.rightMargin ?: 0,
            marginBottom = layoutParams?.bottomMargin ?: 0,
            titleColor = title.currentTextColor,
            titleSizePx = title.textSize,
            summaryColor = summary?.currentTextColor,
            summarySizePx = summary?.textSize,
        ).also {
            view.setTag(R.id.settings_row_defaults, it)
        }
        val hasChevron = view.findViewById<View>(R.id.settings_chevron) != null
        if (!hasChevron) {
            val isCategoryHeader = summary == null && !view.isClickable && !view.isFocusable
            val categoryTopMargin = if (isCategoryHeader) context.dp(if (isTv) 18 else 12) else defaults.marginTop

            layoutParams?.setMargins(
                defaults.marginLeft,
                categoryTopMargin,
                defaults.marginRight,
                defaults.marginBottom,
            )
            view.layoutParams = layoutParams
            view.background = defaults.background
            view.minimumHeight = defaults.minHeight
            view.setPadding(
                defaults.paddingLeft,
                defaults.paddingTop,
                defaults.paddingRight,
                defaults.paddingBottom,
            )

            title.setTextColor(defaults.titleColor)
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaults.titleSizePx)
            title.typeface = Typeface.DEFAULT
            title.letterSpacing = 0f

            summary?.apply {
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                defaults.summaryColor?.let(::setTextColor)
                defaults.summarySizePx?.let { setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
            }
            icon?.imageTintList = null
            return
        }

        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        val surfaceColor = resolveThemeColor(view, R.attr.app_background_color, 0xFF181818.toInt())
        val titleColor = palette.tvHeaderPrimary
        val summaryColor = palette.tvHeaderSecondary
        val accentColor = palette.mobileNavActive
        val rowBackgroundColor = ColorUtils.blendARGB(surfaceColor, titleColor, if (isTv) 0.09f else 0.07f)
        val rowBorderColor = ColorUtils.blendARGB(surfaceColor, summaryColor, 0.42f)
        val rowHighlightColor = ColorUtils.blendARGB(surfaceColor, accentColor, if (isTv) 0.22f else 0.18f)
        val rowHighlightBorderColor = ColorUtils.blendARGB(surfaceColor, accentColor, 0.62f)

        layoutParams?.setMargins(
            context.dp(if (isTv) 28 else 16),
            context.dp(if (isTv) 8 else 6),
            context.dp(if (isTv) 28 else 16),
            context.dp(if (isTv) 8 else 6),
        )
        view.layoutParams = layoutParams
        view.background = createRowBackground(
            view = view,
            isTv = isTv,
            defaultColor = rowBackgroundColor,
            defaultStrokeColor = rowBorderColor,
            activeColor = rowHighlightColor,
            activeStrokeColor = rowHighlightBorderColor,
        )
        view.minimumHeight = context.dp(if (isTv) 88 else 72)
        view.setPadding(
            context.dp(if (isTv) 28 else 20),
            context.dp(if (isTv) 18 else 16),
            context.dp(if (isTv) 28 else 20),
            context.dp(if (isTv) 18 else 16),
        )

        title.setTextColor(titleColor)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv) 20f else 16f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.letterSpacing = 0f

        summary?.apply {
            setTextColor(summaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv) 14f else 13f)
            maxLines = 2
        }

        icon?.drawable?.let {
            icon.imageTintList = ColorStateList.valueOf(accentColor)
        }
    }

    private fun createRowBackground(
        view: View,
        isTv: Boolean,
        defaultColor: Int,
        defaultStrokeColor: Int,
        activeColor: Int,
        activeStrokeColor: Int,
    ): Drawable {
        val radiusDp = if (isTv) 22 else 18
        val defaultStrokeDp = 1
        val activeStrokeDp = if (isTv) 2 else 1

        return StateListDrawable().apply {
            if (isTv) {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    createRoundedRect(view, activeColor, activeStrokeColor, radiusDp, activeStrokeDp)
                )
                addState(
                    intArrayOf(android.R.attr.state_selected),
                    createRoundedRect(view, activeColor, activeStrokeColor, radiusDp, activeStrokeDp)
                )
            } else {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    createRoundedRect(view, activeColor, activeStrokeColor, radiusDp, activeStrokeDp)
                )
            }
            addState(
                intArrayOf(),
                createRoundedRect(view, defaultColor, defaultStrokeColor, radiusDp, defaultStrokeDp)
            )
        }
    }

    private fun createRoundedRect(
        view: View,
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int,
        strokeDp: Int,
    ): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = view.context.dp(radiusDp).toFloat()
        setColor(fillColor)
        setStroke(view.context.dp(strokeDp), strokeColor)
    }

    private fun resolveThemeColor(view: View, attr: Int, fallback: Int): Int {
        val typedValue = android.util.TypedValue()
        return if (view.context.theme.resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(view.context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            fallback
        }
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRecyclerView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
