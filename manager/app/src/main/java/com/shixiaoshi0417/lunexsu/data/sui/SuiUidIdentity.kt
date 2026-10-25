package com.shixiaoshi0417.lunexsu.data.sui

import org.json.JSONObject

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
        fun fromServer(uid: Int, requester: String, text: String): SuiUidIdentity {
            require(text.length <= 128 * 1024) { "Oversized Sui identity" }
            val json = JSONObject(text)
            require(json.getInt("schema") == 1 && json.getInt("uid") == uid)
            val members = json.getJSONArray("packages")
            require(members.length() in 1..256)
            val packages = (0 until members.length()).map { index ->
                val member = members.getJSONObject(index)
                val signers = member.getJSONArray("signers")
                require(signers.length() in 1..32)
                require(member.getLong("first_install") > 0
                    && member.getLong("last_update") >= member.getLong("first_install"))
                SuiPackageIdentity(member.getString("package"), member.getInt("uid"),
                    member.getLong("first_install"), member.getLong("last_update"),
                    (0 until signers.length()).map { signers.getString(it).also { digest ->
                        require(digest.matches(Regex("[0-9a-f]{64}")))
                    } })
            }
            val byName = packages.associateBy { it.packageName }
            require(byName.size == packages.size) { "Duplicate UID member" }
            return capture(uid, requester, { packages.map { it.packageName } }, { byName.getValue(it) })
        }

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
