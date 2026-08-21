package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.streamflixreborn.streamflix.R

object ProfileColorPicker {
    fun show(
        context: Context,
        selectedColor: Int,
        onSelected: (Int) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.profile_color_title)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val grid = GridLayout(context).apply {
            columnCount = 5
            setPadding(24, 20, 24, 20)
        }

        val size = (48 * context.resources.displayMetrics.density).toInt()
        val margin = (6 * context.resources.displayMetrics.density).toInt()
        val selectedIndex = ProfileManager.profileColorOptions.indexOf(selectedColor).coerceAtLeast(0)
        ProfileManager.profileColorOptions.forEachIndexed { _, color ->
            val swatch = TextView(context).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                fun updateFocusRing(focused: Boolean) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        when {
                            focused -> setStroke((4 * context.resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
                            color == selectedColor -> setStroke((2 * context.resources.displayMetrics.density).toInt(), 0xFFBDBDBD.toInt())
                        }
                    }
                }
                updateFocusRing(false)
                contentDescription = String.format("#%06X", color and 0xFFFFFF)
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                setOnFocusChangeListener { _, hasFocus -> updateFocusRing(hasFocus) }
                setOnClickListener {
                    onSelected(color)
                    dialog.dismiss()
                }
            }
            grid.addView(swatch)
        }

        dialog.setView(grid)
        dialog.show()
        grid.getChildAt(selectedIndex)?.requestFocus()
    }
}
