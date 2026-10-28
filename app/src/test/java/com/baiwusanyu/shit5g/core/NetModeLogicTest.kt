package com.baiwusanyu.shit5g.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单元测试阀门：固化制式转换的全部纯逻辑。
 * 发布脚本 scripts/release.ps1 会强制先跑 testReleaseUnitTest，
 * 任何破坏 AOSP 位掩码 / Flyme 值映射 / 判定逻辑的改动都会阻断发布。
 */
class NetModeLogicTest {

    @Test
    fun binderMasksCarryExpectedRadioBits() {
        assertTrue(NetModeManager.MASK_5G and NetModeManager.NR_BIT != 0L)
        assertTrue(NetModeManager.MASK_5G and NetModeManager.LTE_BIT != 0L)
        assertTrue(NetModeManager.MASK_4G and NetModeManager.NR_BIT == 0L)
        assertTrue(NetModeManager.MASK_4G and NetModeManager.LTE_BIT != 0L)
    }

    @Test
    fun aospBitmaskStringsEqualBinderMasks() {
        assertEquals(NetModeManager.MASK_5G, NetMode.BITMASK_5G.toLong(2))
        assertEquals(NetModeManager.MASK_4G, NetMode.BITMASK_4G.toLong(2))
    }

    @Test
    fun modeFromMaskDecisions() {
        assertEquals(NetMode.MODE_5G, NetModeManager.modeFromMask(NetModeManager.MASK_5G))
        assertEquals(NetMode.MODE_4G, NetModeManager.modeFromMask(NetModeManager.MASK_4G))
        assertNull(NetModeManager.modeFromMask(0L))
    }

    @Test
    fun parseBitmaskStringHandlesAospGetOutput() {
        assertEquals(NetMode.MODE_5G, NetModeManager.parseBitmaskString("NR|LTE|CDMA|EVDO|GSM|WCDMA"))
        assertEquals(NetMode.MODE_4G, NetModeManager.parseBitmaskString("LTE|CDMA|EVDO|GSM|WCDMA"))
        assertEquals(NetMode.MODE_5G, NetModeManager.parseBitmaskString("NR"))
        assertEquals(NetMode.MODE_4G, NetModeManager.parseBitmaskString("LTE"))
        assertNull(NetModeManager.parseBitmaskString(""))
        assertNull(NetModeManager.parseBitmaskString("UNKNOWN"))
    }

    @Test
    fun flymeValueMappingStays33Is5G22Is4G() {
        assertEquals(NetMode.MODE_5G, NetModeManager.parseFlymeValue("33"))
        assertEquals(NetMode.MODE_4G, NetModeManager.parseFlymeValue("22"))
        assertNull(NetModeManager.parseFlymeValue("null"))
        assertNull(NetModeManager.parseFlymeValue(""))
    }

    @Test
    fun legacySettingsValueMapping() {
        assertEquals(NetMode.MODE_5G, NetModeManager.parseLegacyValue("20"))
        assertEquals(NetMode.MODE_4G, NetModeManager.parseLegacyValue("13"))
        assertNull(NetModeManager.parseLegacyValue("null"))
    }

    @Test
    fun toggleInvertsModeBothWays() {
        assertEquals(NetMode.MODE_5G, NetMode.MODE_4G.other())
        assertEquals(NetMode.MODE_4G, NetMode.MODE_5G.other())
    }

    @Test
    fun shellSuccessLooseRejectsKnownFailures() {
        assertTrue(
            ShellResult(0, "set-allowed-network-types-for-users completed", "").successLoose()
        )
        assertFalse(ShellResult(1, "", "boom").successLoose())
        assertFalse(ShellResult(0, "Can't find service: telephony", "").successLoose())
        assertFalse(ShellResult(0, "set-allowed... failed", "").successLoose())
        assertFalse(ShellResult(0, "No valid subscription found.", "").successLoose())
    }
}
