# 系统架构

本文记录 Love2Droid 已采用的系统结构、模块职责、数据流和关键约束。界面交互见 [design.md](design.md)，当前能力见 [status.md](status.md)。

## 应用边界

Love2Droid 是单 `app` 模块 Android 应用，包名与 `applicationId` 为 `top.wsdx233.love2droid`：

- Kotlin 承载产品业务；必要的 LÖVE/SDL glue code 保留 Java/C++。
- UI 使用 Android View/XML、AppCompat 和 Material，不引入 Compose。Sora Editor 本身是 View，LÖVE Android 上游也采用传统 Activity/NDK 结构。
- `minSdk` 为 API 23，`compileSdk` 为 API 37，Java/Kotlin 目标版本为 17。
- 为允许 PRoot guest loader 从应用私有临时目录执行，`targetSdk` 保持 API 28；API 29+ 的 W^X 策略会拒绝该执行方式。
- 原生构建锁定 Android NDK、CMake、LÖVE 和 megasource 版本，不使用动态 `latest`。

产品代码优先位于 `app/src/normal/`。`app/src/main/java/org/love2d/android/`、`app/src/main/cpp/love/` 和 `app/src/main/cpp/megasource/` 保持上游结构，产品适配集中在最小 runtime 边界。

## 进程与运行时

编辑器与 LÖVE runtime 位于同一 APK，但职责和进程隔离：

- `LoveGameActivity` 保留上游 `GameActivity`、SDL 初始化和 native library 加载顺序，使用 `Window` 的 `adjustPan` 让系统把当前输入框平移到输入法上方，并挂载游戏调试悬浮层。悬浮层仅负责触控 UI、JNI 状态轮询和从当前 `.love` 快照异步读取暂停位置的 Lua 源码。
- 调试控制台通过 `LoveGameActivity` JNI 与 native 调试桥接通信。native 侧维护固定容量命令队列、日志环形队列和断点表；Lua 主线程轮询命令，在受保护调用中执行 REPL 和监视表达式。编辑器在 Play Intent 中传递项目断点，runtime 初始化 native 状态后一次性装载。
- 断点与单步依赖 Lua hook，命中后只挂起 Lua 执行并保持 Android UI 可通信；继续、单步和暂停由 JNI 状态请求驱动。项目断点以相对 Lua 路径和一基行号保存在 `.love2droid.json`，编辑器行号区域只负责切换和呈现，不引入额外 gutter。

## 项目存储

项目根目录始终通过 `getExternalFilesDir(null)/projects` 获取，规范路径为：

```text
/storage/emulated/0/Android/data/top.wsdx233.love2droid/files/projects/
```

不硬编码绝对路径，也不申请 `MANAGE_EXTERNAL_STORAGE`。项目仓库以文件系统为数据源，不额外引入数据库。

```text
projects/
└── my-game/
    ├── .love2droid.json   # 显示名、简介、创建时间、版本
    ├── .lovedroid         # 编辑器和终端会话
    ├── main.lua           # LÖVE 入口
    ├── conf.lua
    └── assets/
```

存储不变量：

- 目录名是稳定 project id；显示名和简介存放在元数据中。
- 用户输入的名称和路径不得包含 `..`、路径分隔符或控制字符。
- 所有用户输入路径先 canonicalize，再用 `StorageUtils.isWithin` 确认未逃逸项目根目录。
- 文本保存使用 `StorageUtils.writeTextAtomic`，不直接覆盖源文件。
- 递归文件 I/O 和大型文件读取不得在主线程执行。

## SAF 文件导入与导出

- 普通文件/目录导入导出根据用户选择的类型使用 `ACTION_OPEN_DOCUMENT`、`ACTION_OPEN_DOCUMENT_TREE` 或 `ACTION_CREATE_DOCUMENT`；项目管理页面额外使用 SAF 选择 `.love`/`.zip` 导入文件和 `.love` 导出目标。
- `DocumentsUiContracts` 优先将 SAF Intent 定向到 `com.google.android.documentsui` 或 `com.android.documentsui`，找不到标准 DocumentsUI 时才退回系统解析，规避国内定制文件选择器兼容问题。
- URI 内容流、递归复制和 ZIP 解压/压缩均在 Activity 的 `Dispatchers.IO` 协程中执行；归档导入拒绝绝对路径、`..`、重复项、ZIP Slip、过量条目和解压炸弹，并要求根目录存在 `main.lua`。

