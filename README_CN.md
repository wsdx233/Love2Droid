<div align="right">
  <strong><a href="README.md">English</a> · 简体中文</strong>
</div>

<p align="center">
  <img src="doc/images/icon.svg" width="128" alt="Love2Droid — Android 机器人抱着 LÖVE 标志">
</p>

<h1 align="center">Love2Droid</h1>

<p align="center">
  <strong>把下一个 LÖVE 游戏，装进口袋里的开发工作台。</strong><br>
  写 Lua、运行游戏、导出 APK — 在 Android™ 上完成。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-6.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 6.0 及以上">
  <img src="https://img.shields.io/badge/L%C3%96VE-12.0-E74A99" alt="内置 LÖVE 12.0 runtime">
  <img src="https://img.shields.io/badge/Editor-Sora-27AAE1" alt="Sora Editor">
  <a href="license.txt"><img src="https://img.shields.io/badge/Licenses-Third--party_notices-64748B" alt="许可证声明"></a>
</p>

<p align="center">
  <a href="https://github.com/wsdx233/Love2Droid/releases">下载</a> ·
  <a href="#开始使用">开始使用</a> ·
  <a href="#从源码构建">源码构建</a> ·
  <a href="doc/README.md">文档</a> ·
  <a href="https://github.com/wsdx233/Love2Droid/issues">反馈问题</a>
</p>

---

## 界面预览

<p align="center">
  <img src="doc/images/screenshots/editor.jpg" width="32%" alt="Lua 编辑器与 LÖVE API 悬浮文档">
  <img src="doc/images/screenshots/assistant.jpg" width="32%" alt="编辑器标签中内嵌的 DeepSeek Harness 助手">
  <img src="doc/images/screenshots/game.jpg" width="32%" alt="在 Android 手机上运行的 LÖVE 游戏">
</p>
<p align="center"><sub>Lua 编辑与 API 提示 · 内嵌 AI 工作区 · 真机运行</sub></p>

<details>
<summary><strong>更多截图：终端与调试</strong></summary>
<br>
<p align="center">
  <img src="doc/images/screenshots/terminal.jpg" width="48%" alt="集成终端标签中运行的 Oh My Pi">
  <img src="doc/images/screenshots/debugger.jpg" width="48%" alt="游戏内调试控制台执行 Lua 表达式">
</p>
<p align="center"><sub>终端与 Oh My Pi · 游戏内 Lua 控制台</sub></p>
</details>

## 关于项目

Love2Droid 把 LÖVE/SDL runtime、项目管理器、基于 Sora 的编辑器与可选 Linux 开发工具放进同一个 Android 应用。围绕一条简单的工作流：**创建项目 → 编写 Lua → 点击 Play → 导出游戏**。编辑器与游戏在独立进程中运行。

这是独立的社区项目，不是 LÖVE 或 Google 官方产品。目前仍在持续开发，设备差异与已知限制见[当前实现状态](doc/status.md)。

## 可以做什么

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>有上下文的代码编辑</h3>
      <p>Sora 多标签编辑、TextMate 语法高亮、亮暗主题、项目搜索与会话恢复。可选安装 LuaLS，获得补全、悬浮文档、转到定义与查找用法。</p>
    </td>
    <td width="50%" valign="top">
      <h3>边运行，边调试</h3>
      <p>Play 保存项目文件并生成 <code>.love</code> 快照，交给内置 runtime。通过游戏内调试面板查看日志、执行 Lua、监视表达式并使用断点。</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>从项目管理到游戏发布</h3>
      <p>从空项目或基础模板开始，管理项目分组与文件，导入 <code>.love</code>/<code>.zip</code>，导出 <code>.love</code> 或签名游戏 APK，并设置自己的名称、图标与包名。</p>
    </td>
    <td width="50%" valign="top">
      <h3>把常用工具带在身边</h3>
      <p>在 arm64 设备上通过 PRoot 安装 Ubuntu，按需添加 Git、LuaLS、Oh My Pi、DeepSeek Harness 或 LÖVE 无界面检查。终端与 DSH Web 工作区都可以在编辑器标签中使用。</p>
    </td>
  </tr>
</table>

## 开始使用

