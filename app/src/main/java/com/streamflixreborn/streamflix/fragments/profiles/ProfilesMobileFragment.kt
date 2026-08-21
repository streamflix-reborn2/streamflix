package com.streamflixreborn.streamflix.fragments.profiles

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.ProfileAdapter
import com.streamflixreborn.streamflix.databinding.FragmentProfilesMobileBinding
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ProfileColorPicker
import com.streamflixreborn.streamflix.utils.ProfileManager
import com.streamflixreborn.streamflix.utils.ProfileSwitchPinGuard
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ProfilesMobileFragment : Fragment() {

    private var _binding: FragmentProfilesMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<ProfilesViewModel>()

    private lateinit var profileAdapter: ProfileAdapter
    private var isSwitchingProfile = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfilesMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.profiles.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { profiles ->
                profileAdapter.submitList(profiles)
                binding.tvProfilesEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(
            onProfileClick = { profile ->
                selectProfile(profile)
            },
            onProfileLongClick = { profile ->
                showProfileActions(profile, profileAdapter.currentList.size)
            }
        )

        binding.rvProfiles.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = profileAdapter
        }
    }

    private fun setupListeners() {
        binding.btnAddProfile.setOnClickListener {
            showCreateProfileDialog()
        }

        binding.btnManageProfiles.setOnClickListener {
            showManageProfilesDialog()
        }
    }

    private fun selectProfile(profile: Profile) {
        if (isSwitchingProfile) return
        val cameFromProviders = findNavController().previousBackStackEntry?.destination?.id == R.id.providers
        if (profile.id == ProfileManager.activeProfileId) {
            navigateToNext(cameFromProviders)
            return
        }

        ProfileSwitchPinGuard.verifyCurrentProfile(requireContext()) {
            switchToProfile(profile, cameFromProviders)
        }
    }

    private fun switchToProfile(profile: Profile, cameFromProviders: Boolean) {
        if (isSwitchingProfile || !isAdded) return
        val oldProfileId = ProfileManager.activeProfileId
        val oldLang = oldProfileId?.let { AppLanguageManager.getProfileLanguage(requireContext(), it) }
        isSwitchingProfile = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ProfileManager.switchToProfile(profile.id, preserveProvider = !cameFromProviders)
                if (!isAdded) return@launch
                val newLang = AppLanguageManager.getProfileLanguage(requireContext(), profile.id)
                if (newLang != (oldLang ?: AppLanguageManager.SYSTEM_LANGUAGE)) {
                    requireActivity().apply {
                        finish()
                        startActivity(Intent(this, this::class.java).apply {
                            if (cameFromProviders) putExtra("NAV_TO_PROVIDERS", true)
                        })
                    }
                } else {
                    navigateToNext(cameFromProviders)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                isSwitchingProfile = false
            }
        }
    }

    private fun navigateToNext(cameFromProviders: Boolean = false) {
        val destination = when {
            cameFromProviders -> R.id.providers
            UserPreferences.currentProvider != null -> R.id.home
            else -> R.id.providers
        }

        val navController = findNavController()
        if (destination == R.id.home) {
            // Switching profiles closes the current profile's Room database.
            // Remove Home inclusively so its fragment and ViewModel are rebuilt
            // with flows from the newly selected profile's database.
            navController.popBackStack(R.id.home, true)
            navController.navigate(R.id.home)
        } else if (!navController.popBackStack(destination, false)) {
            navController.navigate(destination)
        }
    }

    private fun showProfileActions(profile: Profile, profileCount: Int = 1) {
        val items = mutableListOf<String>().apply {
            add(getString(R.string.profile_action_switch))
            add(getString(R.string.profile_action_rename))
            add(getString(R.string.profile_action_color))
            if (profileCount > 1) {
                add(getString(R.string.profile_action_delete))
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(profile.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    getString(R.string.profile_action_switch) -> selectProfile(profile)
                    getString(R.string.profile_action_rename) -> showRenameDialog(profile)
                    getString(R.string.profile_action_color) -> showColorDialog(profile)
                    getString(R.string.profile_action_delete) -> showDeleteConfirmDialog(profile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCreateProfileDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.profile_name_hint)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_create_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.createProfile(name) { profile ->
                        if (profile != null) {
                            Toast.makeText(requireContext(), R.string.profile_created, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(profile: Profile) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(profile.name)
            hint = getString(R.string.profile_name_hint)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    viewModel.renameProfile(profile.id, newName) { success ->
                        if (success) {
                            Toast.makeText(requireContext(), R.string.profile_renamed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showColorDialog(profile: Profile) {
        ProfileColorPicker.show(requireContext(), profile.avatarColor) { color ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (ProfileManager.setProfileColor(profile.id, color)) {
                    Toast.makeText(requireContext(), R.string.profile_color_changed, Toast.LENGTH_SHORT).show()
                    if (profile.id == ProfileManager.activeProfileId) requireActivity().recreate()
                }
            }
        }
    }

    private fun showDeleteConfirmDialog(profile: Profile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteProfile(profile.id) { success ->
                    if (success) {
                        Toast.makeText(requireContext(), R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), R.string.profile_delete_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManageProfilesDialog() {
        lifecycleScope.launch {
            val profiles = ProfileManager.getAllProfiles()
            val names = profiles.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.profile_manage_title)
                .setItems(names) { _, which ->
                    if (which < profiles.size) {
                        showProfileActions(profiles[which], profiles.size)
                    }
                }
                .setPositiveButton(R.string.profile_add_btn) { _, _ ->
                    showCreateProfileDialog()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
