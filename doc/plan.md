# Love2Droid 基础框架计划

## 1. 目标

新建 Android 应用，包名与 applicationId 固定为 `top.wsdx233.love2droid`，提供一个面向手机的 LÖVE 游戏项目编辑器：

- 集成可启动 LÖVE 游戏的 Android 原生运行环境。
- 集成 Sora Editor。
- 为 Lua 及常用语言提供语法高亮。
- 提供手机友好的单目录文件浏览器、文件多标签和编辑区。
- 支持通过右上角 Play 启动当前项目。
- 支持项目管理和新建项目。
- 项目保存在应用专属外部目录的 `projects/` 下。

基础框架已扩展为可运行闭环：除编辑、项目管理和 LÖVE 运行外，加入 arm64 proot 终端与 Lua Language Server；调试器、Git、云同步、插件系统和完整 VS Code 级 IDE 能力仍不在当前范围。

## 2. 已准备的参考项目

已克隆，仅作为实现参考，不直接把示例 App 当作产品代码：

- `ref/love-android/`
  - 上游：<https://github.com/love2d/love-android>
  - 已递归拉取其 `love` 与 `megasource` 子模块。
  - 重点参考 `GameActivity`、SDL/NDK/CMake 构建、Content URI 启动方式和 Android Manifest。
- `ref/sora-editor/`
  - 上游：<https://github.com/Rosemoe/sora-editor>
  - 重点参考 `CodeEditor` 初始化、TextMate 注册、语言资源和 Android 17 配置。

- `ref/r2droid/`
  - 上游：<https://github.com/wsdx233/r2droid>，参考提交 `f1c63598688027dcbf4404c27ef14a3893ccb9e1`。
  - 重点参考 Termux `TerminalView`/`TerminalSession` 集成、双行快捷键栏、proot 启动参数和安装进度日志页面。
参考项目的上游许可证和第三方语法文件许可证必须在正式项目中保留声明；不会把参考项目的 Demo Activity、测试页面和无关资源整体复制进产品代码。

## 3. 技术路线

### 3.1 Android 工程

- Kotlin 为业务层主要语言；必要的 LÖVE/SDL glue code 保留 Java/C++。
- Android View/XML + `AppCompat`/Material，而不是 Compose。
  - 理由：Sora Editor 是 View widget；LÖVE Android 上游也是传统 Activity/NDK 结构；先减少跨 UI 技术栈和原生窗口交互风险。
- `minSdk` 先定为 API 23，与当前 LÖVE Android 上游构建配置保持一致；Sora Editor 本身支持更低 API，但不因它单独降低运行时下限。
- `compileSdk` 固定为 API 37；为兼容 PRoot 内置 guest loader 从应用私有临时目录执行，`targetSdk` 固定为 API 28；API 29+ 的 W^X 策略会拒绝该 loader 的执行。原生 PRoot 库仍从 APK 解压后的 `nativeLibraryDir` 获取并执行。
- Java/Kotlin 编译目标使用 JDK 17。
- 原生构建使用上游要求的 Android NDK/CMake 组合，并将版本锁定，不使用动态 `latest`。
- `namespace`、`applicationId`、Java/Kotlin 包统一为 `top.wsdx233.love2droid`。

### 3.2 LÖVE 集成策略

采用“单 Android App module + 上游 LÖVE native runtime”的方案：

1. 以 `love-android` 的 Gradle/CMake/SDL/native library 装配方式为基础。
2. 正式工程把 LÖVE 与 megasource 作为锁定提交的 submodule 或等价 vendor 目录放在运行时边界内；`ref/` 只存研究副本。
3. 保留上游的 `GameActivity`/SDL 初始化和 native library 加载顺序，产品侧只增加最小的启动参数与生命周期适配。
4. 产品主入口是编辑器 Activity；LÖVE 游戏使用独立的全屏 `LoveGameActivity` 和 `:game` 进程，避免 SDL/native 清理影响编辑器主进程。
5. 不把 LÖVE 做成普通 Maven 依赖；它包含 CMake、JNI、SDL 和多 ABI native 构建，直接依赖会隐藏关键打包约束。

#### Play 启动方案

初版不直接把 `/Android/data/.../projects/<id>/` 作为外部路径交给运行时，而是：

