package com.baiwusanyu.shit5g.core

import android.content.Context

/** 一次模式查询的结果快照 */
class ModeSnapshot(
    val mode: NetMode?,
    val slot: Int?,
    val source: String,
    val detail: String = ""
)

/** 一次模式切换的执行结果 */
class ApplyResult(
    val success: Boolean,
    val mode: NetMode,
    val slot: Int?,
    val strategy: String,
    val log: List<String>
)

/**
 * 网络制式切换核心逻辑。
 *
 * 主策略（AOSP 标准，Android 11+ 提供，Flyme 12.6/Android 16 已确认存在）：
 *   cmd telephony set-allowed-network-types-for-users -s <卡槽> <20位二进制位掩码>
 *   该命令要求调用方为 shell/root，经 Shizuku 提权后满足。
 *
 * 回退策略：settings put global mobile_default_network_type <20|13>
 *   部分 OEM ROM 兼容读取该旧键；标准 AOSP 多卡下可能不生效，仅作兜底。
 */
class NetModeManager(private val context: Context) {

    companion object {
        const val CMD_SET = "cmd telephony set-allowed-network-types-for-users"
        const val CMD_GET = "cmd telephony get-allowed-network-types-for-users"

        /**
         * Flyme 私有制式键（实测 Flyme 12.6.0 未注册 cmd telephony 服务，
         * 其设置页"启用 5G"开关读写 Settings.Global 的该组键）：
         * 键名 = preferred_network_mode + subId，值 33=允许5G，22=封顶4G
         */
        const val FLYME_KEY_PREFIX = "preferred_network_mode"
        const val FLYME_5G = "33"
        const val FLYME_4G = "22"

        fun parseFlymeValue(text: String): NetMode? = when (text.trim()) {
            FLYME_5G -> NetMode.MODE_5G
            FLYME_4G -> NetMode.MODE_4G
            else -> null
        }

        /** REASON_USER 数值位掩码（AOSP NETWORK_TYPE_BITMASK_*，NR = 1<<19） */
        val MASK_5G: Long = NetMode.BITMASK_5G.toLong(2)
        val MASK_4G: Long = NetMode.BITMASK_4G.toLong(2)
        const val NR_BIT: Long = 1L shl 19
        const val LTE_BIT: Long = 1L shl 13

        /** 由 REASON_USER 位掩码判定当前模式（含 NR 视为 5G，仅 LTE 视为 4G） */
        fun modeFromMask(mask: Long): NetMode? = when {
            mask and NR_BIT != 0L -> NetMode.MODE_5G
            mask and LTE_BIT != 0L -> NetMode.MODE_4G
            else -> null
        }

        fun maskToBinary(mask: Long): String = java.lang.Long.toBinaryString(mask)

        /** 解析 "NR|LTE|CDMA|EVDO|GSM|WCDMA" 形式的返回值 */
        fun parseBitmaskString(text: String): NetMode? {
            val tokens = text.trim().uppercase().split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            return when {
                tokens.any { it.startsWith("NR") } -> NetMode.MODE_5G
                tokens.any { it.startsWith("LTE") } -> NetMode.MODE_4G
                else -> null
            }
        }

        fun parseLegacyValue(text: String): NetMode? = when (text.trim()) {
            NetMode.LEGACY_NR -> NetMode.MODE_5G
            NetMode.LEGACY_LTE -> NetMode.MODE_4G
            else -> null
        }
    }

    /** binder 成功后同步 Flyme 的 UI 镜像键，保证设置页开关状态跟随 */
    private fun mirrorFlymeKeys(mode: NetMode, logs: MutableList<String>) {
        val fKey = flymeKeyOrNull()
        if (fKey != null) {
            val fv = if (mode == NetMode.MODE_5G) FLYME_5G else FLYME_4G
            PrivilegedShell.exec("settings put global " + fKey + " " + fv)
            logs.add("> mirror " + fKey + " = " + fv)
        }
        PrivilegedShell.exec(
            "settings put global mobile_default_network_type " + NetMode.legacyValueOf(mode)
        )
    }

