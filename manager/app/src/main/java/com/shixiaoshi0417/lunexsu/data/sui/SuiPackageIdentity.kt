package com.shixiaoshi0417.lunexsu.data.sui

/**
 * PackageManager data captured without retaining mutable Android PackageInfo objects. Sui grants
 * apply to a whole UID; an edit carries every member's fingerprint and the root server rechecks it.
 */
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
