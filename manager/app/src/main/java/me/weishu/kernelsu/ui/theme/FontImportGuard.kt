package me.weishu.kernelsu.ui.theme

/** A completed import may apply only if it is still the user's latest font choice. */
internal class FontImportGuard {
    private var revision = 0L

    @Synchronized
    fun begin(): Long = ++revision

    @Synchronized
    fun <T> change(apply: () -> T): T {
        ++revision
        return apply()
    }

    @Synchronized
    fun commit(token: Long, apply: () -> Unit): Boolean {
        if (token != revision) return false
        apply()
        return true
    }
}