    /** 探测本 ROM 是否使用 Flyme 制式键（键存在即认为是该体系） */
    private fun flymeKeyOrNull(): String? {
        val sub = SimUtils.activeDataSubId(context)
        if (sub <= 0) return null
        val key = FLYME_KEY_PREFIX + sub
        val r = PrivilegedShell.exec("settings get global " + key)
        val v = r.stdout.trim()
        return if (v.isNotEmpty() && v != "null") key else null
    }

    /** 结合用户设置与系统状态，解析本次操作的目标卡槽；null 表示无法确定 */
    fun targetSlotOrNull(): Int? {
        val configured = Prefs.targetSlot(context)
        if (configured != Prefs.SLOT_AUTO) return configured
        return SimUtils.resolveDataSlot(context)
    }

    /** 查询目标卡当前的网络制式上限 */
    fun query(): ModeSnapshot {
        val slot = targetSlotOrNull()
        if (PrivilegedShell.ready()) {
            // -1) 真实通道：shell 进程直调 ITelephony REASON_USER（与设置页开关同源）
            val subId = SimUtils.activeDataSubId(context)
            if (subId > 0) {
                val mask = PrivilegedTelephony.getAllowedMask(subId)
                if (mask != null) {
                    val mode = modeFromMask(mask)
                    if (mode != null) {
                        return ModeSnapshot(mode, slot, "binder:REASON_USER", maskToBinary(mask))
                    }
                }
            }
            // 0) Flyme 私有通道（与系统"启用 5G"开关同源，保证双向同步）
            val fKey = flymeKeyOrNull()
            if (fKey != null) {
                val rf = PrivilegedShell.exec("settings get global " + fKey)
                parseFlymeValue(rf.stdout)?.let {
                    return ModeSnapshot(it, slot, "flyme:" + fKey, rf.stdout.trim())
                }
            }
            if (slot != null) {
                val r = PrivilegedShell.exec(CMD_GET + " -s " + slot)
                if (r.exitCode == 0 && r.stdout.isNotBlank()) {
                    val mode = parseBitmaskString(r.stdout)
                    if (mode != null) {
                        return ModeSnapshot(mode, slot, CMD_GET, r.stdout.trim())
                    }
                }
            }
            val r2 = PrivilegedShell.exec("settings get global mobile_default_network_type")
            parseLegacyValue(r2.stdout)?.let {
                return ModeSnapshot(it, slot, "settings global", r2.stdout.trim())
            }
        }
        // 最后以实际驻留制式估算（仅用于展示，不代表配置上限）
        return when (val serving = SimUtils.servingDataNetworkType(context)) {
            20 -> ModeSnapshot(NetMode.MODE_5G, slot, "serving(estimated)", "NR")
            12, 13, 14, 19 -> ModeSnapshot(NetMode.MODE_4G, slot, "serving(estimated)", "LTE")
            else -> ModeSnapshot(Prefs.lastMode(context), slot, "cache", "serving=" + serving)
        }
    }

