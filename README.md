<p align="center"><img src="docs/icon.png" width="120" alt="Facet"></p>

<h1 align="center">Facet</h1>

<p align="center">自定义应用在系统各处显示的名称和图标 —— 桌面、最近任务、通知、设置。<br>一个 LSPosed / Vector 模块，针对小米澎湃 OS 3（HyperOS 3）。</p>

## 效果

| 位置 | 名称 | 图标 |
|---|---|---|
| 桌面（含文件夹） | ✅ | ✅ 自动套用当前主题的图标形状 |
| 最近任务 | ✅ | ✅ |
| 通知（单条、折叠组标题、通知设置页） | ✅ | ✅ |
| 设置 / 手机管家里的应用列表 | ✅ | ✅ |

不会改包名、不改 APK、不影响签名，微信、银行类这种会校验签名的应用也能用。关掉模块就恢复原样。

- **分身 / 多开独立设置**：应用分身（用户 900、XSpace 等）里的副本单独列一项，改原应用不会连带改分身，反过来也一样。
- **按通知渠道折叠进「更多通知」**（澎湃）：选中某个应用里指定的通知渠道（比如常驻的「后台连接」），该渠道的通知会立刻折叠进「更多通知」，不用等 6 小时；同一个应用的其他渠道（新消息等）不受影响，照常提醒。需要 Root 来读取应用有哪些渠道。
- **伪装国家码**：给某个应用填两位国家码（如 `us`），它自己进程里 `TelephonyManager.getSimCountryIso / getNetworkCountryIso` 就返回这个值，适合按 SIM / 网络地区限制功能的应用（比如三星健康监测器）。CSC 国家属性 `ro.csc.countryiso_code` 也一并伪装。系统和其它应用不受影响。需要在 LSPosed 里把该应用加进 Facet 的作用域，并强行停止它后重新打开。
- **澎湃 OS 4 原生桌面**：新桌面由 `hyos_spawner` 拉起、没有 Java 运行时，LSPosed 进不去。名称改为在系统框架里改 `LauncherApps` 和（仅对桌面的）`getApplicationInfo` 返回值，作用域要勾「系统框架」并重启；图标写进主题图标包 `/data/system/theme/icons`（需要 Root，Facet 首页按钮），换主题后系统框架里的监听会通知 Facet 自动重写。
- **伪装厂商**：给某个应用填厂商名（如 `samsung`），它自己进程里的 `Build.MANUFACTURER / BRAND` 就是这个值，适合只认三星机的应用。同样需要加进作用域并强行停止后生效。
- **没有桌面图标的系统通知**：列表里额外提供「Android 系统」（LSPosed / Vector 的「已加载」常驻通知就是以它的名义发的，渠道 `lsposed_status` / `vector_status`）和「Shell」两项，可以像普通应用一样把它们的通知渠道折叠进「更多通知」。

## 使用

**需要**：已 Root，装好 LSPosed 或 [Vector](https://github.com/JingMatrix/Vector)。实测环境：
- Xiaomi 17 Pro Max · HyperOS 3.0 · Android 16 · KernelSU Next + Vector 2.2（桌面、最近任务、通知、设置全支持）
- Motorola XT2601-1 · Android 16 · KernelSU Next + Vector 2.2（原生 Launcher3：桌面、最近任务、应用名/图标；作用域勾选 `com.motorola.launcher3`）

1. 从 [Releases](../../releases/latest) 下载 `Facet-vX.Y.apk` 安装。
2. 在 LSPosed / Vector 里启用模块，作用域勾选：系统桌面、系统界面、设置、手机管家、通知管理、Facet 自己。重启一次手机。
3. 给 Facet 授予 Root 权限（只用于「重启桌面 / 重启系统界面」两个按钮）。
4. 打开 Facet → 点应用 → 填新名称、选一张图片作为图标 → 保存 → 点「重启桌面」「重启系统界面」。新到的通知不用重启。

> 图标建议用方形满版的图片，勾选「套用当前主题的图标形状」后会自动裁成当前主题的形状。

## 更新

应用内点「检查更新」，会从本仓库的 Releases 下载新版并交给系统安装；每 12 小时也会静默检查一次，有新版才会提示。安装包有 SHA-256 校验（GitHub 提供时）。

## 原理

配置通过 `xposedsharedprefs` 共享给各个作用域进程，Hook 点：

| 位置 | Hook |
|---|---|
| 所有作用域进程 | `ApplicationPackageManager.getApplicationLabel / loadItemIcon / loadUnbadgedItemIcon`、`PackageItemInfo.loadLabel`、`LauncherActivityInfo.getIcon` |
| 桌面 `com.miui.home` | `IconProvider.getActivityIcon`；最近任务 `IconLoader`、`RecentsTaskLoader.getAndUpdateActivityTitle`、`RecentsModel.onTaskDescriptionChanged` |
| 原生 Launcher3（moto、Pixel 等） | `IconProvider.getIcon`、`LauncherActivityCachingLogic.getLabel`；`IconProvider.getSystemStateForPackage` 里附加配置摘要，让启动器的图标缓存在设置变化后自动失效 |
| 系统界面 | `ExpandedNotification.getAppName`、`AppIconsManager.getAppIconInner / loadAppIcon / getAppIconBitmap` |

图标形状交给 `miui.content.res.IconCustomizer.generateIconStyleDrawable` 处理。澎湃是闭源系统，这些类名来自 HyperOS 3.0.319 的反编译结果，系统大版本更新后可能需要调整。

## 不会改的地方

- 状态栏左上角的单色小图标（应用自己提供的）
- 应用自己界面里显示的名字
- 作用域以外的进程（比如分享面板），需要的话在 LSPosed 里把对应系统应用加进作用域

## 自己构建

不需要 Gradle，只要 JDK 17、Android build-tools 36 和 android-36 的 `android.jar`：

```bash
T=/path/to/tools bash build.sh      # T 下需要 jdk/  bt/  plat/ 三个目录
```

产物在 `dist/Facet.apk`。签名文件默认放在 `~/.facet/`，首次构建自动生成。`VERSION_NAME` / `VERSION_CODE` 环境变量可覆盖版本号。重新生成图标：`python tools/make_icon.py`（需要 Pillow）。

## 许可

[MIT](LICENSE)