1. 保存当前打开文件。
2. 校验项目根目录存在 `main.lua`，并拒绝越过项目根目录的路径。
3. 将项目目录按 `.love` ZIP 规则生成到应用 cache 的临时文件。
4. 通过 `FileProvider` 暴露临时 `.love` 文件的 `content://` URI，并带上临时读权限。
5. 启动 `LoveGameActivity`，沿用上游对 content URI 的参数处理。
6. 游戏退出或下一次启动时清理旧临时包。

理由：Android 11+ 的 scoped storage 下不依赖 file URI 或广泛存储权限；项目运行快照明确，且不会让 LÖVE 读取编辑器的半写入文件。后续如果实测 native runtime 能稳定直接读取应用专属目录，再评估去掉 ZIP 快照，但不把该优化作为基础框架前置条件。

### 3.3 项目存储

用户所说的 `android/data` 在 Android 的规范实际路径应为：

```text
/storage/emulated/0/Android/data/top.wsdx233.love2droid/files/projects/
```

代码使用 `getExternalFilesDir(null)/projects` 获取路径，不硬编码绝对路径，也不申请 `MANAGE_EXTERNAL_STORAGE`。这仍然位于应用专属的 `Android/data/<包名>/files/` 目录下。

项目目录建议结构：

```text
projects/
└── my-game/
    ├── .love2droid.json   # 编辑器元数据：显示名、简介、创建时间、版本
    ├── main.lua            # 运行入口，必须存在
    ├── conf.lua            # 新建项目默认生成
    └── assets/             # 新建项目默认生成，可选
```

约束：

- 目录名是稳定 project id；显示名和简介放在元数据文件中。
- 目录名只允许安全字符，禁止 `..`、路径分隔符和控制字符。
- 所有文件操作先 canonicalize，再确认仍位于当前项目根目录下。
- 文件保存采用临时文件 + 原子替换，避免应用被杀时破坏源文件。
- 项目仓库以文件系统为数据源，不先引入数据库。
- 初版新建项目生成 `main.lua`、`conf.lua`、`assets/` 和元数据；模板内容保持最小可运行。

## 4. 主界面设计

### 4.1 总体布局

```text
┌──────────────────────────────────────────┐
│ ☰  项目名                    ▶  ⋮         │ AppBar（避开状态栏）
├──────────────────────────────────────────┤
│ [main.lua ×] [未命名 • ×]          [+]   │ 多标签栏
├──────────────────────────────────────────┤
│                                          │
│              Sora CodeEditor             │ 编辑区
│                                          │
├──────────────────────────────────────────┤
│ ← → fun ( [ { " = : . , _ + - …         │ 快捷输入栏（随输入法上移）
└──────────────────────────────────────────┘
```

- 左上角导航按钮打开/关闭左侧文件浏览器。
- 右上角三角形 Play 启动当前项目。
- Play 右侧使用 overflow action item，打开项目管理界面。
- 横屏时 Drawer 和编辑区可以同时显示；竖屏时 Drawer 覆盖编辑区并带 scrim。
- 触控目标不小于 Material 推荐尺寸；文件行高度初始按 56–64dp 设计。
- AppBar 和 Drawer 顶部按系统状态栏 inset 留白；编辑区采用 edge-to-edge，但交互控件不被系统栏遮挡。
- 导航、关闭、新建、保存、Play 等图标统一使用 Google Material Icons 矢量图标，不使用 `android.R.drawable` 平台旧图标。

### 4.2 左侧文件浏览器

使用 `DrawerLayout + RecyclerView` 的单目录逐级浏览模式，不展示可展开树：

- 列表显示当前目录的直接子项；进入子目录后首行额外显示带返回箭头的 `..` 上级目录项。文件夹优先，名称按不区分大小写的顺序排列。
- 行只显示文件/文件夹图标、名称和简介属性。
- 文件简介显示语言/文件类型和可读大小；文件夹显示直接子项数量。
- 顶部显示项目名和当前项目内相对路径；路径右侧三点菜单提供新建文件、新建文件夹、粘贴到当前目录和刷新。
- 不在行内显示完整路径、时间戳、权限等低频信息；详细属性放到长按菜单的“详情”。
- 目录读取和递归复制/删除在后台线程执行，完成后再提交新列表。

交互定义：

