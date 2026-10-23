# DiPlay — 面向 iOS 15 及更低版本的旧版有线 CarPlay

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

本仓库基于 **DiPlay 0.2.21**，新增一条实验性的 **旧版 USB 有线 CarPlay** 路径，面向停留在
iOS 15 的 iPhone（目标设备：iPhone 7，iOS 15.8.3）。标准有线（iOS 16+）与无线路径来自上游，
本工作未改动。APK 安装在**车机**上，不是 iPhone。

> **状态：** 旧版路径的软件侧已实现，CI 构建通过；**尚未在车机上完成端到端验证**。USB 角色切换与
> gadget 依赖车机固件，可能中断 ADB 或其他 USB 设备。请把这里的每个构建都当作测试包。

## 旧版路径做了什么

- 发送 Apple 厂商请求 `0x51`，让 iPhone 离开 host 总线，然后把车机 USB 口从 host 切为 device。
- 呈现 FunctionFS iAP2 + CDC-NCM 复合 gadget，并在该链路上完成 MFi 认证。
- 以 `IAP2-USBHost`（interface 1）完成 identification，不声明标准有线 profile；此路径从不发送
  或回应 `0x52`、`0x4300`、`0x4301`。
- 通过 USB 网络（`169.254.1.1` / `fe80::1`）承载 AirPlay 与 mDNS。

root helper 是 PIE 可执行文件，按 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 打包，安装时解压到
`nativeLibraryDir`。CI 用 `scripts/verify_legacy_gadget_apk.py` 校验它确实在 APK 内。

## 下载

[Releases](https://github.com/1272763740-coder/DiPlay-ios15-legacy/releases) ·
[Issues](https://github.com/1272763740-coder/DiPlay-ios15-legacy/issues)

当前版本为 `v0.2.21-legacy.1`。附件 APK 是**仅源码构建的调试包**：

- 包名 `com.shihab.diplay.hudtest`，版本 `0.2.21-hud-test`，调试签名；可与常规
  `com.shihab.diplay` 共存安装。
- **不含 MFi 身份**。`assets/offline-mfi/identity.pk8` 与 `certificate.p7b` 不在本源码树中，也
  不会进入 CI 构建。
- 因此 CarPlay 认证需要能在 USB 角色翻转后继续工作的 MFi 后端：车机内部 I2C MFi、Remote MFi，
  或挂在另一个 USB 控制器上的 CH341。`LOCAL` 离线后端只有在自行注入身份资产的构建中才可用
  （见[构建](#构建)）。
- 没有可用的 MFi 后端时，CarPlay 界面可以打开，但认证无法完成。

## 运行要求

- 已 root 的 BYD 兼容 Android 车机（Android 7.0+），应用可调用 `su`。
- configfs 支持 FunctionFS 与 `usb_f_ncm`（内核内置或可通过 `modprobe` 加载）、可写的 UDC，以及
  可写的、对应 iPhone 所用端口的 USB 角色切换。
- 上述任一可用的 MFi 后端。
- 旧版路径仅用于 iOS 15 及更低版本；iOS 16 及更高版本请使用标准有线路径。

root、UDC 与角色切换的支持情况因固件而异。请用 **设置 → 高级 → 旧版有线 CarPlay（实验性）→
检查兼容性**，它会报告第一个缺失条件，而不是静默失败。

## 使用

1. 在车机上安装 APK 并打开。
2. 进入 **设置 → 高级 → 旧版有线 CarPlay（实验性）**，点 **检查兼容性**。
3. 选择 **旧版（iOS 15 及更低版本）**，确认警告，让它在下次连接时生效。
4. 用 USB 连接 iPhone，观察诊断日志里的 `legacy ...` 行。
5. 需要恢复时，改回标准模式并重新连接。iOS 16+ 必须使用标准模式。

旧版模式会中断正在运行的 ADB 连接，并干扰同一控制器上的其他 USB gadget。不要对仍需 ADB 的端口
启用它。

## 构建

CI（`.github/workflows/android.yml`）构建 debug APK、校验 helper 载荷，然后运行单元测试与 lint。
本地构建需要 JDK 25、Android SDK 37 与 NDK 28.2.13676358：

```sh
./gradlew :mobile:assembleDebug                 # 仅源码，无 MFi 身份
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug
```

要构建带本地离线 MFi 的独立车机测试包，请提供自己的身份资产并选用 standalone 变体：

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/assets ./gradlew :mobile:assembleStandaloneDebug
```

`/path/to/assets` 下必须有 `offline-mfi/identity.pk8` 与 `offline-mfi/certificate.p7b`。不要提交
这些文件；构建会拒绝 APK assets 中出现未声明的凭据。

## 验证状态

- CI 通过：assemble、helper 校验、单元测试、lint 与测试报告。
- APK 会校验 4 个 ABI 的 helper、`extractNativeLibs=true` 以及 dex 中的 legacy 标记。Release
  说明记录了确切的构建指纹与 SHA-256。
- 仍需真机验证：`0x51` 是否被接受、iPhone 是否离开 host 总线、角色切换、MFi、NCM 起链、mDNS
  发现与 AirPlay 首帧。

## 限制

- 非 Apple 认证产品，与 Apple、BYD 均无关联。仅支持 BYD 品牌。
- 旧版路径只对 iOS 15 及更低版本有意义，不能作为 iOS 16+ 的回退方案。
- 角色切换与 gadget 创建依赖固件，可能失败，或让 USB 停留在被改变的状态。
- helper 以 root 运行并写 configfs。安装前请阅读源码。

## 来源与许可

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 0.2.21（GPL-3.0），后者基于
[xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0），其主界面/设置界面适配
[DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0）。许可声明保留在 `docs/licenses`
与 `LICENSE` 中；分发修改版时请一并保留。为兼容性保留了应用 ID `com.shihab.diplay` 与上游名称。
