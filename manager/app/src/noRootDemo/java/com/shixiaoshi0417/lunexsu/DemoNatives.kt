package com.shixiaoshi0417.lunexsu

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.shixiaoshi0417.lunexsu.Natives.Profile.RootProfileFlag
import com.shixiaoshi0417.lunexsu.demo.DemoState
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** Compile-time replacement for UI previews. No native library or IOCTL is used. */
object Natives {
    const val MINIMAL_SUPPORTED_KERNEL = 32513
    const val KERNEL_SU_DOMAIN = "u:r:ksu:s0"
    const val ROOT_UID = 0
    const val ROOT_GID = 0
    const val FLAG_KSU_NO_NEW_PRIVS = 1L
    val version get() = BuildConfig.VERSION_CODE
    val isSafeMode = false
    val isLkmMode = true
    val isLkmBundled = true
    val isLateLoadMode = false
    val isManager = true // Simulated presentation; this grants no operating-system permission.
    val isPrBuild = false
    val kernelUAPIVersion = 8
    val managerUAPIVersion = 8
    private val profiles = ConcurrentHashMap<String, Profile>()
    fun superkeySalt(): ByteArray? = null
    fun authenticateSuperkey(proof: ByteArray): Boolean = false
    fun uidShouldUmount(uid: Int) = true
    fun getAppProfile(key: String?, uid: Int): Profile = profiles[key ?: uid.toString()] ?: Profile(
        name = key.orEmpty(), currentUid = uid, authorizationSupported = true,
    )
    fun setAppProfileResult(profile: Profile?): Int {
        if (profile == null) return -22
        profiles[profile.name] = profile.copy(authorizationState = if (profile.allowSu) 1 else 0)
        return 0
    }
    fun setAppProfile(profile: Profile?) = setAppProfileResult(profile) == 0
    fun isSuEnabled() = DemoState.features.getOrDefault("su_compat", 1) == 1L
    fun setSuEnabled(enabled: Boolean): Boolean { DemoState.features["su_compat"] = if (enabled) 1 else 0; return true }
    fun isKernelUmountEnabled() = DemoState.features.getOrDefault("kernel_umount", 1) == 1L
    fun setKernelUmountEnabled(enabled: Boolean): Boolean { DemoState.features["kernel_umount"] = if (enabled) 1 else 0; return true }
    fun isSelinuxHideEnabled() = DemoState.features.getOrDefault("selinux_hide", 1) == 1L
    fun setSelinuxHideEnabled(enabled: Boolean): Int { DemoState.features["selinux_hide"] = if (enabled) 1 else 0; return 0 }
    fun getUserName(uid: Int): String? = "demo_$uid"
    fun getSuperuserCount() = profiles.values.count { it.allowSu }
    fun setDefaultUmountModules(umountModules: Boolean) = setAppProfile(getAppProfile("$", 9999).copy(umountModules = umountModules))
    fun isDefaultUmountModules() = getAppProfile("$", 9999).umountModules
    fun isFullFeatured() = true

    // Keep the production profile UI/parcel format; all updates stay in the map above.
    @Keep @Immutable @Parcelize @Serializable
    data class Profile(
        val name: String, val currentUid: Int = 0, val allowSu: Boolean = false,
        val rootUseDefault: Boolean = true, val rootTemplate: String? = null,
        val uid: Int = ROOT_UID, val gid: Int = ROOT_GID, val groups: List<Int> = mutableListOf(),
        val capabilities: List<Int> = mutableListOf(), val context: String = KERNEL_SU_DOMAIN,
        val namespace: Int = Namespace.INHERITED.ordinal, val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true, var rules: String = "", val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
        val authorizationMode: Int = 0, val authorizationDurationSeconds: Long = 300,
        val authorizationRemainingSeconds: Long = 0, val authorizationState: Int = 0,
        val authorizationGeneration: Long = 0, val authorizationOperation: Int = 0,
        val authorizationSupported: Boolean = false, val authorizationReadError: Int = 0,
    ) : Parcelable {
        @Keep enum class RootProfileFlag(val display: String, val desc: Int) {
            NO_NEW_PRIVS("NO_NEW_PRIVS", R.string.profile_flags_desc_no_new_privs)
        }
        enum class Namespace { INHERITED, GLOBAL, INDIVIDUAL }
        constructor() : this("")
    }
}

fun List<RootProfileFlag>.toRawFlags(): Long = fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }
fun List<RootProfileFlag>.toOrdinalList(): List<Int> = map { it.ordinal }
fun Long.toRootProfileFlags(): List<RootProfileFlag> = RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }
