# Love2Droid 维护约束

## 项目定位

Love2Droid 是单 `app` 模块 Android 应用，包名为 `top.wsdx233.love2droid`。应用把 LÖVE/SDL native runtime、项目文件管理和 Sora Editor 集成在同一个 APK 中。最低 API 23，Java/Kotlin 目标版本为 17。

## 目录结构

- `app/src/normal/`：产品编辑器界面和业务逻辑。`java/top/wsdx233/love2droid/` 包含编辑器、项目仓库、文件树、打包与校验代码；`res/` 包含编辑器和项目管理界面资源。
- `app/src/main/java/top/wsdx233/love2droid/runtime/`：产品代码到 LÖVE Activity 的最小启动边界。
- `app/src/main/java/org/love2d/android/`：上游 LÖVE Android/SDL Java 桥接代码。
- `app/src/main/cpp/`：LÖVE、SDL3、megasource 和 CMake native 构建。`love/` 与 `megasource/` 视为上游代码。
- `app/src/main/assets/textmate/`：Sora TextMate 主题、语言配置和 grammar；`languages.json` 是集中注册入口。
- `app/src/test/`：不依赖 Android runtime 的逻辑单元测试。
- `doc/plan.md`：架构、交互和实现状态。
- `licenses/` 与 `license.txt`：第三方及 LÖVE 许可证。
- `ref/`：本地参考仓库，不属于产品源码，不提交 Git。

## 源码边界

- 产品功能优先修改 `app/src/normal/`；不要把产品逻辑散落到上游 runtime 目录。
- 除上游同步或确有 native 修复需要，不修改 `app/src/main/cpp/love/`、`app/src/main/cpp/megasource/` 和 `app/src/main/java/org/love2d/android/`。
- 修改上游边界时保持补丁最小，并确认 `normalNoRecord` 变体仍能链接全部 ABI。
- 项目文件始终位于 `getExternalFilesDir(null)/projects`。所有用户输入路径必须 canonicalize，并使用 `StorageUtils.isWithin` 验证不能逃逸项目根目录。
- 文件保存继续使用 `StorageUtils.writeTextAtomic`；不要直接覆盖源文件。
- Play 前必须保存可落盘标签、拒绝未保存的未命名文档、校验 `main.lua`，再由 `LovePackageBuilder` 生成 `.love` 快照。
- Sora 语言扩展名映射只维护在 `LanguageResolver`；TextMate grammar 只通过 `assets/textmate/languages.json` 注册。缺失 grammar 必须退化到纯文本并给出可理解错误，不能阻塞打开文件。
- UI 继续使用 Material 矢量图标和字符串资源；不要引入 `android.R.drawable` 旧图标，也不要在业务代码中新增可本地化的硬编码界面文本。
- Drawer 文件操作、多选、标签切换和保存不得在主线程执行递归目录 I/O 或大型文件读取。
- 不提交构建产物、Gradle/IDE 缓存、native 中间文件、heap dump、签名文件或 `ref/` 参考仓库。

## 构建要求

**每次修改任何源码、资源、Manifest、Gradle 配置、native/CMake 文件或 TextMate 资源后，都必须重新构建 APK；未成功生成 APK 不得宣告改动完成。**

标准构建命令：

```sh
CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew :app:assembleNormalNoRecordDebug
```

APK 输出：

```text
app/build/outputs/apk/normalNoRecord/debug/app-normal-noRecord-debug.apk
```

逻辑行为变化还应运行对应的聚焦测试；涉及界面、输入法、文件系统或 LÖVE runtime 的变化应在 Android 设备或模拟器上验证实际路径。不要用跳过任务、关闭检查或吞掉异常的方式换取构建成功。
