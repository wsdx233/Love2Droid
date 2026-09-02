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

- 产品主入口负责项目管理、文件浏览、编辑器、标签和终端。
- `LoveGameActivity` 保留上游 `GameActivity`、SDL 初始化和 native library 加载顺序，只增加启动参数与生命周期适配。
- 游戏 Activity 运行在 `:game` 进程，避免 SDL/native 清理影响编辑器主进程。
- LÖVE 不封装为普通 Maven 依赖；CMake、JNI、SDL 和多 ABI 打包约束在工程中保持可见。

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

## 项目搜索与版本控制

- 编辑器内搜索由 `EditorSearchController` 封装 Sora `EditorSearcher`，维护普通/正则查询、替换展开状态和标签切换后的重新提交。
- 项目文件与文本搜索由 `ProjectSearchEngine` 在后台执行，跳过产品元数据、`.git/`、二进制和超限文件；所有结果仍受项目根目录边界约束。
- 符号搜索优先使用 LuaLS `workspace/symbol`，无语义结果时退化到本地 Lua 标识符索引，并在结果模型中标记来源。
- Git 后端只通过 PRoot 内的 `git` CLI 执行固定参数命令。`GitClient` 限制输出大小，解析 porcelain/NUL 分隔机器格式；当前 UI 只读展示状态、历史、提交详情和 Diff，不实现写操作或凭据管理。

## 编辑器、语法与 LSP

- Sora `CodeEditor` 提供文本编辑；TextMate grammar 只由 `app/src/main/assets/textmate/languages.json` 注册。
- 扩展名映射只维护在 `LanguageResolver`。grammar 缺失时退化到纯文本并显示可理解错误，不能阻塞打开文件。
- Lua 文件在 Android 8.0 及以上通过 Sora `editor-lsp` 连接 PRoot 内的 Lua Language Server；Android 6.0/7.x 保留 TextMate 编辑能力，不加载 `editor-lsp`。
- 每个项目根目录对应一个 `LspProject`，文档 URI 使用真实项目文件路径。
- LSP 的 stdin/stdout 只承载 JSON-RPC；stderr 独立排空到日志。
- PRoot bind 保持 Android 主体与 Ubuntu guest 中的项目绝对路径一致，避免 workspace URI 分叉。
- Lua 符号导航直接使用 `textDocument/definition` 和 `textDocument/references`；返回的 `file:` URI 必须 canonicalize，并限制在当前项目根目录内后才能作为可编辑标签打开。
- LuaLS 悬浮 Markdown 中的 `file:` 链接由应用内链接处理器消费，禁止通过 `URLSpan` 向系统暴露 `file://` URI。

## 终端与 PRoot

- 文件标签和终端标签共用标签栏；每个终端标签持有独立 `TerminalSession`。
- PRoot 当前产品支持范围为 `arm64-v8a`。`libproot.so` 从 APK 解压后的 `applicationInfo.nativeLibraryDir` 定位并执行，不复制到普通 data 目录。
- 首次安装校验并解压固定版本和 SHA-256 的 Ubuntu Base 24.04.4 arm64，再安装固定版本的 LuaLS、omp 和 bash-prompt；全部阶段成功后才写入完成标记。
- 安装失败保留可复用阶段并允许重试，不提前写入完成状态。

## 主要模块职责

- Activity：组装 View 和生命周期；不执行递归文件操作。
- `ProjectRepository`：项目目录和元数据；不持有编辑器 View。
- `EditorSession`：标签、dirty、光标和滚动状态；不负责项目列表 UI。
- `LanguageResolver`：扩展名到语言定义的唯一映射入口。
- `LovePackageBuilder`：项目目录到 `.love` 临时快照。
- `AndroidApkAssembler` 与 `AndroidBinaryXmlEditor`：纯归档/二进制 Manifest 变换；不持有 Activity 或签名密钥。
- `AndroidSigningStore`：AndroidKeyStore 和 PKCS#12 导入边界；项目元数据只保存稳定身份标识，不保存私钥或密码。
- `AndroidApkBuilder`：串联 `.love`、模板、runtime 条目与签名，输出 cache APK。
- `ProjectSearchEngine` 与 `GitClient`：后台搜索和只读 Git 协议；Bottom Sheet 只负责展示与用户动作。
- `StorageUtils`：路径边界、原子写和递归文件操作。
- runtime 边界：保存、校验、打包后启动 LÖVE，不处理文件浏览器选择。

## 长期风险约束

- LÖVE native 构建受 NDK、ABI 和 CMake 影响：依赖版本必须锁定，`normalNoRecord` 变体必须链接全部 ABI。
- `Android/data` 可能对第三方文件管理器不可见：应用内文件浏览器是基础管理入口。
- TextMate 资源影响 APK 体积和许可证：只引入需要的 grammar，并记录来源与许可证。
- 文件操作、编辑保存和列表刷新可能并发：写入串行化并采用原子替换，操作完成后再提交列表。