| 手势 | 默认行为 |
| --- | --- |
| 单击文件夹 | 进入该目录 |
| 单击 `..` 上级目录项 | 返回当前项目内的上级目录；项目根目录不显示该项 |
| 单击文件 | 打开或切换到对应标签 |
| 长按普通文件/文件夹行 | 弹出当前项目的操作菜单，不自动开启多选；上级目录项不提供长按操作 |
| 左右水平滑动普通文件/文件夹行 | 选中该行并开启多选；上级目录项不可选择，垂直滑动仍用于滚动 |
| 精确两个端点已选中 | 显示“选中区间”操作，按当前目录可见顺序选择两端之间项目 |
| 开启多选后单击 | 切换当前行选中状态，不打开文件/文件夹 |
| 文件浏览器内按返回键 | 先退出多选，再返回上级目录；项目根目录再关闭 Drawer |

长按菜单提供打开、重命名、复制、剪切、删除和详情；多选时操作作用于当前选中项，并可取消选择或选择两个端点之间的区间。新建、粘贴和刷新属于顶部当前目录菜单。新建文件名和重命名必须校验非法字符、重名和路径越界；删除使用确认对话框。

### 4.3 Sora Editor 编辑区

- 使用 `io.github.rosemoe.sora.widget.CodeEditor`。
- 每个打开文件维护独立的文本内容、光标/选区、滚动位置、语言类型和 dirty 状态。
- 文件首次打开时后台读取，避免阻塞主线程；不可解码内容提示为二进制文件，不强行载入编辑器。
- 文本编辑采用 UTF-8；保留检测到的换行风格，初版不做复杂编码转换。
- dirty 文件在标签上显示圆点；切换、后台和 Activity 销毁前触发保存，保存失败保留 dirty 状态并提示。
- 文件被外部修改时不静默覆盖：若当前标签 dirty，提示重新加载/保留编辑内容；未 dirty 时可直接刷新。
- 对超大文件设置明确上限并提示，避免在移动设备上无界加载导致内存压力；上限在实现阶段根据实际设备测试确定。
- 编辑区底部提供可横向滚动的常用符号栏，符号集合参考 AndroLua+；点击后替换当前选区或在光标处插入，并保持 Sora Editor/输入法焦点。
- 使用 IME inset 与 `adjustResize` 适配软键盘，键盘弹出时符号栏停靠在键盘上方，编辑区压缩而非被遮挡。
- AppBar 的文件操作菜单至少提供新建、保存、另存为；保存继续使用项目根目录内的原子写入和路径校验。

### 4.4 多标签

- 标签横向滚动，活动标签高亮，未保存标签显示 dirty 圆点，标签内提供关闭按钮。
- 同一相对路径只允许一个标签；重复打开只切换，不重复读入。
- 关闭 dirty 标签弹出保存/不保存/取消。
- 项目切换时关闭当前项目标签，先处理 dirty 状态；不跨项目复用 URI。
- 初版不做标签拖拽排序；后续可加入长按拖动。
- 支持未落盘的“未命名”标签；标签栏最右侧固定 `+` 按钮，用于新建未命名文档。
- 未命名文档首次保存或关闭时进入“另存为”流程；切换项目或 Play 前不静默丢弃未命名 dirty 内容。

### 4.5 终端标签与 proot

- 文件标签和终端标签共用标签栏；菜单可创建多个终端标签，每个标签持有独立的 proot shell 会话，切换标签不终止后台会话，关闭标签时终止对应会话。
- 终端主体使用 Termux `TerminalView`，底部按 r2droid 布局提供两行 `ESC`、`TAB`、`CTRL`、`ALT`、方向键、`HOME`、`END`、`PGUP`、`PGDN` 等快捷键。
- 当前 proot 产品支持范围为 arm64-v8a。可执行文件以 `libproot.so` 放入 APK 的 arm64 native library 目录，并通过 Gradle `jniLibs.useLegacyPackaging` 让最终合并清单启用 native library 解压；运行时只从 `applicationInfo.nativeLibraryDir` 定位并执行，不把可执行文件复制到普通 data 文件目录。
- 首次启动强制进入安装页：顶部 Toolbar 显示安装状态和线性进度，主体持续追加日志。安装程序校验并解压 Ubuntu Base 24.04.4 arm64 到 `filesDir/proot/ubuntu`，进入 proot 后安装固定版本的 Lua Language Server，全部命令成功后才写入完成标记。
- Ubuntu Base 与 LuaLS 下载都固定 URL、版本和 SHA-256；失败保留可复用阶段并提供重试，不写入“安装完成”标记。

### 4.6 项目管理界面

