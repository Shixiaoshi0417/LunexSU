package me.weishu.kernelsu

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Bundle

/** Device-only control channel for a disposable debuggable fixture app.
 * Runs inside the recognized Manager so no production permission is relaxed.
 */
class AuthorizationTestRunner : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(Natives.isManager && Natives.kernelUAPIVersion == 6) {
                "Recognized LunexSU Manager and UAPI 6 Core required"
            }
            val pkg = requireNotNull(arguments.getString("fixture")) { "fixture required" }
            @Suppress("DEPRECATION")
            val info = targetContext.packageManager.getApplicationInfo(pkg, 0)
            check(info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && pkg != targetContext.packageName) {
                "Use a disposable debuggable fixture, never the Manager"
            }
            val uid = info.uid
            val profile = Natives.getAppProfile(pkg, uid)
            when (arguments.getString("action", "inspect")) {
                "grant" -> check(Natives.setAppProfile(profile.copy(
                    name = pkg,
                    allowSu = true,
                    authorizationMode = arguments.getString("mode", "1").toInt(),
                    authorizationDurationSeconds = arguments.getString("seconds", "300").toLong(),
                    authorizationOperation = 1
                ))) { "Grant failed (busy/stale/identity/storage)" }
                "revoke" -> check(Natives.setAppProfile(profile.copy(allowSu = false))) { "Revoke failed" }
                "edit" -> check(Natives.setAppProfile(profile.copy(authorizationOperation = 0))) { "KEEP edit failed" }
                "keep_grant" -> {
                    check(!profile.allowSu) { "KEEP grant regression requires a denied fixture" }
                    check(!Natives.setAppProfile(profile.copy(allowSu = true, authorizationOperation = 0))) {
                        "KEEP created or restored an authorization"
                    }
                }
                "stale" -> {
                    check(Natives.setAppProfile(profile.copy(authorizationOperation = 0))) { "KEEP edit failed" }
                    check(!Natives.setAppProfile(profile.copy(
                        name = pkg, allowSu = true, authorizationMode = 0, authorizationOperation = 1
                    ))) { "Stale generation changed policy" }
                }
                "inspect" -> Unit
                else -> error("Unknown action")
            }
            val saved = Natives.getAppProfile(pkg, uid)
            result.putString("auth", "uid=$uid mode=${saved.authorizationMode} state=${saved.authorizationState} remaining=${saved.authorizationRemainingSeconds} configured=${saved.allowSu}")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("error", failure.toString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
