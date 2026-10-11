package com.shixiaoshi0417.lunexsu.ui.screen.appprofile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.shixiaoshi0417.lunexsu.Natives

/** Only a Core with authorization support has Once credits and Timed countdowns to refresh. */
fun shouldPollAuthorization(special: Boolean, profile: Natives.Profile): Boolean =
    !special && profile.authorizationSupported

/** Both UI modes use this transaction boundary, including countdown refresh.
 * Never retry a rejected generation or write through the permanent-only ABI.
 */
class AuthorizationRepository(
    private val read: suspend () -> Natives.Profile,
    private val write: suspend (Natives.Profile) -> Int,
) {
    private val mutex = Mutex()

    suspend fun refresh(): Natives.Profile = mutex.withLock { read() }

    data class SaveResult(val error: Int, val profile: Natives.Profile)

    suspend fun save(profile: Natives.Profile): SaveResult = mutex.withLock {
        val error = when {
            profile.authorizationReadError != 0 -> profile.authorizationReadError
            profile.authorizationMode !in 0..2 -> -22
            profile.allowSu && profile.authorizationMode != 0 && !profile.authorizationSupported -> -95
            else -> write(profile)
        }
        // Success is acknowledged only with a fresh Core snapshot. A read
        // failure is explicit: never replace the saved mode with a UI default.
        val fresh = read()
        val verifiedError = when {
            error != 0 -> error
            fresh.authorizationReadError != 0 -> fresh.authorizationReadError
            fresh.authorizationMode != profile.authorizationMode || fresh.allowSu != profile.allowSu -> -71
            profile.authorizationOperation == 1 && profile.authorizationMode == 2 &&
                fresh.authorizationDurationSeconds != profile.authorizationDurationSeconds -> -71
            else -> 0
        }
        SaveResult(verifiedError, fresh)
    }
}

fun authorizationGrant(profile: Natives.Profile, mode: Int, minutes: String): Natives.Profile? {
    if (profile.authorizationReadError != 0 || mode !in 0..2) return null
    if (mode != 0 && (!profile.authorizationSupported || profile.currentUid % 100000 !in 10000..19999)) return null
    val seconds = if (mode == 2) {
        val value = minutes.toLongOrNull()?.takeIf { it in 1..525600 } ?: return null
        value * 60
    } else 0L
    return profile.copy(allowSu = true, authorizationMode = mode,
        authorizationDurationSeconds = seconds, authorizationOperation = 1)
}
