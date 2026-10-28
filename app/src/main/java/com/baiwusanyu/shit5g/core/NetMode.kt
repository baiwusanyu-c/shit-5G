package com.baiwusanyu.shit5g.core

/**
 * 上网卡允许接入的最高网络制式。
 *
 * 位掩码字符串直接取自 AOSP [com.android.phone.TelephonyShellCommand] 的官方帮助文档，
 * 为 20 位二进制串，对应 TelephonyManager.NETWORK_TYPE_BITMASK_* 的组合：
 *  - 5G = NR|LTE|CDMA|EVDO|GSM|WCDMA（允许 5G，低制式自动回落）
 *  - 4G = LTE|CDMA|EVDO|GSM|WCDMA（封顶 4G）
 */
enum class NetMode(val display: String) {
    MODE_4G("4G"),
    MODE_5G("5G");

    fun other(): NetMode = if (this == MODE_5G) MODE_4G else MODE_5G

    companion object {
        const val BITMASK_5G: String = "11001111101111111111"
        const val BITMASK_4G: String = "01001111101111111111"

        fun bitmaskOf(mode: NetMode): String = if (mode == MODE_5G) BITMASK_5G else BITMASK_4G

        /** 兼容回退用的旧全局键取值（TelephonyManager.NETWORK_TYPE_NR / NETWORK_TYPE_LTE） */
        const val LEGACY_NR: String = "20"
        const val LEGACY_LTE: String = "13"

        fun legacyValueOf(mode: NetMode): String = if (mode == MODE_5G) LEGACY_NR else LEGACY_LTE
    }
}
