# 网络模式切换（shit-5G）

安卓下拉快捷设置磁贴：**单击即在"当前上网卡"上切换 5G / 4G**（双卡也只调整正在
承载数据的卡）。无需 root、无需解锁 BL，依赖 [Shizuku](https://shizuku.rikka.app/) 提权。
主适配 **Flyme 12.6.0（Android 16）**，兼容 Android 11+ 的 AOSP 系 ROM。

## 背景
该死的广电卡5G信号在地铁上非常差，切换到4G就可以使用了，然而在公司楼下4G没有信号需要使用5G，但是 Flyme OS 切换信号的菜单非常复杂，这个该死的场景诞生了这个项目

**应用没有桌面图标**：装完后磁贴就是全部日常入口；未授权时点磁贴、或长按磁贴
会打开设置页（仅首次授权与调试时使用）。

## 工作原理（实机逆向验证）

| 环节 | 实现 |
| --- | --- |
| 快捷磁贴 | 标准 `TileService`（Android 7.0+） |
| 提权通道 | Shizuku **UserService**：我们的代码运行在 shell(uid=2000) 特权进程内 |
| 切换制式 | 反射直调 `ITelephony.setAllowedNetworkTypesForReason(subId, REASON_USER, 位掩码)`——与系统设置页"启用 5G"开关**同一条 binder 通道**，立即下发基带 |
| 状态读取 | `getAllowedNetworkTypesForReason`（真实配置，非猜测） |
| UI 同步 | 成功后镜像写 Flyme 私有键 `preferred_network_mode<subId>`（33=5G / 22=4G），设置页开关跟随 |
| 定位上网卡 | `SubscriptionManager.getActiveDataSubscriptionId()`（公开 API） |
| 降级链 | binder 不可用 → Flyme 键 → `cmd telephony`（其他 ROM）→ `mobile_default_network_type` |

位掩码（AOSP `NETWORK_TYPE_BITMASK_*`）：5G=`11001111101111111111`（含 NR），
4G=`01001111101111111111`（封顶 LTE）。

### 为什么必须 Shizuku（运行时依赖）

`MODIFY_PHONE_STATE` 是签名级权限，公开 API 与 Settings 键写入都无法触达基带配置
（实测 Flyme 上写键只改 UI 镜像，**binder 调用才是本体**）。Shizuku 服务进程必须
在运行——它不是开发工具，而是运行时组件；重启手机后需通过"无线调试"重新
启动一次 Shizuku（配对信息保留，两步约 10 秒；root/Sui 可开机常驻）。

## 安装使用

1. 手机安装 [Shizuku](https://shizuku.rikka.app/zh-hans/download/)，开发者选项 →
   无线调试 → 配对并启动 Shizuku
2. 安装本应用（Release 页下载 APK）
3. 下拉控制中心 → 编辑 → 添加「4G/5G」磁贴
4. 首次点磁贴会弹出设置页 → 「请求 Shizuku 授权」→ 允许
5. 完成。此后点磁贴即切换；长按磁贴可回到设置页

注意：**先卸载旧版再装 release 版**（调试签名与发布签名不同，直接覆盖安装会失败）。

## 构建与发布

### 本地一键发布（Windows）

```powershell
powershell -File scripts\release.ps1
```

流水线：定位 JDK → 首次自动生成 `shit5g.keystore` + `keystore.properties` →
**单元测试阀门**（失败即阻断）→ `assembleRelease` → 产物复制到 `release/shit5g-<版本>-release.apk`。

> ⚠️ 务必备份 `shit5g.keystore` 与 `keystore.properties`（已 gitignore）：
> 丢失后无法给已安装用户推送可覆盖升级。

### GitHub Release（CI 自动打包）

推送 `v*` 标签即触发 `.github/workflows/release.yml`：JDK17 + Android SDK →
跑单测 → assembleRelease → 自动挂到 GitHub Release。

- 不配置任何 secret：CI 用 debug 签名产出可安装包（适合个人自用）
- 正式分发：在仓库 Secrets 配置 `KEYSTORE_BASE64`（keystore 的 base64）、
  `KEYSTORE_PASSWORD`、`KEY_PASSWORD`，CI 即用发布密钥签名

版本号在 `app/build.gradle.kts`（`versionCode`/`versionName`），发版时同步打 tag。

### 开发调试

```powershell
.\gradlew.bat :app:assembleDebug          # 调试包
.\gradlew.bat :app:testReleaseUnitTest    # 单测阀门
```

App 内「诊断」按钮输出：双卡槽位掩码、Flyme 键状态、磁贴事件日志、真实驻留制式，
配合"自定义提权命令"框可远程排查新机型适配。

## 测试阀门

`app/src/test/java/.../NetModeLogicTest.kt` 固化全部纯逻辑契约（发布脚本强制执行）：

- AOSP 位掩码字符串 ↔ binder 数值掩码一致性（NR/LTE 位）
- 位掩码 → 模式判定、`other()` 取反
- Flyme 值映射（33↔5G、22↔4G）与旧全局键映射（20/13）
- shell 结果宽松成功判定对已知失败输出的拒绝

## Flyme 12.6.0 适配结论（实机验证）

1. 该 ROM **未注册 `cmd telephony` 服务**，AOSP shell 命令不可用 → 走 binder 直调
2. 设置页"启用 5G"开关 = binder 调用 + 写 `preferred_network_mode<subId>` 镜像；
   只写镜像键不会改变基带（本项目曾踩此坑，已修复为 binder 优先）
3. 请关闭"智能 5G"（`smart_5g_switch`），否则系统会在后台自动改回制式
4. 重装 APK 后需在面板**移除磁贴再重新添加**（旧注册是死图标：可见但点击无事件，
   「诊断」里的磁贴事件日志可确认服务是否被绑定）
5. 切换后状态栏有 3~10 秒搜网延迟、数据瞬断属正常；SA -only 地区（如广电）
   无 5G 覆盖时封顶 4G 后可能观察不到图标变化

## 已知限制

- 非 root：重启后 Shizuku 需重新无线调试启动（磁贴会提示并直达设置页）
- 只调整"用户允许制式上限"，不修改运营商限制（carrier restriction）与 SA/NSA 配置
- 双卡在线且切换上网卡时自动跟随（自动模式），也可在设置页固定卡槽

## 目录结构

```
app/src/main/java/com/baiwusanyu/shit5g/
├── App.kt                        # 全局 context（UserService 绑定用）
├── MainActivity.kt               # 设置/调试页（无桌面图标，磁贴唤入）
├── NetModeTileService.kt         # 快捷磁贴（点击切换/查询分离、事件日志）
├── service/
│   └── TelephonyUserService.kt   # Shizuku v13 User Service：shell 进程内反射调 ITelephony
└── core/
    ├── NetMode.kt                # 模式枚举 + AOSP 位掩码常量
    ├── NetModeManager.kt         # 策略链：binder → Flyme 键 → cmd telephony → 旧全局键
    ├── PrivilegedTelephony.kt    # UserService 客户端（绑定/负缓存/失败原因）
    ├── PrivilegedShell.kt        # Shizuku shell 命令通道（settings 等）
    ├── SimUtils.kt               # 当前上网卡 subId/卡槽解析
    ├── Prefs.kt                  # 卡槽偏好、模式缓存、重搜开关
    └── TileLog.kt                # 磁贴事件环形日志（诊断"无法点击"）
app/src/main/aidl/                # ITelephonyPrivileged.aidl
app/src/test/java/.../            # NetModeLogicTest（发布阀门）
scripts/release.ps1               # 本地发布流水线
.github/workflows/release.yml     # GitHub Release CI
```

## 构建环境备注（本机）

本机安全软件会拦截 JDK 17 匿名 Unix 域套接字，导致 Gradle 守护进程/测试进程报
`Unable to establish loopback connection`。已用 `--patch-module` 覆盖
`PipeImpl`/`WEPollSelectorImpl` 强制 TCP 环回修复（补丁类在 `D:\tools\patch-out`，
`scripts/release.ps1` 检测到该目录时自动注入，其他机器无感）。