overflow action item 打开独立的项目管理页面或全屏 Bottom Sheet（以手机可操作性优先，最终根据实际布局选择）：

- 列出 `projects/` 下的项目。
- 显示项目图标、显示名、简介、最近打开时间；不显示完整内部路径。
- 新建项目：输入显示名、目录 id、简介；创建默认 LÖVE 文件。
- 打开项目：关闭/保存当前 dirty 标签后切换。
- 重命名项目：修改显示名和目录 id，更新引用；若目录已存在则拒绝。
- 删除项目：二次确认后递归删除整个项目目录。
- 当前项目可直接返回编辑器；没有项目时提供新建项目入口。

## 5. 语法高亮范围

优先使用 Sora 的 TextMate 集成，而不是为每种语言手写 lexer：

- 依赖 `io.github.rosemoe:editor`、`io.github.rosemoe:language-textmate`；版本使用 Maven Central 当前稳定版并在 Gradle 中固定。
- 使用 JDK 17，并按 Sora 文档启用 TextMate 所需的 core library desugaring。
- 在 `assets/textmate/` 注册 grammar、language configuration、`languages.json` 和主题。
- 第一批语言：Lua、Java、Kotlin、JavaScript/TypeScript、Python、HTML、XML、Markdown、JSON、CSS、Shell。
- 其中 Lua 是 LÖVE 项目的默认语言，必须优先验证；没有匹配 grammar 时使用纯文本语言，不阻塞打开文件。
- 扩展名映射集中在 `LanguageResolver`，不把映射散落在 Activity 和文件浏览器代码中。
- grammar 来源、版本/commit 和许可证写入第三方声明文件；不将 VS Code 等上游语法资源当成无许可证内容复制。
- Lua 文件在 Android 8.0 及以上通过 Sora `editor-lsp` 接入 proot 内的 LuaLS，提供协议声明的补全、诊断、悬浮、签名提示等能力；Android 6.0/7.x 保持 TextMate 编辑能力但不加载 `editor-lsp`。

Sora 的注册流程计划采用：

1. 应用启动时初始化 `FileProviderRegistry`/`GrammarRegistry`。
2. 加载 TextMate language registry。
3. 根据文件扩展名创建或更新 `TextMateLanguage`。
4. 给 `CodeEditor` 设置语言和主题。
5. 切换标签时先设置 `TextMateLanguage`，再载入文本，确保分析器收到完整文档并生成高亮 span；Lua grammar 注册失败时显示具体错误，不静默退化。

LuaLS 接入边界：

1. 每个项目根目录对应一个 `LspProject`，Lua 文档使用实际项目文件路径作为 URI。
2. `StreamConnectionProvider` 直接管理 proot 中 `lua-language-server` 的 stdin/stdout，stderr 独立排空到日志，不能混入 JSON-RPC 数据流。
3. 文件标签激活时用 `LspLanguage` 包装现有 TextMate language；切换到终端或非 Lua 标签时解除当前 LSP editor，保存成功后发送 `didSave`。
4. 项目外部目录通过 proot bind 保持主客体绝对路径一致，避免 LSP workspace URI 与真实文件路径分叉。

## 6. 建议的代码边界

```text
app/src/normal/java/top/wsdx233/love2droid/
├── EditorActivity.kt          # 编辑器、标签和文件浏览器交互组装
├── EditorSession.kt           # 打开标签、dirty、光标和滚动状态
├── FileBrowserAdapter.kt      # 当前目录列表、选择和滑动手势
├── LanguageResolver.kt        # 扩展名到 TextMate scope
├── ProjectRepository.kt       # 项目目录和元数据
├── ProjectManagerActivity.kt
├── ProjectValidator.kt
├── LovePackageBuilder.kt      # 项目目录 -> .love 临时快照
└── StorageUtils.kt            # 路径约束、原子写和递归文件操作

app/src/main/java/top/wsdx233/love2droid/runtime/
└── LoveGameActivity.java      # 上游 GameActivity 的最小产品适配
```

实际文件名可以按工程现状调整，但职责不跨层泄漏：

- Activity 负责组装 View 和生命周期，不直接执行递归文件操作。
- `ProjectRepository` 负责项目目录和元数据，不持有编辑器 View。
- `EditorSession` 负责标签/dirty/光标状态，不负责项目列表 UI。
- Play 启动流程负责保存、校验、打包和 Intent 启动，不负责文件浏览器选择。
- native LÖVE/SDL 代码尽量保持上游结构，产品改动集中在 Java Activity 和启动参数边界。

