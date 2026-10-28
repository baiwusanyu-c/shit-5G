package com.baiwusanyu.shit5g.core

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.baiwusanyu.shit5g.App
import com.baiwusanyu.shit5g.BuildConfig
import com.baiwusanyu.shit5g.ITelephonyPrivileged
import com.baiwusanyu.shit5g.service.TelephonyUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 特权 binder 通道客户端：绑定 shell 进程里的 TelephonyUserService。
 * 绑定失败会进入 60 秒负缓存，避免每次点击都阻塞超时。
 */
object PrivilegedTelephony {

    @Volatile
    private var service: ITelephonyPrivileged? = null
    @Volatile
    private var lastError: String = ""
    @Volatile
    private var nextRetryAt: Long = 0
    private val lock = Any()

    fun failureReason(): String = lastError

    private fun current(): ITelephonyPrivileged? =
        service?.takeIf { it.asBinder().isBinderAlive }

    fun ensure(timeoutMs: Long = 12_000): ITelephonyPrivileged? {
        current()?.let { return it }
        if (System.currentTimeMillis() < nextRetryAt) return null
        if (!PrivilegedShell.ready()) {
            lastError = "shizuku-not-ready"
            return null
        }
        val context = App.context ?: run {
            lastError = "app-context-null"
            return null
        }
        synchronized(lock) {
            current()?.let { return it }
            val latch = CountDownLatch(1)
            var failure: String? = null
            try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(
                        context.packageName,
                        TelephonyUserService::class.java.name
                    )
                )
                    .daemon(false)
                    .processNameSuffix("priv")
                    .debuggable(BuildConfig.DEBUG)
                Shizuku.bindUserService(args, object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        service = ITelephonyPrivileged.Stub.asInterface(binder)
                        lastError = ""
                        nextRetryAt = 0
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                    }
                })
            } catch (t: Throwable) {
                failure = "bind-threw:" + t.javaClass.simpleName + ":" + t.message
            }
            if (failure == null && !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                failure = "bind-timeout"
            }
            if (failure != null) {
                lastError = failure
                nextRetryAt = System.currentTimeMillis() + 60_000
                service = null
            }
            return current()
        }
    }

    /** 读取 REASON_USER 允许制式位掩码；失败返回 null */
    fun getAllowedMask(subId: Int): Long? {
        val svc = ensure() ?: return null
        return try {
            val r = svc.call("get", subId.toString())
            if (r.startsWith("OK:")) r.substring(3).toLongOrNull() else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 写入 REASON_USER 允许制式位掩码；成功返回 null，失败返回错误信息 */
    fun setAllowedMask(subId: Int, mask: Long): String? {
        val svc = ensure() ?: return "priv-unavailable(" + lastError + ")"
        return try {
            val r = svc.call("set", subId.toString() + "|" + mask.toString())
            if (r == "OK") null else r
        } catch (t: Throwable) {
            "EX:" + t.javaClass.simpleName + ":" + t.message
        }
    }
}
