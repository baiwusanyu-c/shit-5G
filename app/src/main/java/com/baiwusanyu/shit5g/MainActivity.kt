package com.baiwusanyu.shit5g

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.baiwusanyu.shit5g.core.ApplyResult
import com.baiwusanyu.shit5g.core.ModeSnapshot
import com.baiwusanyu.shit5g.core.NetMode
import com.baiwusanyu.shit5g.core.NetModeManager
import com.baiwusanyu.shit5g.core.Prefs
import com.baiwusanyu.shit5g.core.PrivilegedShell
import com.baiwusanyu.shit5g.core.SimUtils
import com.baiwusanyu.shit5g.core.TileLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/**
 * 主界面：Shizuku 授权引导、上网卡信息、模式查询/切换与命令日志。
 * 日常使用只需把磁贴拖进下拉面板，本页用于一次性授权与调试。
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_SHIZUKU = 1001
        private const val REQ_PHONE = 1002
        private const val SHIZUKU_PKG = "moe.shizuku.privileged"
        private const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
        private val executor = Executors.newSingleThreadExecutor()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tvMode: TextView
    private lateinit var tvModeSub: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSim: TextView
    private lateinit var tvLog: TextView
    private lateinit var spSlot: Spinner
    private lateinit var etCmd: EditText
    private lateinit var scrollRoot: ScrollView
    private var suppressSlotEvent = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus(); refreshModeAsync() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                runOnUiThread {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        toast("Shizuku 授权成功")
                        refreshStatus()
                        refreshModeAsync()
                    } else {
                        toast("Shizuku 授权被拒绝")
                        refreshStatus()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMode = findViewById(R.id.tv_mode)
        tvModeSub = findViewById(R.id.tv_mode_sub)
        tvStatus = findViewById(R.id.tv_status)
        tvSim = findViewById(R.id.tv_sim)
        tvLog = findViewById(R.id.tv_log)
        spSlot = findViewById(R.id.sp_slot)
        etCmd = findViewById(R.id.et_cmd)
        scrollRoot = findViewById(R.id.scroll_root)

        setupSlotSpinner()

        findViewById<Button>(R.id.btn_grant).setOnClickListener { onGrantClick() }
        findViewById<Button>(R.id.btn_open_shizuku).setOnClickListener { openShizuku() }
        findViewById<Button>(R.id.btn_4g).setOnClickListener { onApplyClick(NetMode.MODE_4G) }
        findViewById<Button>(R.id.btn_5g).setOnClickListener { onApplyClick(NetMode.MODE_5G) }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            refreshStatus(); refreshSimInfo(); refreshModeAsync()
        }
        findViewById<Button>(R.id.btn_run_cmd).setOnClickListener { onRunCustomCmd() }
        findViewById<Button>(R.id.btn_diag).setOnClickListener { onDiagClick() }
        val cbRescan = findViewById<CheckBox>(R.id.cb_rescan)
        cbRescan.isChecked = Prefs.forceRescan(this)
        cbRescan.setOnCheckedChangeListener { _, checked ->
            Prefs.setForceRescan(this, checked)
            logLine(if (checked) "已开启：切换后自动重新搜网" else "已关闭自动重新搜网")
        }

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshSimInfo()
        refreshModeAsync()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHONE) {
            refreshSimInfo()
            refreshModeAsync()
        }
    }

    // ---------------- UI helpers ----------------

    private fun setupSlotSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.slot_options, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSlot.adapter = adapter
        spSlot.setSelection(slotToPosition(Prefs.targetSlot(this)))
        spSlot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSlotEvent) return
                Prefs.setTargetSlot(this@MainActivity, positionToSlot(position))
                logLine("目标卡槽已设置为: " + spSlot.selectedItem)
                refreshModeAsync()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun positionToSlot(position: Int): Int = when (position) {
        1 -> 0
        2 -> 1
        else -> Prefs.SLOT_AUTO
    }

    private fun slotToPosition(slot: Int): Int = when (slot) {
        0 -> 1
        1 -> 2
        else -> 0
    }

    private fun refreshStatus() {
        val installed = packageManager.getInstalledPackages(0)
            .any { it.packageName == SHIZUKU_PKG }
        val sb = StringBuilder()
        sb.append("Shizuku: ")
        if (!installed) {
            sb.append("未安装（请点击“打开 Shizuku”下载安装）")
        } else if (!PrivilegedShell.isBinderAlive()) {
            sb.append("已安装，但服务未运行。请在 Shizuku 中通过“无线调试”启动服务")
        } else if (!PrivilegedShell.hasPermission()) {
            sb.append("服务运行中，点击下方按钮授予本应用权限")
        } else {
            sb.append("已就绪（身份 uid=")
            sb.append(PrivilegedShell.uid())
            sb.append(if (PrivilegedShell.uid() == 0) " root" else " shell")
            sb.append("，服务版本 ")
            sb.append(PrivilegedShell.serverVersion())
            sb.append("）")
        }
        tvStatus.text = sb.toString()
    }

    private fun refreshSimInfo() {
        val sb = StringBuilder()
        val infos = SimUtils.activeSubscriptions(this)
        if (infos.isEmpty()) {
            sb.append("SIM：未读到有效订阅（可能未授予电话权限）")
        } else {
            val dataSub = SimUtils.activeDataSubId(this)
            val dataSlot = SimUtils.resolveDataSlot(this)
            sb.append("SIM：")
            for (info in infos) {
                val mark = if (info.subscriptionId == dataSub) " ←上网卡" else ""
                val carrier = if (android.os.Build.VERSION.SDK_INT >= 28) info.carrierName else null
                sb.append("[卡槽").append(info.simSlotIndex + 1).append("] ")
                    .append(carrier ?: info.displayName ?: "未知")
                    .append("(sub=").append(info.subscriptionId).append(")").append(mark)
                sb.append("  ")
            }
            if (dataSlot != null) {
                sb.append("\n当前上网卡卡槽索引: ").append(dataSlot)
            }
        }
        tvSim.text = sb.toString()
    }

    private fun refreshModeAsync() {
        executor.execute {
            val snapshot: ModeSnapshot? = try {
                NetModeManager(this).query()
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (snapshot == null) {
                    refreshStatus()
                    return@runOnUiThread
                }
                refreshStatus()
                refreshSimInfo()
                renderModeHeader(snapshot)
                logLine("查询结果: " + (snapshot.mode?.display ?: "未知") +
                    " slot=" + snapshot.slot + " " + snapshot.detail)
            }
        }
    }

    private fun renderModeHeader(snapshot: ModeSnapshot) {
        val mode = snapshot.mode
        tvMode.text = if (mode == null) "—" else mode.display + " 模式"
        tvMode.setTextColor(when (mode) {
            NetMode.MODE_5G -> getColor(R.color.accent)
            NetMode.MODE_4G -> getColor(R.color.mode_4g)
            null -> getColor(R.color.text_secondary)
        })
        val slotText = if (snapshot.slot != null) "卡槽" + (snapshot.slot + 1) else "未知卡槽"
        tvModeSub.text = "上网卡: " + slotText + " · 来源: " + snapshot.source
    }

    // ---------------- actions ----------------

    private fun onGrantClick() {
        try {
            if (!PrivilegedShell.isBinderAlive()) {
                toast("Shizuku 服务未运行，请先在 Shizuku 内启动")
                openShizuku()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("已获得权限")
                refreshStatus()
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("权限被永久拒绝，请在系统设置或 Shizuku 管理中重新授权")
                return
            }
            Shizuku.requestPermission(REQ_SHIZUKU)
        } catch (t: Throwable) {
            toast("请求授权失败: " + t.message)
        }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (launch != null) {
            startActivity(launch)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD)))
        } catch (t: ActivityNotFoundException) {
            toast("无法打开下载页，请手动安装 Shizuku")
        }
    }

    private fun onApplyClick(mode: NetMode) {
        if (!PrivilegedShell.ready()) {
            toast(getString(R.string.toast_shizuku_not_ready))
            return
        }
        executor.execute {
            val result: ApplyResult = try {
                NetModeManager(this).apply(mode)
            } catch (t: Throwable) {
                logLine("执行异常: " + t)
                return@execute
            }
            runOnUiThread {
                for (line in result.log) logLine(line)
                toast(if (result.success)
                    getString(R.string.toast_switch_done, mode.display) else
                    getString(R.string.toast_switch_failed))
                refreshModeAsync()
            }
        }
    }

    /** 一键诊断：抓取 AOSP 各 reason 位掩码、Flyme 可能的私有存储键、modem 状态 */
    private fun onDiagClick() {
        if (!PrivilegedShell.ready()) {
            toast(getString(R.string.toast_shizuku_not_ready))
            return
        }
        val sub = SimUtils.activeDataSubId(this)
        val cmds = ArrayList<String>()
        // 磁贴事件放最前：这是判断点击是否送达的核心证据，避免被长输出淹没
        cmds.add("cmd telephony get-allowed-network-types-for-users -s 0")
        cmds.add("cmd telephony get-allowed-network-types-for-users -s 1")
        if (sub > 0) cmds.add("settings get global preferred_network_mode" + sub)
        cmds.add("settings list global | grep -iE 'preferred_network_mode|smart_5g|mobile_default_network|config_5g' | head -n 20")
        cmds.add("dumpsys telephony.registry | grep -oE 'mNetworkType=[A-Za-z0-9_]+' | head -n 6")
        executor.execute {
            runOnUiThread {
                logLine("---- 磁贴事件日志（先看这里）----")
                TileLog.read(this).lines().takeLast(40).forEach { logLine(it) }
            }
            for (cmd in cmds) {
                val r = PrivilegedShell.exec(cmd)
                runOnUiThread {
                    logLine("> " + cmd)
                    val text = r.output
                    logLine(if (text.isBlank()) "(无输出)" else text)
                }
            }
        }
    }

    private fun onRunCustomCmd() {
        val cmd = etCmd.text.toString().trim()
        if (cmd.isEmpty()) {
            toast("请输入命令")
            return
        }
        if (!PrivilegedShell.ready()) {
            toast(getString(R.string.toast_shizuku_not_ready))
            return
        }
        executor.execute {
            val r = PrivilegedShell.exec(cmd)
            runOnUiThread {
                logLine("> " + cmd)
                logLine("exit=" + r.exitCode)
                if (r.stdout.isNotBlank()) logLine(r.stdout.trim())
                if (r.stderr.isNotBlank()) logLine("[stderr] " + r.stderr.trim())
            }
        }
    }

    // ---------------- logging ----------------

    private fun logLine(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        mainHandler.post {
            tvLog.append("[" + stamp + "] " + line + System.lineSeparator())
            scrollRoot.post { scrollRoot.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