## 7. 分阶段实施顺序

### 阶段 A：工程与运行时基线

- 创建 Gradle Android 工程和包名。
- 接入锁定版本的 LÖVE Android native 构建。
- 让 `LoveGameActivity` 可以启动一个最小 `main.lua` 示例。
- 完成 ABI、NDK、CMake、Manifest、FileProvider、全屏窗口配置。

验收：安装 Debug APK 后，能从编辑器入口启动示例 LÖVE 游戏并返回编辑器。

### 阶段 B：项目存储与项目管理

- 实现应用专属 `files/projects/` 路径。
- 实现项目扫描、元数据读取、路径校验和默认模板。
- 实现项目列表、新建、打开、重命名、删除。

验收：新建项目后能在目录中看到 `main.lua`/`conf.lua`/`assets/`，重启应用后项目仍可见。

### 阶段 C：Sora 编辑器与单文件编辑

- 接入 Sora Editor 和 TextMate。
- 导入/注册语法资源与主题。
- 实现单文件读取、编辑、保存、dirty 状态和语言映射。

验收：打开 `main.lua` 能看到 Lua 高亮，修改后保存，重新打开内容一致。

### 阶段 D：文件浏览器与手机交互

- 实现 Drawer、单目录逐级浏览、当前路径和目录操作菜单。
- 实现文件行图标/名称/简介、单击进入或打开、长按菜单和水平滑动选择。
- 实现多选、两个端点的区间选择和批量文件操作。

验收：不依赖桌面右键或键盘，单手可逐级浏览目录，并完成新建、重命名、复制/剪切、粘贴、删除和批量选择。

### 阶段 E：多标签与会话

- 实现标签横向滚动、切换、关闭和 dirty 提示。
- 保存每个标签的光标、滚动位置和文本状态。
- 处理 dirty 关闭、项目切换和外部修改冲突。

验收：同时打开多个文件，切换后各自内容/位置正确；dirty 文件关闭前不会静默丢失。

### 阶段 F：Play 闭环

- 实现保存全部、项目校验、`.love` 快照生成。
- 通过 FileProvider/content URI 启动 LÖVE Activity。
- 处理无项目、缺少 `main.lua`、打包失败和运行时返回。

验收：点击右上角 Play 可以运行当前编辑中的游戏，修改 Lua 后再次 Play 运行新内容。

### 阶段 G：稳定性与交付检查

- 真机检查竖屏/横屏、返回键、旋转/Activity 重建、后台恢复。
- 检查大目录、重名、非法路径、删除确认、保存失败和无权限异常。
- 检查所有第三方许可证、ABI 打包和 Debug/Release 构建。
- 只在上述闭环完成后再做 UI 细节和性能清理。

## 8. 验证计划

实现阶段至少需要以下可重复验证：

### 单元/逻辑验证

- 项目 id 和相对路径不能逃逸项目根目录。
- 新建/重命名拒绝重名和非法名称。
- 当前目录区间选择包含两个端点，且不会包含其他目录项目。
- 多选点击是幂等切换；长按菜单不会误开启多选，普通单击仍进入目录或打开文件。
- 标签去重、dirty 关闭决策和项目切换状态正确。
- `.love` 快照包含项目文件且根目录存在 `main.lua`。

### 真机/运行时验证

- 首次安装、重启、横竖屏切换、切到后台再回来。
- 新建项目 -> 编辑 `main.lua` -> Play -> LÖVE 渲染 -> 返回。
- 修改多个文件 -> 切换标签 -> 保存 -> 再 Play。
- 打开包含非 ASCII 文件名、空目录、大目录和非法文件的项目。
- Android 11+ 不申请广泛存储权限仍能读写应用专属 `projects/`。
- LÖVE 运行时异常和缺少入口文件均有可理解的 UI 错误，不崩溃回桌面。

## 9. 主要风险与处理

1. **LÖVE native 构建较重且受 NDK/ABI 影响**
   - 锁定上游提交、Gradle plugin、NDK、CMake；先做独立运行时基线，再叠加 UI。
2. **content URI 到 LÖVE native 文件系统的兼容性**
   - 复用上游已有 content URI 参数路径；Play 初版使用 `.love` 快照；若实际设备发现某个 URI/SDL 组合不兼容，在 runtime 边界增加受控的本地 staging，而不是给全应用申请存储权限。