## Play 数据流

Play 使用不可变 `.love` 快照，不让 runtime 读取编辑器可能处于半写入状态的工作目录：

1. 保存所有可落盘的打开标签。
2. 拒绝尚未保存的未命名文档。
3. 校验项目根目录和 `main.lua`，阻止路径逃逸。
4. 由 `LovePackageBuilder` 将项目生成到 cache 中的临时 `.love` ZIP。
5. 通过 `FileProvider` 暴露 `content://` URI，并授予临时读取权限。
6. 启动 `LoveGameActivity`；游戏退出或下次启动时清理旧快照。

该数据流避免依赖 file URI 和广泛存储权限。若个别设备的 URI/SDL 组合不兼容，只允许在 runtime 边界增加受控 staging，不扩大整个应用的存储权限。

## Android 游戏发布数据流

Android 发布与 Play 分离；发布结果是可安装、可分享的独立 APK：

1. 项目元数据保存应用名、包名、版本、方向、权限和稳定签名身份；图标保存在应用私有 `project-icons/`，项目重命名和删除时同步处理。
2. Gradle 构建期间由 AAPT2 从 `app/src/gameTemplate/AndroidManifest.xml` 和默认图标生成最小 `game-template.apk` 资产，不提交预编译模板。
3. 发布前保存标签、校验 `main.lua`，再由 `LovePackageBuilder` 生成最新 `game.love`。
4. `AndroidApkAssembler` 取模板的 Manifest、资源表和图标，取当前已安装 Love2Droid APK 的 runtime DEX 与必要 native 库，写入 `assets/game.love`；编辑器资产、终端和 PRoot native 库不进入成品。
5. `AndroidBinaryXmlEditor` 重写二进制 Manifest 的包名、应用名、版本、方向和权限，并在输出前重新解析验证结果。
6. `AndroidSigningStore` 为每个项目在 AndroidKeyStore 中维护稳定签名，也允许导入 RSA/EC PKCS#12；密码只存在于当前导入操作的内存中。`AndroidApkBuilder` 使用官方 `apksig` 生成 v1/v2 签名。
7. 成品写入 cache 下的 `android-packages/`，只通过 `FileProvider` 临时 URI 交给分享目标或系统安装器。Android 8.0 及以上的未知来源授权由系统设置处理。

独立成品启动 `PackagedLoveGameActivity`，不包含编辑器内 Play 使用的调试悬浮层。成品 APK 继承当前已安装 Love2Droid APK 中可用的 ABI；分 ABI 安装时不会凭空补齐其他 ABI。
- 打包面板展示和实际构建均按项目 id 从 `ProjectRepository` 重新读取元数据，避免使用属性编辑前的旧 `Project` 快照。

## 项目搜索与版本控制

- 编辑器内搜索由 `EditorSearchController` 封装 Sora `EditorSearcher`，维护普通/正则查询、替换展开状态和标签切换后的重新提交。
- 项目文件与文本搜索由 `ProjectSearchEngine` 在后台执行，跳过产品元数据、`.git/`、二进制和超限文件；所有结果仍受项目根目录边界约束。
- 符号搜索优先使用 LuaLS `workspace/symbol`，无语义结果时退化到本地 Lua 标识符索引，并在结果模型中标记来源。
- Git 后端只通过 PRoot 内的 `git` CLI 执行固定参数命令。`GitClient` 限制输出大小，解析 porcelain/NUL 分隔机器格式，并提供仓库初始化、文件级暂存/取消暂存、全部暂存/取消暂存、提交和工作区/暂存区 Diff；自定义 Diff 解析器把 unified patch 转为带行号的红绿列表。UI 不实现分支、合并、推送或凭据管理。

## 编辑器、语法与 LSP

