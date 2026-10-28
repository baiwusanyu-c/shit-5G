package com.baiwusanyu.shit5g.service

import android.os.IBinder
import com.baiwusanyu.shit5g.ITelephonyPrivileged

/**
 * Shizuku v13 User Service：类本身就是 Binder（继承 AIDL Stub），
 * 由 Shizuku 服务端在 shell(uid=2000) 特权进程里反射实例化。
 * 无需注册到 Manifest；该进程不受非 SDK 接口限制，
 * 因此可直接反射 ITelephony 真实接口——与系统设置页"启用 5G"开关完全同源。
 */
class TelephonyUserService : ITelephonyPrivileged.Stub() {

    companion object {
        /** TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER */
        private const val REASON_USER = 0
    }

    private val telephony: Any by lazy {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java)
            .invoke(null, "phone") as? IBinder
            ?: error("phone service not found")
        val stubCls = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
        stubCls.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("ITelephony.asInterface returned null")
    }

    private fun invokeTelephony(
        name: String,
        types: Array<Class<*>>,
        vararg args: Any?
    ): Any? = telephony.javaClass.getMethod(name, *types).invoke(telephony, *args)

    override fun call(method: String?, arg: String?): String = try {
        when (method) {
            "get" -> {
                val v = invokeTelephony(
                    "getAllowedNetworkTypesForReason",
                    arrayOf(Integer.TYPE, Integer.TYPE),
                    arg!!.toInt(), REASON_USER
                ) as Long
                "OK:" + v
            }
            "set" -> {
                val parts = arg!!.split("|")
                val ok = invokeTelephony(
                    "setAllowedNetworkTypesForReason",
                    arrayOf(Integer.TYPE, Integer.TYPE, java.lang.Long.TYPE),
                    parts[0].toInt(), REASON_USER, parts[1].toLong()
                ) as Boolean
                if (ok) "OK" else "ERR:rejected-by-phone-service"
            }
            "ping" -> "OK"
            else -> "ERR:unknown_method"
        }
    } catch (t: Throwable) {
        val real = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
        "ERR:" + real.javaClass.simpleName + ":" + real.message
    }
}