3. **Android/data 对用户文件管理器不可见或受限制**
   - 应用内文件浏览器是唯一基础管理入口；不假设第三方文件管理器能任意访问该目录。
4. **TextMate 资源体积和许可证**
   - 只引入第一批需要的 grammar，记录来源和许可证，避免复制整套 Demo 资源。
5. **手机编辑大文件导致卡顿/内存压力**
   - 后台读取、列表增量更新、文件大小保护；不在第一版加入复杂分页编辑器。
6. **文件操作和编辑保存并发**
   - 所有写入串行化，采用原子写；文件浏览列表只在操作完成后提交。

7. **Android W^X 限制影响 PRoot guest loader**
   - 对齐 r2droid 的 `-L`、`--link2symlink`、`--kill-on-exit`、`--root-id`、`-r`、bind 和 `/usr/bin/env -i` 参数；保留 `targetSdk 28`，避免 API 29+ 对应用私有临时 loader 的执行拒绝。

## 10. 默认方案，请你先审阅

以下选择是为了先得到一个可落地、风险可控的基础版本：

- 单 App module，LÖVE native runtime 与编辑器同 APK；不先拆成复杂多模块。
- Play 使用 `.love` 临时快照 + `FileProvider` content URI；不申请广泛存储权限。
- arm64 首次启动安装 Ubuntu Base 24.04.4、proot、LuaLS、omp 和 bash-prompt；文件与终端共用多标签栏；Lua 文件启用完整 LSP 客户端；新建项目自动生成 `.luarc.json`，加载 LuaJIT 和 LuaLS 内置 LÖVE 11.5 API library。bash-prompt 默认启用 `PROMPT_DIRTRIM=1`，避免终端目录显示完整的 Android/data 长路径。
- 初始语法高亮覆盖 Lua、Java、Kotlin、JavaScript/TypeScript、Python、HTML、XML、Markdown、JSON、CSS、Shell；无 grammar 时纯文本回退。
- 长按提供常见文件操作；区间选择限定为同一父目录的可见同级节点。
- arm64 首次启动安装 Ubuntu Base 24.04.4、proot、LuaLS、omp 和 bash-prompt；文件与终端共用多标签栏；Lua 文件启用完整 LSP 客户端；终端目录提示默认只保留末级目录。
- 调试器、Git、云同步和通用插件系统仍不在当前阶段。

本文件随实现状态维护；后续变更以用户最新要求为准。

## 11. 当前实现状态

基础框架已实现：LÖVE Android runtime、应用专属项目目录与项目管理、Drawer 单目录文件浏览器及文件操作、Sora Editor、文件/终端混合多标签、Termux 终端快捷键栏、arm64 proot、Ubuntu Base 首次安装流程、LuaLS 编辑器接入、TextMate 语法资源注册、`.love` 打包与 Play 启动链路；首次安装还会固定版本下载并启用 bash-prompt，将终端目录提示缩短为末级目录。

后续界面调整以用户最新要求为准：标签栏高度为 24dp；标签宽度随文件名自适应；文件名超过 15 个字符时显示前 15 个字符和 `...`；标签、关闭按钮和新建按钮均使用波纹反馈；活动标签底线使用主题色，其他标签使用灰色。该调整覆盖第 4.1 节原有的通用触控目标建议。

当前维护流程只运行不依赖 Android runtime 的逻辑测试和 APK 构建，不使用 Android 模拟器；界面、输入法、proot、Ubuntu 文件系统、LuaLS 和 LÖVE runtime 的实际交互由用户在 arm64 真机验证。打开文件内容覆盖、保存后 dirty 圆点刷新和游戏退出影响编辑器进程的问题均已在源码侧修正。

以下属于计划中的增强边界，当前基础框架尚未实现：外部文件内容变化冲突检测、原始换行风格保留、二进制内容探测，以及项目列表的最近打开时间展示。

当前已实现会话增强：每个项目根目录的 `.lovedroid` 以原子写入保存打开的编辑器标签、编辑器光标/滚动位置、未命名或未保存编辑文本、当前目录、终端标签及工作目录；终端进程下次打开时重建。OMP 标签不要求用户输入 session ID：启动 `omp` 后自动扫描新增的顶层 JSONL 会话文件，提取 OMP 自己生成的 session ID 并记录；下次启动使用 `omp -r <id>` 恢复，若发现不到 ID 则保留普通 `omp` 启动行为。
