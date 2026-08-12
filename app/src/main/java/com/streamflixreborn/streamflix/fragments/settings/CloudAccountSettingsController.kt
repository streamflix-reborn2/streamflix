package com.streamflixreborn.streamflix.fragments.settings

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.CloudSyncProgress
import com.streamflixreborn.streamflix.sync.SupabaseProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object CloudAccountSettingsController {
    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        val status = findPreference("cloud_account_status") ?: return
        val signIn = findPreference("cloud_sign_in")
        val signUp = findPreference("cloud_sign_up")
        val signOut = findPreference("cloud_sign_out")
        val syncNow = findPreference("cloud_sync_now")

        fun refresh() {
            val email = CloudSyncManager.currentUserEmail()
            status.summary = email?.let {
                fragment.getString(R.string.cloud_sync_signed_in_as, it)
            } ?: fragment.getString(R.string.cloud_sync_signed_out)
            signIn?.isVisible = email == null
            signUp?.isVisible = email == null
            signOut?.isVisible = email != null
            syncNow?.isVisible = email != null
            status.isEnabled = SupabaseProvider.isConfigured
            signIn?.isEnabled = SupabaseProvider.isConfigured && email == null
            signUp?.isEnabled = SupabaseProvider.isConfigured && email == null
            signOut?.isEnabled = SupabaseProvider.isConfigured && email != null
            syncNow?.isEnabled = SupabaseProvider.isConfigured && email != null
        }

        signIn?.setOnPreferenceClickListener {
            showCredentialsDialog(fragment, R.string.cloud_sync_sign_in) { email, password ->
                runProgressAction(fragment, scope, ::refresh) { onProgress ->
                    CloudSyncManager.signIn(
                        fragment.requireContext(),
                        email,
                        password,
                        onProgress,
                    )
                    R.string.cloud_sync_sign_in_success
                }
            }
            true
        }

        signUp?.setOnPreferenceClickListener {
            showCredentialsDialog(fragment, R.string.cloud_sync_sign_up) { email, password ->
                runProgressAction(fragment, scope, ::refresh) { onProgress ->
                    val signedIn = CloudSyncManager.signUp(
                        fragment.requireContext(),
                        email,
                        password,
                        onProgress,
                    )
                    if (signedIn) R.string.cloud_sync_sign_up_success else R.string.cloud_sync_confirm_email
                }
            }
            true
        }

        signOut?.setOnPreferenceClickListener {
            runAction(fragment, scope, ::refresh) {
                CloudSyncManager.signOut(fragment.requireContext())
                R.string.cloud_sync_sign_out_success
            }
            true
        }

        syncNow?.setOnPreferenceClickListener {
            runProgressAction(fragment, scope, ::refresh) { onProgress ->
                CloudSyncManager.syncNow(fragment.requireContext(), onProgress)
                R.string.cloud_sync_success
            }
            true
        }

        refresh()
    }

    private fun showCredentialsDialog(
        fragment: Fragment,
        titleRes: Int,
        onSubmit: (String, String) -> Unit,
    ) {
        val context = fragment.requireContext()
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val email = EditText(context).apply {
            hint = context.getString(R.string.cloud_sync_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            isSingleLine = true
        }
        val password = EditText(context).apply {
            hint = context.getString(R.string.cloud_sync_password_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(email, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(titleRes, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val emailValue = email.text.toString().trim()
                val passwordValue = password.text.toString()
                if (!emailValue.contains('@') || passwordValue.length < 6) {
                    Toast.makeText(context, R.string.cloud_sync_invalid_credentials, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onSubmit(emailValue, passwordValue)
            }
        }
        dialog.show()
    }

    private fun runProgressAction(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
        action: suspend ((CloudSyncProgress) -> Unit) -> Int,
    ) {
        val context = fragment.requireContext()
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        )
        val message = TextView(context).apply {
            setText(R.string.cloud_sync_progress_connecting)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(
                progressBar,
                LinearLayout.LayoutParams(
                    (72 * context.resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                message,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = padding
                },
            )
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.cloud_sync_progress_title)
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        scope.launch {
            try {
                val resultMessage = action { progress ->
                    updateProgress(fragment, progressBar, message, progress)
                }
                dialog.dismiss()
                refresh()
                Toast.makeText(
                    fragment.requireContext(),
                    resultMessage,
                    Toast.LENGTH_LONG,
                ).show()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (fragment.isAdded) showError(fragment, error)
            } finally {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    private fun updateProgress(
        fragment: Fragment,
        progressBar: ProgressBar,
        message: TextView,
        progress: CloudSyncProgress,
    ) {
        when (progress.stage) {
            CloudSyncProgress.Stage.AUTHENTICATING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_authenticating)
            }
            CloudSyncProgress.Stage.CHECKING_CLOUD -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_checking_cloud)
            }
            CloudSyncProgress.Stage.PREPARING_LOCAL -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_preparing_local)
            }
            CloudSyncProgress.Stage.MERGING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_merging)
            }
            CloudSyncProgress.Stage.UPLOADING -> {
                progressBar.isIndeterminate = false
                progressBar.max = progress.total.coerceAtLeast(1)
                progressBar.progress = progress.current
                message.text = fragment.getString(
                    R.string.cloud_sync_progress_uploading,
                    progress.current,
                    progress.total,
                )
            }
            CloudSyncProgress.Stage.APPLYING_CLOUD -> {
                progressBar.isIndeterminate = true
                message.text = fragment.resources.getQuantityString(
                    R.plurals.cloud_sync_progress_applying_cloud,
                    progress.total,
                    progress.total,
                )
            }
            CloudSyncProgress.Stage.FINALIZING -> {
                progressBar.isIndeterminate = true
                message.setText(R.string.cloud_sync_progress_finalizing)
            }
        }
    }

    private fun runAction(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        refresh: () -> Unit,
        action: suspend () -> Int,
    ) {
        scope.launch {
            runCatching { action() }
                .onSuccess { message ->
                    refresh()
                    Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
                }
                .onFailure { error ->
                    showError(fragment, error)
                }
        }
    }

    private fun showError(fragment: Fragment, error: Throwable) {
        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(
                R.string.cloud_sync_error,
                error.message ?: error.javaClass.simpleName,
            ),
            Toast.LENGTH_LONG,
        ).show()
    }
}
