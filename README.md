# HyperFreeform

一个基于 LSPosed 的 Android 原生自由窗口模块。模块直接管理
`WINDOWING_MODE_FREEFORM`、Task 与 Surface，不使用 VirtualDisplay。

> [!WARNING]
> 本项目会 Hook `system_server`、SystemUI 和 Launcher 的内部实现，具有明显的系统版本与
> ROM 兼容性风险。刷入、安装或启用前请确保能够进入安全模式并恢复模块状态。

## 功能

- 普通小窗、迷你挂起态与贴边 Pin 状态
- 侧边栏选择应用并以小窗打开
- 通知下滑和前台上划进入小窗
- 拖动、等比缩放、关闭、全屏与贴边手势
- 自定义小窗 DPI、宽度和高度，并同步到已打开窗口
- 原生 Task 保留与多窗口避让
- 与内容裁剪一致的圆角、描边和阴影

## 环境要求

- Android 13 或更高版本
- LSPosed（模块最小 API 版本 93）
- 支持自由窗口的系统，或能够由模块开启相应系统能力的 ROM
- 编译环境：JDK 17、Android SDK 37

本项目主要面向 AOSP/HyperOS 风格的系统组件实现。不同 ROM 对 WindowManager、Launcher
和 SystemUI 的修改差异很大，不保证在所有设备上可用。

## 构建

1. 安装 JDK 17 和 Android SDK，并在未跟踪的 `local.properties` 中配置 SDK：

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

2. 构建调试 APK：

   ```bash
   ./gradlew :app:assembleDebug
   ```

   输出位于 `app/build/outputs/apk/debug/`。调试包使用 Android 默认调试签名。

3. 如需签名发布包，复制示例配置并在本地填写真实信息：

   ```bash
   cp signing.properties.example signing.properties
   ./gradlew :app:assembleRelease
   ```

   `signing.properties`、密钥文件及构建产物均已被 `.gitignore` 排除。未配置签名信息时，
   Release 构建不会包含私有签名。

## 启用

1. 安装 APK，并在 LSPosed 中启用模块。
2. 按模块建议选择作用域，随后重启设备。
3. 打开“自由窗口”应用，确认模块与 system_server 服务状态正常。
4. 在设置页按需配置侧边栏、通知手势、前台手势、DPI 与窗口大小。

## 源码结构

- `app/src/main/java/io/hyper/freeform/xposed/`：系统 Hook、窗口服务、策略与 Shell 覆盖层
- `app/src/main/java/io/hyper/freeform/ui/`：Compose 配置界面
- `app/src/main/aidl/`：应用与 system_server 间的接口
- `app/src/main/res/`：应用资源

## 安全说明

仓库不包含发布密钥、签名口令、设备序列号、本机绝对路径、测试截图或日志。请勿在 Issue、
提交记录或构建日志中上传自己的 `local.properties`、`signing.properties` 或密钥文件。
