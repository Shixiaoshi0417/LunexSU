package com.shixiaoshi0417.lunexsu.data.sui

/** PackageManager data captured without retaining mutable Android PackageInfo objects. */
internal data class SuiPackageIdentity(
    val packageName: String,
    val uid: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val signers: List<String>,
) {
    fun fingerprint(): String {
        check(signers.isNotEmpty() && signers.all { it.isNotEmpty() })
        return "$packageName|$uid|$firstInstallTime|$lastUpdateTime|${signers.sorted().joinToString()}"
    }
}

/** Sui persists grants by UID, so the consent identity must include every installed member. */
internal data class SuiUidIdentity(val packages: List<String>, val fingerprint: String) {
    companion object {
        fun capture(uid: Int, requester: String, packagesForUid: () -> List<String>,
            packageInfo: (String) -> SuiPackageIdentity): SuiUidIdentity {
            fun names() = packagesForUid().distinct().sorted()
            val packages = names()
            check(uid >= 0 && packages.isNotEmpty() && requester in packages) { "Unverified requesting UID" }
            val fingerprint = packages.joinToString("\n") { name ->
                val info = packageInfo(name)
                check(info.packageName == name && info.uid == uid) { "Application UID changed" }
                info.fingerprint()
            }
            // Refuse membership changes while gathering the individual package identities, too.
            check(names() == packages) { "Shared UID packages changed" }
            return SuiUidIdentity(packages, fingerprint)
        }
    }
}
