package com.streamflixreborn.streamflix.fragments.profiles

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.streamflixreborn.streamflix.models.Profile
import com.streamflixreborn.streamflix.utils.ProfileManager

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                ProfileManager.getAllProfilesFlow()?.collect { profileList ->
                    _profiles.value = profileList
                    _isLoading.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ProfilesViewModel", "Error loading profiles", e)
                _isLoading.value = false
            }
        }
    }

    fun switchToProfile(profileId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ProfileManager.switchToProfile(profileId)
        }
    }

    fun createProfile(name: String, onResult: (Profile?) -> Unit) {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { ProfileManager.createProfile(name) }
            onResult(profile)
        }
    }

    fun renameProfile(id: String, newName: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) { ProfileManager.renameProfile(id, newName) }
            onResult(success)
        }
    }

    fun deleteProfile(id: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) { ProfileManager.deleteProfile(id) }
            onResult(success)
        }
    }
}
