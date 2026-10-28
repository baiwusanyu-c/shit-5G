package com.baiwusanyu.shit5g.core

import android.content.Context

/** 轻量本地配置：目标卡槽选择与最近一次模式缓存（供磁贴快速渲染） */
object Prefs {

    private const val FILE = "net_mode_prefs"
    private const val KEY_TARGET_SLOT = "target_slot"
    private const val KEY_LAST_MODE = "last_mode"

    const val SLOT_AUTO = -1

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun targetSlot(context: Context): Int =
        prefs(context).getInt(KEY_TARGET_SLOT, SLOT_AUTO)

    fun setTargetSlot(context: Context, slot: Int) {
        prefs(context).edit().putInt(KEY_TARGET_SLOT, slot).apply()
    }

    fun lastMode(context: Context): NetMode? = when (prefs(context).getString(KEY_LAST_MODE, null)) {
        NetMode.MODE_5G.name -> NetMode.MODE_5G
        NetMode.MODE_4G.name -> NetMode.MODE_4G
        else -> null
    }

    /** 切换成功后是否强制飞行模式快速开关一次（触发重新搜网） */
    fun forceRescan(context: Context): Boolean =
        prefs(context).getBoolean("force_rescan", false)

    fun setForceRescan(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("force_rescan", value).apply()
    }

    fun setLastMode(context: Context, mode: NetMode?) {
        prefs(context).edit()
            .putString(KEY_LAST_MODE, mode?.name)
            .apply()
    }
}
