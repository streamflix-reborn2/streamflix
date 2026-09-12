package com.streamflixreborn.streamflix.sync

/**
 * Local data was found while the device is claimed by another cloud account.
 * Preserve it until the user explicitly resolves the account conflict.
 */
class CloudAccountDataConflictException(
    message: String = "This device contains local data owned by another cloud account. " +
        "The local data was preserved; resolve the account conflict before syncing.",
) : IllegalStateException(message)