    /** 在目标卡上应用指定制式 */
    fun apply(mode: NetMode): ApplyResult {
        val logs = ArrayList<String>()
        val slot = targetSlotOrNull()
        if (!PrivilegedShell.ready()) {
            logs.add("Shizuku 未就绪（未启动或未授权）")
            return ApplyResult(false, mode, slot, "none", logs)
        }

        // 策略 -1：真实 binder 通道（首选，立即下发基带，无需重搜网）
        val subId = SimUtils.activeDataSubId(context)
        if (subId > 0) {
            val mask = if (mode == NetMode.MODE_5G) MASK_5G else MASK_4G
            logs.add("> binder setAllowedNetworkTypesForReason(sub=" + subId +
                ", USER, " + maskToBinary(mask) + ")")
            val err = PrivilegedTelephony.setAllowedMask(subId, mask)
            if (err == null) {
                val back = PrivilegedTelephony.getAllowedMask(subId)
                logs.add("> verify mask=" + (back?.let { maskToBinary(it) } ?: "null"))
                if (back != null && modeFromMask(back) == mode) {
                    mirrorFlymeKeys(mode, logs)
                    Prefs.setLastMode(context, mode)
                    return ApplyResult(true, mode, slot, "binder:REASON_USER", logs)
                }
                logs.add("binder 校验不一致，继续走键值回退")
            } else {
                logs.add("binder 通道不可用(" + err + ")，回退键值通道")
            }
        }

        // 策略 0：Flyme 私有通道（实测该 ROM 无 cmd telephony 服务）
        val fKey = flymeKeyOrNull()
        if (fKey != null) {
            val fv = if (mode == NetMode.MODE_5G) FLYME_5G else FLYME_4G
            val cmd0 = "settings put global " + fKey + " " + fv
            logs.add("> " + cmd0)
            val r0 = PrivilegedShell.exec(cmd0)
            logs.add(r0.output)
            // 同步旧回退键，避免两套通道状态互相打架
            PrivilegedShell.exec(
                "settings put global mobile_default_network_type " + NetMode.legacyValueOf(mode)
            )
            val back = PrivilegedShell.exec("settings get global " + fKey)
            logs.add("> verify: " + fKey + " = " + back.stdout.trim())
            if (parseFlymeValue(back.stdout) == mode) {
                Prefs.setLastMode(context, mode)
                if (Prefs.forceRescan(context)) forceRescan(logs)
                return ApplyResult(true, mode, slot, fKey, logs)
            }
            logs.add("Flyme 通道校验失败，继续尝试标准策略")
        }

        val bitmask = NetMode.bitmaskOf(mode)
        val singleModem = SimUtils.modemCount(context) <= 1
        if (slot == null && !singleModem) {
            logs.add("无法确定当前上网卡，请在应用内手动指定卡槽或检查电话权限")
        }

        // 策略 1：AOSP 标准命令
        if (slot != null || singleModem) {
            val cmd = if (slot != null) {
                CMD_SET + " -s " + slot + " " + bitmask
            } else {
                CMD_SET + " " + bitmask
            }
            logs.add("> " + cmd)
            val r = PrivilegedShell.exec(cmd)
            logs.add(r.output)
            if (r.successLoose()) {
                if (slot != null) {
                    val v = PrivilegedShell.exec(CMD_GET + " -s " + slot)
                    logs.add("> verify: " + (CMD_GET + " -s " + slot))
                    logs.add(v.output)
                    val confirmed = parseBitmaskString(v.stdout)
                    if (confirmed == mode || confirmed == null) {
                        Prefs.setLastMode(context, mode)
                        return ApplyResult(true, mode, slot, CMD_GET, logs)
                    }
                    logs.add("校验结果与目标不一致，尝试兼容回退")
                } else {
                    Prefs.setLastMode(context, mode)
                    return ApplyResult(true, mode, slot, CMD_SET, logs)
                }
            } else {
                logs.add("主策略失败，尝试兼容回退")
            }
        }

        // 策略 2：旧全局键回退（部分 ROM 有效）
        val legacy = NetMode.legacyValueOf(mode)
        val cmd2 = "settings put global mobile_default_network_type " + legacy
        logs.add("> " + cmd2)
        val r2 = PrivilegedShell.exec(cmd2)
        logs.add(r2.output)
        val ok2 = r2.exitCode == 0
        if (ok2) Prefs.setLastMode(context, mode)
        return ApplyResult(ok2, mode, slot, "settings global (回退)", logs)
    }

    /**
     * 强制重新搜网：部分 ROM（实测 Flyme 12.6 疑似）只在下次网络重选时应用新制式，
     * 快速开关一次飞行模式可让状态栏立刻反映切换结果。
     */
    private fun forceRescan(logs: MutableList<String>) {
        val on = PrivilegedShell.exec("cmd connectivity airplane-mode enable")
        logs.add("> airplane-mode enable: " + on.output)
        try {
            Thread.sleep(2500)
        } catch (ignored: InterruptedException) {
        }
        val off = PrivilegedShell.exec("cmd connectivity airplane-mode disable")
        logs.add("> airplane-mode disable: " + off.output)
    }

    /** 取反切换：5G <-> 4G */
    fun toggle(): ApplyResult {
        val current = query().mode ?: Prefs.lastMode(context)
        // 未知时默认按“当前是 5G”处理（Flyme 出厂默认允许 5G）
        val target = current?.other() ?: NetMode.MODE_4G
        return apply(target)
    }
}
