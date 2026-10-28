package com.baiwusanyu.shit5g.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.nio.charset.Charset

/** 一次提权 shell 命令的执行结果 */
class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {

    val output: String
        get() = (stdout + "\n" + stderr).trim()

    /** 宽松的成功判定：退出码为 0 且输出中没有明显的错误关键字 */
    fun successLoose(): Boolean {
        val text = output.lowercase()
        return exitCode == 0 &&
            !text.contains("failed") &&
            !text.contains("no valid") &&
            !text.contains("not a valid") &&
            !text.contains("exception") &&
            !text.contains("permission denied") &&
            !text.contains("unknown command") &&
            !text.contains("can't find service")
    }
}

/**
 * 通过 Shizuku 以 shell(uid=2000) / root(uid=0) 身份执行命令。
 * 所有方法均为阻塞调用，必须在子线程使用。
 */
object PrivilegedShell {

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun ready(): Boolean = isBinderAlive() && hasPermission()

    /** 当前提权身份：0=root，2000=shell(adb)，-1=不可用 */
    fun uid(): Int = try {
        Shizuku.getUid()
    } catch (t: Throwable) {
        -1
    }

    fun serverVersion(): Int = try {
        Shizuku.getVersion()
    } catch (t: Throwable) {
        -1
    }

    /**
     * Shizuku 13.1.5 将 newProcess 设为 private，这里通过反射调用。
     * 返回的 IRemoteProcess 继承 java.lang.Process。
     */
    private fun newProcess(cmd: Array<String>, env: Array<String>?, cwd: String?): Process {
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, cwd) as Process
    }

    fun exec(command: String, timeoutMs: Long = 20_000L): ShellResult {
        if (!ready()) {
            return ShellResult(-1, "", "Shizuku 未就绪（未启动或未授权）")
        }
        return try {
            val process = newProcess(
                arrayOf("/system/bin/sh", "-c", command),
                null,
                "/"
            )
            val outBuf = StringBuffer()
            val errBuf = StringBuffer()
            val tOut = Thread { readAll(process.inputStream, outBuf) }
            val tErr = Thread { readAll(process.errorStream, errBuf) }
            tOut.isDaemon = true
            tErr.isDaemon = true
            tOut.start()
            tErr.start()
            tOut.join(timeoutMs)
            tErr.join(2_000L)
            var exit = -1
            try {
                exit = process.waitFor()
            } catch (t: Throwable) {
                try { process.destroy() } catch (ignored: Throwable) { }
            }
            ShellResult(exit, outBuf.toString(), errBuf.toString())
        } catch (t: Throwable) {
            ShellResult(-1, "", "执行失败: " + t.javaClass.simpleName + " - " + t.message)
        }
    }

    private fun readAll(stream: InputStream, sink: StringBuffer) {
        try {
            stream.use { input ->
                val bytes = input.readBytes()
                sink.append(String(bytes, Charset.forName("UTF-8")))
            }
        } catch (t: Throwable) {
            // 进程退出后读取中断属于正常现象，忽略
        }
    }
}
