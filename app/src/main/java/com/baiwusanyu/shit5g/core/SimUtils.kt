package com.baiwusanyu.shit5g.core

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** 双卡/多卡场景下定位“当前上网卡”的工具集合 */
object SimUtils {

    const val SUB_INVALID = -1

    fun subscriptionManager(context: Context): SubscriptionManager? = try {
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
    } catch (t: Throwable) {
        null
    }

    fun telephonyManager(context: Context): TelephonyManager? = try {
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    } catch (t: Throwable) {
        null
    }

    /** 当前正在承载数据的订阅 id（优先 active，其次 default） */
    fun activeDataSubId(context: Context): Int {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val method = SubscriptionManager::class.java
                    .getDeclaredMethod("getActiveDataSubscriptionId")
                val value = method.invoke(null) as? Int
                if (value != null && value > 0) return value
            } catch (t: Throwable) {
                // 部分 ROM 未暴露该方法，走 default 回退
            }
        }
        return try {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } catch (t: Throwable) {
            SUB_INVALID
        }
    }

    /** 订阅 id -> 物理卡槽 index（0 起） */
    fun slotIdOfSub(context: Context, subId: Int): Int? {
        if (subId <= 0) return null
        return try {
            subscriptionManager(context)?.getActiveSubscriptionInfo(subId)?.simSlotIndex
        } catch (t: Throwable) {
            null
        }
    }

    /** 解析当前上网卡所在卡槽；无法确定时：单卡返回其卡槽，否则返回 null */
    fun resolveDataSlot(context: Context): Int? {
        val subId = activeDataSubId(context)
        slotIdOfSub(context, subId)?.let { return it }
        val infos = activeSubscriptions(context)
        if (infos.size == 1) return infos[0].simSlotIndex
        return null
    }

    fun activeSubscriptions(context: Context): List<SubscriptionInfo> = try {
        subscriptionManager(context)?.activeSubscriptionInfoList ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 当前数据业务实际驻留的制式（TelephonyManager.NETWORK_TYPE_*），失败返回 -1 */
    fun servingDataNetworkType(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return try {
            telephonyManager(context)?.dataNetworkType ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    fun modemCount(context: Context): Int = try {
        val tm = telephonyManager(context) ?: return 1
        if (Build.VERSION.SDK_INT >= 30) {
            tm.activeModemCount
        } else {
            try {
                tm.javaClass.getMethod("getSlotCount").invoke(tm) as? Int ?: 1
            } catch (t: Throwable) {
                1
            }
        }
    } catch (t: Throwable) {
        1
    }
}
