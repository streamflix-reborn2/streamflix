package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.streamflixreborn.streamflix.R

object ProfileSwitchPinGuard {

    fun verifyCurrentProfile(context: Context, onVerified: () -> Unit) {
        if (!UserPreferences.isParentalControlActive) {
            onVerified()
            return
        }

        when {
            UserPreferences.parentalControlHardLocked -> {
                Toast.makeText(context, R.string.settings_parental_locked_hard, Toast.LENGTH_SHORT).show()
                return
            }
            UserPreferences.isParentalControlTemporarilyLocked -> {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.settings_parental_locked_temporary,
                        lockRemainingMinutes(),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
        }

        val currentPin = UserPreferences.parentalControlPin
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = context.getString(R.string.settings_parental_pin_hint)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.profile_switch_pin_title)
            .setMessage(R.string.profile_switch_pin_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                input.error = null
                if (input.text?.toString()?.trim() == currentPin) {
                    UserPreferences.registerParentalPinSuccess()
                    dialog.dismiss()
                    onVerified()
                } else {
                    UserPreferences.registerParentalPinFailure()
                    val errorMessage = when {
                        UserPreferences.parentalControlHardLocked ->
                            context.getString(R.string.settings_parental_locked_hard)
                        UserPreferences.isParentalControlTemporarilyLocked ->
                            context.getString(
                                R.string.settings_parental_locked_temporary,
                                lockRemainingMinutes(),
                            )
                        else -> context.getString(R.string.settings_parental_invalid_pin)
                    }
                    input.setText("")
                    input.error = errorMessage
                    input.requestFocus()
                }
            }
        }

        dialog.show()
    }

    private fun lockRemainingMinutes(): Int {
        val millis = UserPreferences.parentalControlLockRemainingMillis
        return ((millis + 60_000L - 1L) / 60_000L).toInt().coerceAtLeast(1)
    }
}
