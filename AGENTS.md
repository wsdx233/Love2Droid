# Love2Droid 维护约束

## 项目定位

Love2Droid 是单 `app` 模块 Android 应用，包名为 `top.wsdx233.love2droid`。应用把 LÖVE/SDL native runtime、项目文件管理和 Sora Editor 集成在同一个 APK 中。最低 API 23，Java/Kotlin 目标版本为 17。

## 目录结构

- `app/src/normal/`：产品编辑器界面和业务逻辑。`java/top/wsdx233/love2droid/` 包含编辑器、项目仓库、文件浏览器、打包与校验代码；`res/` 包含编辑器和项目管理界面资源。
- `app/src/main/java/top/wsdx233/love2droid/runtime/`：产品代码到 LÖVE Activity 的最小启动边界。
- `app/src/main/java/org/love2d/android/`：上游 LÖVE Android/SDL Java 桥接代码。
- `app/src/main/cpp/`：LÖVE、SDL3、megasource 和 CMake native 构建。`love/` 与 `megasource/` 视为上游代码。
- `app/src/main/assets/textmate/`：Sora TextMate 主题、语言配置和 grammar；`languages.json` 是集中注册入口。
- `app/src/test/`：不依赖 Android runtime 的逻辑单元测试。
- `doc/README.md`：文档导航和分类说明。
- `doc/architecture.md`：当前架构、模块边界和关键技术约束。
- `doc/design.md`：界面布局、交互和编辑器行为约定。
- `doc/reference.md`：外部参考项目、来源和使用边界。
- `doc/verification.md`：逻辑测试、构建和真机验证要求。
- `doc/status.md`：当前实现状态、已知限制和已确认决策。
- `doc/plan/`：只记录尚未实现且准备实现的事项，每个主题使用独立 Markdown 文件。
- `licenses/` 与 `license.txt`：第三方及 LÖVE 许可证。
- `ref/`：本地参考仓库，不属于产品源码，不提交 Git。

## 文档规范

- 文档统一使用 Markdown，放在 `doc/` 下；每篇文档只负责一个稳定主题，禁止继续把架构、交互、参考资料和实现状态堆到同一个计划文件。
- `doc/architecture.md` 记录已经采用的系统结构、模块职责、数据流和不应随意改变的边界；新的架构决策在这里更新。
- `doc/design.md` 记录界面布局、交互行为和用户可见约定；实现细节只在确实影响交互时引用源码，不复制大段代码。
- `doc/reference.md` 记录参考项目、官方资料、版本或提交、许可证和允许借鉴的范围；参考仓库本身仍放在 `ref/`，不复制 Demo 或无关资源。
- `doc/verification.md` 记录可重复的逻辑测试、APK 构建和必须由真机完成的验证；验证步骤应对应可观察行为。
- `doc/status.md` 记录当前已实现能力、已知限制和已确认的非计划事项；完成或取消计划后同步更新，不把已完成内容伪装成待办。
- `doc/plan/` 中的文件只记录“尚未实现且已确认要实现”的内容；按主题拆分，并明确状态、范围、行为要求和验收标准。实现完成或计划取消后必须从 `plan/` 移除，同时同步 `status.md` 及受影响的稳定主题文档；不得在 `plan/` 保留已完成事项、历史实现说明、架构约束、参考资料或当前状态。
- 文档之间通过相对 Markdown 链接互相引用；文件改名或移动时必须同步搜索并更新引用，入口导航维护在 `doc/README.md`。
- 文档使用中文为主，代码符号、路径、命令、版本号和外部名称保持原文；新增可本地化的产品 UI 文案仍必须进入字符串资源，不能只写死在业务代码中。
- 文档变更不要求 APK 构建；若同一提交还修改源码、资源、Manifest、Gradle、native/CMake 或 TextMate 资源，仍必须遵守下方 APK 构建要求。

## 源码边界

- 产品功能优先修改 `app/src/normal/`；不要把产品逻辑散落到上游 runtime 目录。
- 除上游同步或确有 native 修复需要，不修改 `app/src/main/cpp/love/`、`app/src/main/cpp/megasource/` 和 `app/src/main/java/org/love2d/android/`。
- 修改上游边界时保持补丁最小，并确认 `normalNoRecord` 变体仍能链接全部 ABI。
- 项目文件始终位于 `getExternalFilesDir(null)/projects`。所有用户输入路径必须 canonicalize，并使用 `StorageUtils.isWithin` 验证不能逃逸项目根目录。
- 文件保存继续使用 `StorageUtils.writeTextAtomic`；不要直接覆盖源文件。
- Play 前必须保存可落盘标签、拒绝未保存的未命名文档、校验 `main.lua`，再由 `LovePackageBuilder` 生成 `.love` 快照。
- Sora 语言扩展名映射只维护在 `LanguageResolver`；TextMate grammar 只通过 `assets/textmate/languages.json` 注册。缺失 grammar 必须退化到纯文本并给出可理解错误，不能阻塞打开文件。
- UI 继续使用 Material 矢量图标和字符串资源；不要引入 `android.R.drawable` 旧图标，也不要在业务代码中新增可本地化的硬编码界面文本。新增或替换图标必须通过 `npx --yes --package=@expo/material-symbols add-material-symbols` 获取，默认使用 Rounded、未填充、weight 400；命令与清理要求见 [`doc/reference.md`](doc/reference.md#material-symbols)。
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

逻辑行为变化还应运行对应的聚焦测试。不得使用 Android 模拟器进行测试；涉及界面、输入法、文件系统或 LÖVE runtime 的实际交互由用户在真机验证，本地只执行构建和不依赖 Android runtime 的测试。不要用跳过任务、关闭检查或吞掉异常的方式换取构建成功。
