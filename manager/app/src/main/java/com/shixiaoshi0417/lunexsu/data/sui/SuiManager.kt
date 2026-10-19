package com.shixiaoshi0417.lunexsu.data.sui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.MainActivity
import com.shixiaoshi0417.lunexsu.ui.util.getKsuDaemonPath
import com.shixiaoshi0417.lunexsu.ui.util.submitRootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest

/** Sui's ACL and callbacks are independent from the kernel root allowlist. */
object SuiManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private val mutable = MutableStateFlow(SuiUiState())
    val state = mutable.asStateFlow()
    private val requests = SuiRequestQueue()
    private val epoch = AtomicLong()
    private val lastForeground = AtomicLong()
    private val ipc = SuiIpcExecutor()
    private val lifecycle = SuiLifecycleCommand()
    private val watchingLifecycle = AtomicBoolean()
    private lateinit var context: Context
    private data class Session(val epoch: Long, val service: IShizukuService, val death: IBinder.DeathRecipient)
    @Volatile private var session: Session? = null

    @Synchronized fun start(application: Context) {
        if (::context.isInitialized) return
        context = application.applicationContext
        scope.launch {
            while (true) {
                delay(1_000)
                requests.expire(SystemClock.elapsedRealtime()).forEach {
                    runCatching { reply(it, SuiDecision.DENY_ONCE) }
                }
                publishRequests()
            }
        }
        refresh(false)
        scope.launch {
            repeat(5) {
                delay(5_000)
                val current = mutable.value
                if (current.busy) return@repeat
                if (current.hostAttached || !current.status.desiredEnabled || current.status.pendingReboot) return@launch
                refresh(false)
            }
        }
    }

    fun onForeground() {
        if (!::context.isInitialized) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastForeground.getAndSet(now) >= 3_000) refresh(false)
    }

    fun refresh(includeApps: Boolean = true) = operation(allowReconciliation = true) { refreshStatus(includeApps) }

    private fun refreshStatus(includeApps: Boolean) {
        val status = if (lifecycle.isPending()) {
            lifecycle.reconcile { SuiStatus.parse(command("status")) } ?: return
        } else SuiStatus.parse(command("status"))
        mutable.update { it.copy(status = status, reconciling = false, error = null) }
        if (status.desiredEnabled && status.serviceHealthy && !status.pendingReboot) {
            remote { lease -> connect(lease) }
            if (includeApps) loadApps()
        } else disconnect()
    }

    fun prepare() = operation { try { command("prepare") } finally { refreshLater() } }
    fun enable() = operation { try { command("prepare"); command("enable") } finally { refreshLater() } }
    fun disable() = operation { disconnect(); try { command("disable") } finally { refreshLater() } }

    fun setPermission(app: SuiApp, permission: SuiPermission) = operation {
        check(app.uid != Process.myUid() && app in mutable.value.apps)
        remote { lease ->
            val currentSession = active()
            val current = context.packageManager.getPackagesForUid(app.uid).orEmpty().map { packageInfo(it) }
            check(fingerprint(current) == app.identity) { "Application identity changed; refresh before granting" }
            check(session?.epoch == currentSession.epoch) { "Expired Sui connection" }
            lease.checkActive()
            currentSession.service.updateFlagsForUid(app.uid, SuiPermission.MASK, permission.flag)
        }
        loadApps()
    }

    fun decide(request: SuiRequest, decision: SuiDecision) {
        if (!requests.remove(request)) return
        publishRequests()
        scope.launch {
            runCatching { reply(request, decision) }
                .onFailure { error -> mutable.update { it.copy(error = error.message ?: error.javaClass.simpleName) } }
        }
    }

    private fun operation(allowReconciliation: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            operations.withLock {
                mutable.update { it.copy(busy = true, error = null) }
                try {
                    check(allowReconciliation || !lifecycle.isPending()) { pendingMessage() }
                    block()
                }
                catch (error: Exception) {
                    mutable.update { it.copy(error = if (lifecycle.isPending()) pendingMessage()
                        else error.message ?: error.javaClass.simpleName) }
                } finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }

    private fun refreshLater() { refresh(false) }

    private fun pendingMessage() = context.getString(R.string.sui_outcome_pending)

    private fun watchLifecycle() {
        if (!watchingLifecycle.compareAndSet(false, true)) return
        scope.launch {
            try {
                // One retained mutation and one observer, never a retry of the mutation.
                // While it still runs, do not enqueue status jobs behind the shared shell.
                while (lifecycle.isPending()) {
                    delay(5_000)
                    operations.withLock {
                        mutable.update { it.copy(busy = true) }
                        try { refreshStatus(false) }
                        catch (error: Exception) { mutable.update { it.copy(error = if (lifecycle.isPending())
                            pendingMessage() else error.message ?: error.javaClass.simpleName) } }
                        finally { mutable.update { it.copy(busy = false, reconciling = lifecycle.isPending()) } }
                    }
                }
            } finally {
                watchingLifecycle.set(false)
                if (lifecycle.isPending()) watchLifecycle()
            }
        }
    }

    private fun command(action: String): String {
        require(action in setOf("status", "prepare", "enable", "disable"))
        val path = "'" + getKsuDaemonPath().replace("'", "'\\''") + "'"
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val future = submitRootShell {
            check(isRoot) { "Root is unavailable" }
            newJob().add("$path sui $action").to(output, errors).exec()
        }
        val result = try { future.get(15, TimeUnit.SECONDS) }
        catch (error: Exception) {
            if (action != "status" && (error is TimeoutException || error is InterruptedException)) {
                lifecycle.retain(future)
                disconnect()
                mutable.update { it.copy(reconciling = true, error = pendingMessage()) }
                watchLifecycle()
            } else if (!future.isDone) future.cancel(false)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        }
        check(result.isSuccess) { errors.joinToString("\n").ifEmpty { "Sui command failed (${result.code})" } }
        return output.joinToString("\n")
    }

    private fun <T> remote(block: (SuiIpcExecutor.Lease) -> T): T {
        return try { ipc.call(block) }
        catch (error: TimeoutException) {
            disconnect() // Invalidates requests/callbacks and a late connect's session epoch.
            throw IllegalStateException(context.getString(R.string.sui_ipc_timeout), error)
        } catch (error: InterruptedException) { disconnect(); throw error }
    }

    private fun active(): Session = checkNotNull(session?.takeIf { it.service.asBinder().isBinderAlive }) {
        "Sui is not connected"
    }

    private fun connect(lease: SuiIpcExecutor.Lease) {
        lease.checkActive()
        if (session?.service?.asBinder()?.isBinderAlive == true) return
        val generation = epoch.incrementAndGet()
        val activity = HiddenApiBypass.invoke(Class.forName("android.os.ServiceManager"), null,
            "getService", "activity") as? IBinder ?: error("Activity service unavailable")
        val data = Parcel.obtain()
        val result = Parcel.obtain()
        val binder = try {
            data.writeInterfaceToken("android.app.IActivityManager")
            data.writeInt(2)
            check(activity.transact(('_'.code shl 24) or ('S'.code shl 16) or ('U'.code shl 8) or 'I'.code, data, result, 0))
            result.readException()
            checkNotNull(result.readStrongBinder()) { "Sui bridge unavailable" }
        } finally { data.recycle(); result.recycle() }
        val service = IShizukuService.Stub.asInterface(binder)
        check(service.uid == 0 && service.version == 13) { "Unexpected Sui server" }
        val death = IBinder.DeathRecipient {
            if (disconnect(generation)) {
                scope.launch {
                    // Bounded recovery only; never spin or repeatedly execute mutating commands.
                    repeat(5) {
                        delay(5_000)
                        if (session != null || !mutable.value.status.desiredEnabled) return@launch
                        refresh(false)
                    }
                }
            }
        }
        binder.linkToDeath(death, 0)
        synchronized(this) {
            if (epoch.get() != generation || runCatching { lease.checkActive() }.isFailure) {
                binder.unlinkToDeath(death, 0)
                error("Sui connection was cancelled")
            }
            session = Session(generation, service, death)
        }
        try {
            service.attachApplication(application(generation), Bundle().apply {
                putString("shizuku:attach-package-name", BuildConfig.APPLICATION_ID)
                putInt("shizuku:attach-api-version", 13)
            })
        } catch (error: Exception) { disconnect(generation); throw error }
    }

    private fun application(generation: Long) = object : IShizukuApplication.Stub() {
        private fun requireRoot() { check(Binder.getCallingUid() == 0) { "Only the root Sui server may call this host" } }
        override fun bindApplication(data: Bundle?) {
            requireRoot()
            check(data?.getInt("shizuku:attach-reply-uid", -1) == 0)
            synchronized(SuiManager) {
                if (session?.epoch == generation) mutable.update { it.copy(hostAttached = true) }
            }
        }
        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) { requireRoot() }
        override fun dispatchServerBinder(binder: IBinder?, packageName: String?, generation: Long): Boolean {
            requireRoot()
            return false // Never accept an unauthenticated replacement through a callback.
        }
        override fun showPermissionConfirmation(uid: Int, pid: Int, packageName: String?, requestCode: Int) {
            requireRoot()
            val name = packageName.orEmpty()
            val identity = runCatching {
                val info = packageInfo(name)
                check(info.applicationInfo?.uid == uid)
                fingerprint(listOf(info))
            }.getOrDefault("")
            val request = SuiRequest(generation, uid, pid, name, requestCode, SystemClock.elapsedRealtime(), identity)
            val accepted = synchronized(SuiManager) {
                if (session?.epoch != generation) return
                identity.isNotEmpty() && requests.offer(request)
            }
            if (!accepted) scope.launch { runCatching { reply(request, SuiDecision.DENY_ONCE) } }
            publishRequests()
            notifyRequest()
        }
    }

    private fun disconnect(expectedEpoch: Long? = null): Boolean {
        val previous = synchronized(this) {
            if (expectedEpoch != null && session?.epoch != expectedEpoch) return false
            epoch.incrementAndGet()
            val old = session
            session = null
            requests.clear()
            mutable.update { it.copy(hostAttached = false, requests = emptyList(), apps = emptyList()) }
            old
        }
        runCatching { previous?.service?.asBinder()?.unlinkToDeath(previous.death, 0) }
        if (::context.isInitialized) context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        return true
    }

    private fun reply(request: SuiRequest, decision: SuiDecision) {
        remote { lease ->
            val current = active()
            check(current.epoch == request.connection) { "Expired Sui connection" }
            if (decision.allowed && !request.expired(SystemClock.elapsedRealtime())) {
                val info = packageInfo(request.packageName)
                check(info.applicationInfo?.uid == request.uid && fingerprint(listOf(info)) == request.identity) {
                    "Requesting application changed; no permission was granted"
                }
            }
            check(session?.epoch == current.epoch) { "Expired Sui connection" }
            // Recheck at dispatch, not only in the periodic expiry job: Binder/PM work can be slow.
            val effective = if (request.expired(SystemClock.elapsedRealtime())) SuiDecision.DENY_ONCE else decision
            lease.checkActive()
            current.service.dispatchPermissionConfirmationResult(request.uid, request.pid, request.requestCode, Bundle().apply {
                putBoolean("shizuku:request-permission-reply-allowed", effective.allowed)
                putBoolean("shizuku:request-permission-reply-is-onetime", effective.once)
                putBoolean("shizuku:request-permission-reply-is-shell", effective.shell)
            })
        }
    }

    private fun loadApps() {
        val apps = context.packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
            .filter { info -> info.applicationInfo?.uid?.let { it != Process.myUid() && it % 100_000 >= 10_000 } == true }
            .groupBy { it.applicationInfo!!.uid }
        val values = remote { lease ->
            val current = active()
            current.epoch to apps.map { (uid, packages) ->
                lease.checkActive()
                SuiApp(uid, packages.sortedBy { it.packageName }.joinToString { it.packageName },
                    packages.joinToString { context.packageManager.getApplicationLabel(it.applicationInfo!!).toString() },
                    SuiPermission.fromFlags(current.service.getFlagsForUid(uid, SuiPermission.MASK)), fingerprint(packages))
            }.sortedBy { it.label.lowercase() }
        }
        synchronized(this) {
            check(session?.epoch == values.first) { "Expired Sui connection" }
            mutable.update { it.copy(apps = values.second) }
        }
    }

    private fun packageInfo(name: String): PackageInfo =
        context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)

    private fun fingerprint(packages: List<PackageInfo>): String {
        check(packages.isNotEmpty())
        return packages.sortedBy { it.packageName }.joinToString("\n") { info ->
            val signers = checkNotNull(info.signingInfo?.apkContentsSigners).map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
            }.sorted()
            check(signers.isNotEmpty())
            "${info.packageName}|${info.applicationInfo?.uid}|${info.firstInstallTime}|${info.lastUpdateTime}|${signers.joinToString()}"
        }
    }

    private fun publishRequests() {
        val snapshot = synchronized(this) {
            val current = requests.snapshot().filter { it.connection == session?.epoch }
            mutable.update { it.copy(requests = current) }
            current
        }
        if (snapshot.isEmpty() && ::context.isInitialized)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }

    private fun notifyRequest() {
        if (!::context.isInitialized) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.sui_permission_request), NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTIFICATION, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(context.getString(R.string.sui_permission_request))
            .setContentText(context.getString(R.string.sui_open_manager))
            .setContentIntent(pending).setAutoCancel(true).build()
        // Notifications may be denied. The request remains visible inside the Manager.
        runCatching { manager.notify(NOTIFICATION, notice) }
    }

    private const val CHANNEL = "lunexsu_sui_permissions"
    private const val NOTIFICATION = 0x535549
}