1. **安装 Love2Droid。** 在 [Releases](https://github.com/wsdx233/Love2Droid/releases) 查看已发布的 APK；如果尚无安装包，可以[自行构建](#从源码构建)。
2. **安装工具环境。** 普通 APK 按需联网下载组件；ARM64 离线 APK 无需下载，直接恢复包含 OMP、DSH 和 `love-check` 的完整环境。首次向导可以跳过，之后通过**设置 → 环境与扩展组件**重新进入。
3. **新建或导入项目。** 空项目提供最小 Hello World，基础项目附带支持中文的像素字体。导入已有 `.love` 或 `.zip` 时，归档根目录必须包含 `main.lua`。
4. **编辑，然后点击 Play。** 先把未命名文档保存为项目文件。Play 会保存可落盘标签、校验 `main.lua` 并运行最新快照。
5. **准备好后导出。** 分享 `.love` 归档，或先在项目属性中设置应用名、包名、版本、方向、权限与图标，再构建游戏 APK。

离线版内置的是开发工具，不包含 AI 模型权重；远程模型、额外插件和远程 Git 操作仍需联网。升级不会用镜像替换已有环境。打包与签名方法见[离线构建和验证流程](doc/verification.md#离线完整环境发布)。

> **记得备份。** 项目位于应用专属外部文件目录 `Android/data/top.wsdx233.love2droid/files/projects` 下。卸载应用可能删除这些文件；卸载或清除应用数据前，请先导出重要项目。

## 运行要求与边界

| 项目 | 当前支持 |
| --- | --- |
| Android | Android 6.0 / API 23 及以上 |
| APK 包含的 native ABI | `armeabi-v7a`、`arm64-v8a`、`x86_64` |
| PRoot 与 Linux 工具 | 仅 `arm64-v8a`，按需安装 |
| Lua 语言服务 | 可选 LuaLS；Android 6.0/7.x 保留 TextMate 编辑，不加载 Sora LSP |
| 游戏 runtime | 应用内置 Android LÖVE 12.0 |
| 可选无界面检查 | Linux LÖVE 11.5 + Xvfb/Mesa，不能替代 Android 真机运行 |
| AI 助手 | 可选工具；模型凭据与联网要求取决于所用服务及配置 |

只运行可信项目、终端命令和 AI 工具。游戏与开发工具可以使用应用拥有的文件访问权限；PRoot 与 `love-check` **不是安全沙箱**。不要分享 API Key，也不要把密钥放进导出项目。下方标准构建生成的是 **debug APK**，不是用于正式发布的生产签名包。

## 从源码构建

使用 Linux 构建环境，准备 **JDK 17+**、**Android SDK Platform 37**、**NDK 27.3.13750724** 与 **CMake 3.21 或更新版本**。Gradle 使用仓库内的 wrapper；native 源码已包含在仓库中。

```sh
git clone https://github.com/wsdx233/Love2Droid.git
cd Love2Droid

# 替换为本机 Android SDK 所在目录。
export ANDROID_HOME="$HOME/Android/Sdk"
CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew :app:assembleNormalNoRecordDebug
```

APK 输出：

```text
app/build/outputs/apk/normalNoRecord/debug/app-normal-noRecord-debug.apk
```

聚焦逻辑测试、构建约束和真机检查见[验证要求](doc/verification.md)。Android 界面、输入法、PRoot 与游戏 runtime 的实际交互必须在真机验证；本地构建成功不等同于设备兼容性已验证。

## 更多文档

详细开发文档目前以中文维护。

- [文档导航](doc/README.md)
- [架构与模块边界](doc/architecture.md)
- [界面与编辑器行为](doc/design.md)
- [构建与验证](doc/verification.md)
- [实现状态与已知限制](doc/status.md)
- [外部参考与素材来源](doc/reference.md)

欢迎通过 [Issues](https://github.com/wsdx233/Love2Droid/issues) 反馈问题。请附应用版本、Android 版本、设备与 ABI、复现步骤，以及尽可能小的示例项目或脱敏日志；不要上传凭据或私人项目文件。

## 致谢与许可证

Love2Droid 的实现离不开开源社区：

- **[LÖVE](https://love2d.org/) / [LÖVE Android](https://github.com/love2d/love-android) / [SDL](https://libsdl.org/)** — 游戏 runtime 与 Android 集成。
- **[Sora Editor](https://github.com/Rosemoe/sora-editor) / [Lua Language Server](https://github.com/LuaLS/lua-language-server)** — 代码编辑与 Lua 语言服务。
- **[Termux](https://github.com/termux/termux-app) / [PRoot](https://github.com/termux/proot) / [r2droid](https://github.com/wsdx233/r2droid)** — 终端组件与 Linux 环境集成参考。
- **[Oh My Pi](https://github.com/can1357/oh-my-pi) / [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)** — 可选 AI 开发工具。
- **[Fusion Pixel Font](https://github.com/TakWolf/fusion-pixel-font)** — 基础项目模板内置的像素字体。
- **[Material Symbols](https://fonts.google.com/icons) / [Devicon](https://github.com/devicons/devicon)** — 界面图标与新应用图标中的 LÖVE 矢量素材。
- **[Sts2MobileLauncher](https://github.com/ModinMobileSTS/Sts2MobileLauncher)** — README 排版与表达方式参考；未复用其启动器代码或游戏资产。

仓库包含采用不同许可证的组件，请分别查看 [license.txt](license.txt)、[licenses/](licenses/) 与[参考说明](doc/reference.md)。上游许可证声明不代表整个项目统一采用 MIT 许可证。

应用图标将 Android 机器人改编为拥抱 LÖVE 标志的形象。按 Android 品牌要求保留以下署名：**The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).** Android 是 Google LLC 的商标。LÖVE 及其他项目名称和标志归各自权利人所有；使用这些素材不表示获得官方背书。