- Sora `CodeEditor` 提供文本编辑；TextMate grammar 只由 `app/src/main/assets/textmate/languages.json` 注册，主题由 `EditorActivity` 加载 `quietlight` 与 `darcula`。
- 扩展名映射只维护在 `LanguageResolver`。grammar 缺失时退化到纯文本并显示可理解错误，不能阻塞打开文件；当前额外覆盖 TOML 和 GLSL 着色器扩展名。
- `EditorFileLoader` 在 `Dispatchers.IO` 严格解码 UTF-8，先探测二进制和 5 MB 上限，并识别换行风格；标签保存磁盘大小/修改时间基线，外部变化由 Activity 自动重载或提示确认。
- 每个项目根目录对应一个 `LspProject`，文档 URI 使用真实项目文件路径。
- LSP 的 stdin/stdout 只承载 JSON-RPC；stderr 独立排空到日志。
- PRoot bind 保持 Android 主体与 Ubuntu guest 中的项目绝对路径一致，避免 workspace URI 分叉。
- Lua 符号导航直接使用 `textDocument/definition` 和 `textDocument/references`；返回的 `file:` URI 必须 canonicalize，并限制在当前项目根目录内后才能作为可编辑标签打开。
- LuaLS 悬浮 Markdown 中的 `file:` 链接由应用内链接处理器消费，禁止通过 `URLSpan` 向系统暴露 `file://` URI。

## 终端与 PRoot

- 文件标签和终端标签共用标签栏；每个终端标签持有独立 `TerminalSession`。
- PRoot 当前产品支持范围为 `arm64-v8a`。`libproot.so` 从 APK 解压后的 `applicationInfo.nativeLibraryDir` 定位并执行，不复制到普通 data 目录。
- 首次安装校验并解压固定版本和 SHA-256 的 Ubuntu Base 24.04.4 arm64，再安装固定版本的 LuaLS、omp 和 bash-prompt；全部阶段成功后才写入完成标记。
- Ubuntu guest 的 `/etc/group` 补齐 Android 应用进程继承的 supplementary GID，避免登录 shell 查询组名时输出未知 group ID。
- 产品启动的 Git 进程注入 `core.createObject=rename`，绕过 Android 共享存储上不可靠的硬链接对象落盘；终端 Git 继承同一设置。
- 安装失败保留可复用阶段并允许重试，不提前写入完成状态。

## 主要模块职责

- Activity：组装 View 和生命周期；不执行递归文件操作。
- `ProjectRepository`：项目目录和元数据；不持有编辑器 View。
- `EditorSession`：标签、dirty、光标和滚动状态；不负责项目列表 UI。
- `LanguageResolver`：扩展名到语言定义的唯一映射入口。
- `EditorFileLoader`：受限 UTF-8 文本加载、二进制/大文件探测、换行识别和文件基线。
- `LovePackageBuilder` 与 `LoveArchiveTransfer`：项目目录到 `.love` 快照，以及 SAF `.love`/`.zip` 归档导入导出。
- `AndroidApkAssembler` 与 `AndroidBinaryXmlEditor`：纯归档/二进制 Manifest 变换；不持有 Activity 或签名密钥。
- `AndroidSigningStore`：AndroidKeyStore 和 PKCS#12 导入边界；项目元数据只保存稳定身份标识，不保存私钥或密码。
- `AndroidApkBuilder`：串联 `.love`、模板、runtime 条目与签名，输出 cache APK。
- `ProjectSearchEngine` 与 `GitClient`：后台搜索和只读 Git 协议；Bottom Sheet 只负责展示与用户动作。
- `StorageUtils`：路径边界、原子写和递归文件操作。
- runtime 边界：保存、校验、打包后启动 LÖVE，不处理文件浏览器选择。
- `SafFileTransfer`：通过 `DocumentFile` 和内容流在项目边界内递归导入/导出；不解析外部 URI 路径。
- `GameDebugOverlay`：游戏内黑色半透明悬浮球、HUD 和自适应调试面板；负责日志筛选、监视、REPL、执行控制、暂停源码展示和面板动画，不负责断点编辑。
- `DebugWatchStore`：按项目 id 在应用私有 `files/debug-watches/` 中原子保存监视表达式和固定状态；调试 runtime 负责读写，`ProjectRepository` 在项目重命名和删除时同步迁移或清理。
- `love_debug.cpp`：native 调试命令/日志边界、Lua 保护求值、断点与单步 hook；固定内存上限，不持有 Android View。

## 长期风险约束

- LÖVE native 构建受 NDK、ABI 和 CMake 影响：依赖版本必须锁定，`normalNoRecord` 变体必须链接全部 ABI。
- `Android/data` 可能对第三方文件管理器不可见：应用内文件浏览器是基础管理入口。
- TextMate 资源影响 APK 体积和许可证：只引入需要的 grammar，并记录来源与许可证。
- 文件操作、编辑保存和列表刷新可能并发：写入串行化并采用原子替换，操作完成后再提交列表。
