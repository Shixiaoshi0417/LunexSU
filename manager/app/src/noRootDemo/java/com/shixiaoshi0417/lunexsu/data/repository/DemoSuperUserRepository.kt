package com.shixiaoshi0417.lunexsu.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.data.model.AppInfo

/** No package/root service IPC: these are explicitly fictional demonstration applications. */
class SuperUserRepositoryImpl : SuperUserRepository {
    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = Result.success(
        (1..4).map { index ->
            val name = "com.example.lunexsu.demo.app$index"
            val uid = 10100 + index
            val info = PackageInfo().apply {
                packageName = name
                versionName = "1.0-demo"
                applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid }
            }
            AppInfo("[DEMO] Application $index", info, Natives.getAppProfile(name, uid))
        } to listOf(0)
    )
    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = Result.success(
        currentApps.map { it.copy(profile = Natives.getAppProfile(it.profileKey, it.uid)) }
    )
}
