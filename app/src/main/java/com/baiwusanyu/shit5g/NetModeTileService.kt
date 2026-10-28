package com.baiwusanyu.shit5g

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.baiwusanyu.shit5g.core.ApplyResult
import com.baiwusanyu.shit5g.core.NetMode
import com.baiwusanyu.shit5g.core.NetModeManager
import com.baiwusanyu.shit5g.core.Prefs
import com.baiwusanyu.shit5g.core.PrivilegedShell
import com.baiwusanyu.shit5g.core.TileLog
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/**
 * 下拉快捷设置磁贴：点击后在“当前上网卡”上切换 5G / 4G。
 * 长按磁贴进入主界面（通过 MainActivity 的 QS_TILE_PREFERENCES 入口）。
 */
class NetModeTileService : TileService() {

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile
        private var toggling = false
        @Volatile
        private var querying = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val binderListener = Shizuku.OnBinderReceivedListener {
        TileLog.add(applicationContext, "Shizuku binder 就绪")
        refreshAsync()
    }

    override fun onCreate() {
        super.onCreate()
        TileLog.add(this, "onCreate")
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
        } catch (t: Throwable) {
            TileLog.add(this, "注册监听失败: " + t.javaClass.simpleName)
        }
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
        } catch (ignored: Throwable) {
        }
        super.onDestroy()
    }

    override fun onStartListening() {
        TileLog.add(this, "onStartListening ready=" + PrivilegedShell.ready())
        render(Prefs.lastMode(applicationContext), toggling)
        startQuery()
    }

    /** 查询与切换分离：查询不再占用切换通道，点击不会被查询吞掉 */
    private fun startQuery() {
        if (querying || toggling) return
        querying = true
        executor.execute {
            val snapshot = try {
                NetModeManager(applicationContext).query()
            } catch (t: Throwable) {
                null
            } finally {
                querying = false
            }
            mainHandler.post {
                if (!toggling) {
                    render(snapshot?.mode ?: Prefs.lastMode(applicationContext), false)
                }
            }
        }
    }

    override fun onClick() {
        TileLog.add(this, "onClick 触发")
        try {
            handleClick()
        } catch (t: Throwable) {
            TileLog.add(this, "onClick 异常: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun handleClick() {
        val tile = qsTile ?: return
        if (!PrivilegedShell.ready()) {
            toast(getString(R.string.toast_shizuku_not_ready))
            render(Prefs.lastMode(applicationContext), false)
            openSetup()
            return
        }
        if (toggling) {
            // 上一次切换还在执行：本次直接忽略（磁贴已显示"切换中"）
            return
        }
        toggling = true
        tile.label = getString(R.string.tile_switching)
        tile.updateTile()
        executor.execute {
            val result: ApplyResult? = try {
                NetModeManager(applicationContext).toggle()
            } catch (t: Throwable) {
                null
            } finally {
                toggling = false
                querying = false
            }
            mainHandler.post {
                if (result == null) {
                    render(Prefs.lastMode(applicationContext), false)
                    toast(getString(R.string.toast_switch_failed))
                    return@post
                }
                render(result.mode, false)
                TileLog.add(applicationContext, "切换结果 success=" + result.success +
                    " mode=" + result.mode.display + " strategy=" + result.strategy)
                if (result.success) {
                    val suffix = if (result.slot != null) "（卡槽" + (result.slot + 1) + "）" else ""
                    toast(getString(R.string.toast_switch_done, result.mode.display) + suffix)
                } else {
                    toast(getString(R.string.toast_switch_failed))
                }
            }
        }
    }

    private fun refreshAsync() {
        startQuery()
    }

    private fun render(mode: NetMode?, working: Boolean) {
        val tile = qsTile ?: return
        when (mode) {
            NetMode.MODE_5G -> {
                tile.label = "5G 模式"
                tile.state = Tile.STATE_ACTIVE
            }
            NetMode.MODE_4G -> {
                tile.label = "4G 模式"
                tile.state = Tile.STATE_INACTIVE
            }
            null -> {
                // 未知状态也必须保持可点击：此前用 STATE_UNAVAILABLE 会导致
                // 查询失败时磁贴变灰且无法点击（Flyme 上表现为完全失效）
                tile.label = getString(R.string.tile_name)
                tile.state = if (working) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            }
        }
        tile.updateTile()
    }

    /** 未提权时直达设置页（应用已无桌面图标，这里是唯一授权入口） */
    private fun openSetup() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        } catch (t: Throwable) {
            try {
                startActivity(intent)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }
}
