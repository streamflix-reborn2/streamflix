package com.streamflixreborn.streamflix.sync

data class CloudSyncProgress(
    val stage: Stage,
    val current: Int = 0,
    val total: Int = 0,
) {
    enum class Stage {
        AUTHENTICATING,
        CHECKING_CLOUD,
        PREPARING_LOCAL,
        MERGING,
        UPLOADING,
        APPLYING_CLOUD,
        FINALIZING,
    }
}
