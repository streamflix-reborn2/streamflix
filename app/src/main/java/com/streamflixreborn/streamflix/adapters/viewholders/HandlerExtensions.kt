package com.streamflixreborn.streamflix.adapters.viewholders

import android.os.Handler

/**
 * Keeps delayed ViewHolder callbacks explicit without depending on the core-ktx postDelayed overload.
 */
internal fun Handler.postDelayed(
    delayMillis: Long,
    action: () -> Unit,
): Boolean = postDelayed(Runnable(action), delayMillis)
