# DiPlay — 適用於 iOS 15 及更舊版本的舊版有線 CarPlay

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

本儲存庫以 **DiPlay 0.2.21** 為基礎，新增一條實驗性的 **舊版 USB 有線 CarPlay** 路徑，適用於停留在
iOS 15 的 iPhone（目標裝置：iPhone 7，iOS 15.8.3）。標準有線（iOS 16+）與無線路徑來自上游，本次
工作未予變更。APK 安裝在**車機**上，不是 iPhone。

> **狀態：** 舊版路徑的軟體端已實作，CI 建置通過；**尚未在車機上完成端對端驗證**。USB 角色切換與
> gadget 取決於車機韌體，可能中斷 ADB 或其他 USB 裝置。請把這裡的每個建置都視為測試包。

## 舊版路徑做了什麼

- 送出 Apple 廠商請求 `0x51`，讓 iPhone 離開 host 匯流排，再把車機 USB 埠從 host 切為 device。
- 呈現 FunctionFS iAP2 + CDC-NCM 複合 gadget，並在該連結上完成 MFi 認證。
- 以 `IAP2-USBHost`（interface 1）完成 identification，不宣告標準有線 profile；此路徑從不送出
  或回應 `0x52`、`0x4300`、`0x4301`。
- 透過 USB 網路（`169.254.1.1` / `fe80::1`）承載 AirPlay 與 mDNS。

root helper 是 PIE 執行檔，依 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 封裝，安裝時解壓到
`nativeLibraryDir`。CI 以 `scripts/verify_legacy_gadget_apk.py` 驗證它確實在 APK 內。

## 下載

[Releases](https://github.com/1272763740-coder/DiPlay-ios15-legacy/releases) ·
[Issues](https://github.com/1272763740-coder/DiPlay-ios15-legacy/issues)

目前版本為 `v0.2.21-legacy.1`。附件 APK 是**僅原始碼建置的偵錯包**：

- 套件名稱 `com.shihab.diplay.hudtest`，版本 `0.2.21-hud-test`，偵錯簽章；可與一般
  `com.shihab.diplay` 並存安裝。
- **不含 MFi 身分**。`assets/offline-mfi/identity.pk8` 與 `certificate.p7b` 不在本原始碼樹中，也
  不會進入 CI 建置。
- 因此 CarPlay 認證需要能在 USB 角色翻轉後繼續運作的 MFi 後端：車機內部 I2C MFi、Remote MFi，
  或掛在另一個 USB 控制器上的 CH341。`LOCAL` 離線後端只有在自行注入身分資產的建置中才可用
  （見[建置](#建置)）。
- 沒有可用的 MFi 後端時，CarPlay 畫面可以開啟，但認證無法完成。

## 需求

- 已 root 的 BYD 相容 Android 車機（Android 7.0+），應用程式可呼叫 `su`。
- configfs 支援 FunctionFS 與 `usb_f_ncm`（核心內建或可透過 `modprobe` 載入）、可寫入的 UDC，以及
  可寫入、對應 iPhone 所用連接埠的 USB 角色切換。
- 任一上述可用的 MFi 後端。
- 舊版路徑僅適用於 iOS 15 及更舊版本；iOS 16 及更新版本請使用標準有線路徑。

root、UDC 與角色切換的支援情形因韌體而異。請用 **設定 → 進階 → 舊版有線 CarPlay（實驗性）→
檢查相容性**，它會回報第一個缺少的條件，而不是無聲失敗。

## 使用

1. 在車機上安裝 APK 並開啟。
2. 進入 **設定 → 進階 → 舊版有線 CarPlay（實驗性）**，點 **檢查相容性**。
3. 選擇 **舊版（iOS 15 及更舊版本）**，確認警告，讓它在下次連線時生效。
4. 以 USB 連接 iPhone，觀察診斷日誌裡的 `legacy ...` 行。
5. 需要還原時，改回標準模式並重新連線。iOS 16+ 必須使用標準模式。

舊版模式會中斷執行中的 ADB 連線，並干擾同一控制器上的其他 USB gadget。不要對仍需 ADB 的連接埠
啟用它。

## 建置

CI（`.github/workflows/android.yml`）建置 debug APK、驗證 helper 承載內容，然後執行單元測試與
lint。本機建置需要 JDK 25、Android SDK 37 與 NDK 28.2.13676358：

```sh
./gradlew :mobile:assembleDebug                 # 僅原始碼，無 MFi 身分
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug
```

要建置含本機離線 MFi 的獨立車機測試包，請提供自己的身分資產並選用 standalone 變體：

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/assets ./gradlew :mobile:assembleStandaloneDebug
```

`/path/to/assets` 下必須有 `offline-mfi/identity.pk8` 與 `offline-mfi/certificate.p7b`。不要提交
這些檔案；建置會拒絕 APK assets 中出現未宣告的憑證。

## 驗證狀態

- CI 通過：assemble、helper 驗證、單元測試、lint 與測試報告。
- APK 會驗證 4 個 ABI 的 helper、`extractNativeLibs=true` 以及 dex 中的 legacy 標記。Release
  說明記錄了確切的建置指紋與 SHA-256。
- 仍需實機驗證：`0x51` 是否被接受、iPhone 是否離開 host 匯流排、角色切換、MFi、NCM 起鏈、mDNS
  探索與 AirPlay 首幀。

## 限制

- 非 Apple 認證產品，與 Apple、BYD 均無關聯。僅支援 BYD 品牌。
- 舊版路徑只對 iOS 15 及更舊版本有意義，不能作為 iOS 16+ 的備援方案。
- 角色切換與 gadget 建立取決於韌體，可能失敗，或讓 USB 停留在被改變的狀態。
- helper 以 root 執行並寫入 configfs。安裝前請閱讀原始碼。

## 來源與授權

以 [DiPlay](https://github.com/shihabal3amri/DiPlay) 0.2.21（GPL-3.0）為基礎，後者基於
[xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0），其主畫面/設定畫面改編自
[DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0）。授權聲明保留在 `docs/licenses`
與 `LICENSE` 中；散布修改版時請一併保留。為相容性保留了應用程式 ID `com.shihab.diplay` 與上游
名稱。
