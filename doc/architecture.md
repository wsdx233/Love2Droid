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
    ├── .luarc.json        # 通用 LuaLS 配置
    ├── main.lua           # LÖVE 入口
    ├── conf.lua
    ├── AGENTS.md          # 仅基础项目：运行与代理验证约束
    └── assets/
        └── fonts/         # 仅基础项目：默认字体及许可证
```

`NewProjectActivity` 承载模板选择与创建表单，创建成功后经项目管理页返回 project id，由编辑器沿用原有项目切换流程打开。`ProjectTemplate` 枚举明确区分空项目与基础项目，仓库创建接口要求调用方显式传入模板，不依赖隐式默认值。空项目入口来自 `app/src/normal/assets/project-template-empty/`，只绘制 Hello World，使用引擎内置字体；基础项目沿用 `app/src/normal/assets/project-template/` 的入口、`AGENTS.md`、OTF 与许可证。`ProjectTemplate` 原子写入文本并按模板复制字体，`ProjectRepository` 为两者生成 `conf.lua`、`.luarc.json` 和元数据，并在创建失败时删除未完成的项目目录。创建操作在 `Dispatchers.IO` 执行；空项目不生成 `assets/` 或 `AGENTS.md`。基础项目的字体与代理说明属于普通项目内容，随 Play 快照、`.love` 导出和游戏 APK 分发，不依赖编辑器私有路径，也不修改上游 runtime 的内置字体。已有或导入项目不自动补写模板内容。

存储不变量：

- 目录名是稳定 project id；显示名和简介存放在元数据中。
- 项目内新建和重命名的名称不得包含 `..`、路径分隔符或控制字符。
- 项目文件操作先 canonicalize，再用 `StorageUtils.isWithin` 确认未逃逸项目根目录。DSH Web 主动打开外部文件走单独的 `EditorFileAccess` 边界，不扩大 Drawer、项目搜索、Git 或打包的操作范围。
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
- 终端 PTY 输出通知在主线程按消息合并并以约 16ms 节奏处理，避免持续输出占满主线程导致标签和其他输入事件失去响应。
- PRoot 当前产品支持范围为 `arm64-v8a`。`libproot.so` 从 APK 解压后的 `applicationInfo.nativeLibraryDir` 定位并执行，不复制到普通 data 目录。
- `TerminalSession` 的参数数组直接交给 `execvp()`，必须保留可执行文件作为 `argv[0]`；PRoot 开关从 `argv[1]` 开始。普通终端与 DSH 仅要求 rootfs 就绪，不依赖可选 OMP、LuaLS 或 bash-prompt。
- 共享存储只绑定可访问的应用目录和项目真实路径，不绑定 Android 的 `/storage`、`/sdcard`、`/mnt` 父目录。绑定目标使用 `host:guest!` 保留路径，设置 `PROOT_DONT_POLLUTE_ROOTFS=1` 让 PRoot 在临时 glue 目录准备缺失路径；应用不再向 PRoot 创建的 `000` 权限占位目录递归写入，也不绑定宿主 `/root`。
- PRoot 启动准备统一维护 guest `/root/projects` 软链接，目标沿用项目仓库的应用文件目录下 `projects` 规范路径，借助已有应用目录绑定访问真实项目。使用 API 23 可用的 `Os.readlink` / `Os.symlink`，正确链接不重建，失效链接更新，同名真实文件/目录保留；不向共享存储创建软链接。
- 普通版首次启动支持模块化安装选择或跳过，按用户选择校验并解压固定版本和 SHA-256 的 Ubuntu Base 24.04.4 arm64，以及可选的 LuaLS、omp、Git、DSH、bash-prompt 和 LÖVE 无界面检查；各组件独立维护安装标记，支持按需断点补充安装。
- 安装向导仅在应用首次启动时展示一次，后续启动直接进入编辑器；用户可通过设置“环境与扩展组件”随时进入管理或补充安装。
- Ubuntu guest 的 `/etc/resolv.conf` 固定使用 `8.8.8.8`、`8.8.4.4`，并通过 `options use-vc` 强制 glibc 使用 TCP DNS；真机已确认同一网络下 IP 连接和 TCP DNS 正常而默认 UDP DNS 失败。应用不启动 DNS 代理，也不把特定 Wi-Fi 或 VPN 的临时 resolver 持久化到 guest；环境完整性检查会让旧安装重新进入配置阶段并修复该文件。
- Ubuntu guest 的 `/etc/group` 补齐 Android 应用进程继承的 supplementary GID，避免登录 shell 查询组名时输出未知 group ID。
- OMP 标签不持有或持久化 session ID；新建标签使用 `omp --allow-home`，仅恢复工作区中的 OMP 标签时使用 `omp --allow-home --continue`，由 OMP 自己选择当前工作目录下的第一个可恢复 session。
- 产品启动的 Git 进程注入 `core.createObject=rename`，绕过 Android 共享存储上不可靠的硬链接对象落盘；终端 Git 继承同一设置。
- DSH 后台服务使用独立 `TerminalSession` 并显式初始化终端模拟器；先运行随 APK 部署的 `dsh-love2droid/activate.mjs`，成功后以 `exec dsh --profile web --no-open --port 3080` 启动，让服务退出结束后台会话。后台和应用内普通终端仅从完整、已换行的 `dsh web:` 输出捕获 `127.0.0.1:3080` 认证 URL，不能使用分批输出中的 token 前缀。token 仅在当前进程内存使用，工作区只持久化无 token 的 loopback 基地址。
- WebView 等待认证 URL，不抢先加载无 token 基地址；每个 DSH 标签独立记录已提交的认证 URL，避免服务重定向到 `/` 后切换标签又触发认证。新标签和失败后的重试沿用原认证地址规则。API 24+ 通过 `network_security_config.xml` 仅允许 `127.0.0.1` 的 HTTP；API 23 使用 Manifest 的 `usesCleartextTraffic` 兼容开关。
- `EditorActivity` 按 `DshWebTab` 对象身份持有独立的 WebView、`DshWebLoadState` 和已加载认证地址；首次显示标签时才创建页面，容器只挂载活动页，其他页保留在内存。页面回调只更新所属页状态，顶栏后退/刷新和进度条始终读取活动页，已关闭页的迟到回调不再改变界面。关闭、项目切换和 Activity 销毁时移除并销毁页面；不向标签模型或 `.lovedroid` 引入 Android View、网页历史或 token，原工作区列表格式直接支持多个 DSH 条目。
- DSH profile 是 pnpm workspace root。安装脚本和每次 PRoot 启动准备都会在 `/root/.dsh/profiles/web/.npmrc` 确保 `ignore-workspace-root-check=true`，兼容终端、插件市场和 DSH CLI 的直接 `plugin add`；预装移动端适配插件使用 `dsh-web-mobile`，已有其他 npm 配置保留。
- PRoot 每次启动通过 `StorageUtils.writeTextAtomic` 把 APK 中的 `proot/dsh-filesystem-compat.mjs` 安装或更新到 guest `/root/.local/share/love2droid/`，并通过 `NODE_OPTIONS=--import=...` 注入。后台 DSH、普通终端、安装脚本和继承环境的子进程/worker 使用同一适配；无需重装旧环境，已经运行的进程需要重启。安装脚本追加 `--jitless` 时保留该选项。
- Android 共享存储不支持硬链接或符号链接，`--link2symlink` 不能补齐该能力。适配只替换 `dsh-fs-local` 的 `createIfAbsent`、`dsh-session-persistence-jsonl` 的首次日志提交及 `dsh-attachment-local` 的附件提交，使用 `renameat2(RENAME_NOREPLACE)` 发布已写完并同步的临时文件；附件成功提交后的清理接受源文件已被移动。并发创建仍失败而非覆盖，版本检查、取消、附件去重/完整性验证和上游同步步骤保留；普通覆盖/编辑仍沿用原有 rename 路径。
- 适配复用 DSH 已依赖的 Koffi 和 Ubuntu glibc，不修改已安装 npm 包、Node 全局 `fs.link` 或 PRoot syscall 语义。最低 Node.js 22.15；当前核对的 DSH 包为 `0.1.2-rc.1`。上游提交代码结构变化时明确报错，不能静默漏补；内核/挂载点拒绝 `RENAME_NOREPLACE` 时保留真实错误，不退化为先检查再覆盖或非原子复制。普通终端工具主动创建链接、把 npm/pnpm 安装目录移到共享存储等行为不因此获得通用 POSIX 链接能力。
- `assets/proot/dsh-love2droid/` 是应用自有的 DSH 集成插件，不是文件系统加载补丁的另一份实现。每次 PRoot 准备原子更新插件文件；DSH 启动前通过正在运行的 CLI 的 `dsh-app-boot` 初始化/读取 Web profile，维护本地包软链接并幂等加入 bundle 列表，不联网安装、不覆盖用户 patch 或其他配置。包路径被同名真实目录占用时明确失败，不删除用户文件。
- Web 插件仅在当前窗口存在 `Love2DroidFiles` 桥接时包装公开的 `connection.rpc.call`：接管 `session/openWorkspacePath` 文件请求和可用性查询；普通浏览器、目录、HTTP(S) 链接及其他 RPC 沿用原实现。不修改 `xdg-open`、Node 文件系统 API 或上游网页源码。插件卸载时还原调用并取消等待中的请求。
- 插件通过继承 DSH Host/Origin 与认证检查的 `/love2droid/resolve-file` RPC，在 guest 内执行 realpath/stat；Android 使用 `WebViewCompat.addWebMessageListener`，仅接受 `http://127.0.0.1:3080` 主 frame 的请求，并再次校验路径。`EditorFileAccess` 按真实 PRoot bind 映射 rootfs、应用 files/cache、专属外部存储、`/tmp`、`/var/tmp` 和 `/dev/shm`；拒绝越界、逃逸软链接及其他 `/dev`、`/proc`、`/sys` 文件。不开放任意 Android 存储权限。
- 外部文件复用 `EditorActivity.openFile` 的受限后台加载和 canonical 标签身份：同一文件不重复开标签，dirty 内容不因重开被覆盖，不切换当前项目。保存仍使用原子写入并复核应用存储边界；`.lovedroid` 使用 `externalPath` 保存外部绝对路径，恢复时重新校验，未保存文本仍保留。外部文件和 `.lovedroid` 不进入当前游戏快照。
- WebView 已经位于应用自行消费系统状态栏 inset 后的内容区；移动端插件的 `viewport-fit=cover` 安全区规则若再次作用于 `[data-mobile-ux="frame"]`，应用在页面完成后清除这两个 frame padding，避免顶部重复空白。
- 安装失败保留可复用阶段并允许重试，不提前写入完成状态。

### 离线整包

- `offline` mode 与 `normal` 共用产品源码、资源、包名和发布签名，只保留 `arm64-v8a`，通过 `BuildConfig.BUNDLED_ROOTFS` 选择整包恢复；普通版不携带镜像。两种 APK 是同一应用的替代发布产物，不是可并装的两个应用。
- `tools/offline-rootfs.py` 从固定来源的干净 Ubuntu Base 构建完整环境，预装 LuaLS、OMP、nvm/Node.js/npm/pnpm、DSH 及 Web 插件、Git、bash-prompt 和完整 `love-check` 图形依赖。版本入口为 `tools/offline-rootfs.lock.json`；发布镜像同时记录实际 APT/npm 依赖清单，不导入开发者或用户的现有 rootfs。
- 主机使用 rootless Podman user namespace 和独立 mount namespace 内的 chroot；x86_64 使用固定 BuildKit QEMU 执行真实 ARM64 程序，避免 PRoot + QEMU 的 V8 地址映射崩溃。该构建路径不是 Android 模拟器，也不替代手机 PRoot 验证。
- 镜像采用 `tar.xz`、`-9e` 与 32 MiB 字典，APK 中不二次压缩 XZ。`OfflineRootfs` 使用流式解码，解码器内存上限 64 MiB；这不是整个 App 的堆内存上限。Manifest 记录 SHA-256、压缩/展开大小、条目数与完整组件集合，Gradle 打包前也校验镜像。
- 在线 gzip 和离线 XZ 共用 `RootfsArchive` 的路径边界、guest 链接和权限恢复规则。离线先校验剩余空间，再解压到同级 staging；拒绝路径或链接逃逸，完整检查 XZ、大小、条目数及 SHA-256 后才改名提交。中断只清理自有 staging，不删除现有非空 rootfs。
- 解压进度由 `RootfsArchive` 在文件写入和条目处理时上报，`OfflineRootfs` 按约 200ms 间隔发布实际展开字节数、已处理条目数和当前相对路径；归档校验阶段强制发布最终计数。`ProotInstaller` 映射连续的安装阶段进度，并根据现有离线自检脚本的逐组件成功输出更新通过项数；只有完整流程成功才提交 100%。安装页只有日志变化时才重建日志文本和滚动，不因解压进度刷新重复处理日志。
- 手机恢复后沿用设备 DNS、GID、路径和脚本准备逻辑，再执行实际 CLI、DSH 认证 Web 页面与 `love-check doctor`；全部通过才写组件完成标记。验证失败重试复用已恢复的同一镜像，不再次解包。离线流程不调用 APT/npm 或下载回退；升级只复用完整旧环境，不用新镜像覆盖用户环境，既有不完整环境保留并明确报错。


## LÖVE 无界面检查

- 检查环境独立于 Android 游戏进程：Ubuntu guest 使用发行版包 `love=11.5-1build1`、LuaJIT、Python 3、Xvfb 和 Mesa；不修改 App 内置的 LÖVE 12.0/SDL3 runtime，也不把 Linux 结果等同于 Android 验证。
- `LoveCheckRuntime` 负责组件就绪状态与脚本部署。每次 PRoot 启动准备通过 `StorageUtils.writeTextAtomic` 更新 `assets/proot/love-check/` 到 guest `/root/.local/share/love2droid/love-check/`，命令入口为 `/usr/local/bin/love-check`。路径先按 `StorageUtils.isWithin` 全部校验；就绪检查读取实际 `love-11.5` 文件，避免 Android 主体解析 guest 的绝对 alternatives 链接。
- 安装仅依赖 rootfs，不要求 OMP、DSH 或 LuaLS。APT 安装成功后必须执行真实 `doctor` 自检，验证图片加载、字体、Shader、音频 API、存档和软件渲染，再写独立完成标记；失败保留真实错误，不能仅凭可执行文件存在标记完成。
- Ubuntu Base 默认排除 man 手册；安装脚本在独立的 `dpkg.cfg.d/love2droid-love-check` 中只保留 `man6` 目录和 `love*` 手册，供发行版包的 `update-alternatives` 正常注册，不移除其他文档裁剪规则。旧安装已有 `love-11.5` 但缺少手册时，通过 APT 下载固定版本包并执行 `dpkg --unpack` 恢复文件，再由正常安装事务完成配置；不使用可能在半配置状态报 `No file name` 的 `apt --reinstall`，也不改写包维护脚本或忽略 dpkg 错误。
- 检查器直接启动 Xvfb，使用 `-displayfd` 等待显示服务就绪，关闭 TCP 监听，并为服务端和客户端写入一次性 MIT-MAGIC-COOKIE-1 认证记录。不使用 `xvfb-run`/xauth 的硬链接锁，也不通过 `-ac` 关闭认证。
- 子进程固定 `LIBGL_ALWAYS_SOFTWARE=1`、`GALLIUM_DRIVER=llvmpipe`、`LP_NUM_THREADS=2`、`SDL_VIDEODRIVER=x11`；真实 renderer 必须包含 llvmpipe。`ALSOFT_DRIVERS=null` 保留 OpenAL/LÖVE 音频 API 和混音，不要求物理音频设备；不验证实际听感。
- ARM64（`aarch64` / `arm64`）检查子进程固定 `LLVM_CPUINFO=/dev/null`，让 LLVM 使用通用 CPU 目标，避免依 CPU 型号生成的 llvmpipe 指令在 PRoot 中触发 `SIGILL`。安装 `doctor` 与项目检查共用这一环境；覆盖继承的同名设置，但不修改父进程环境、shell 配置或其他架构的 CPU 选择。仍执行真实 llvmpipe、Shader 和音频 API，不降级到伪造渲染或跳过自检。
- `love-check check` 只读取已保存的项目目录，在临时副本上运行。过滤沿用 `LoveArchiveTransfer.addDirectory` 的非隐藏文件规则；canonical 路径不能逃逸项目根，目录链接不能形成循环。沿用归档的 4096 条目、单文件 64 MiB、总内容 256 MiB 上限。LuaJIT 先对副本中的 Lua 文件做纯解析，再包装副本的 `conf.lua`/`main.lua`，错误堆栈保留原入口文件名；不修改项目源码。
- 每次检查使用独立 HOME、XDG 数据/配置/缓存和工作目录，测试存档及相对文件写入随临时副本清理。这是运行隔离，不是安全沙箱：游戏仍是应用权限下执行的代码，不能据此承诺阻止恶意 Lua/FFI 的绝对路径或网络访问。
- 帧预算按成功的 `love.graphics.present()` 计数，顶层 main 和标准 `love.load` 中的加载画面不计入。自定义 `love.run` 的阻塞式绘制循环也受呈现预算约束；没有呈现的循环由外部超时终止。不改写 dt/随机数，不伪造图形 API，不保证未执行的关卡或交互路径正确。
- 有效检查命令在 stdout 返回一个 JSON 结果；日志保留末尾最多 64 KiB 并标记截断。提前退出、错误、超时和取消均不算通过；超时和信号取消回收受监督的游戏、Xvfb 及其进程组。命令与验收见 [verification.md](verification.md#löve-无界面检查)。

## 应用更新检查

- `SettingsStore.autoCheckUpdates` 沿用应用偏好存储，缺省为 `true`；普通版和离线版共用检查实现及 `BuildConfig.VERSION_NAME`，不使用游戏项目的发布版本号。
- `AppUpdateChecker` 在 `Dispatchers.IO` 请求 `https://api.github.com/repos/wsdx233/Love2Droid/releases/latest`，使用连接/读取超时和响应大小上限。只接受正式非草稿 Release，404 表示暂无正式版本；HTTP 错误、异常 JSON 和不可解析版本作为检查失败处理。
- 版本比较兼容 `v`/`V` 前缀，按数字段比较并补齐末尾零；预发布标识低于同号正式版，build metadata 不影响排序。相同或更低版本不提示升级，下载入口只构造在固定项目仓库下的 Release 页面。
- `AppUpdateSession` 在应用进程内去重启动检查与并发请求，持有待消费结果而不持有 Activity；自动无更新/失败不产生用户通知，手动检查保留结果反馈。关闭开关后未显示的自动更新结果不再弹出。
- `AppUpdateUi` 绑定安装向导、编辑器、项目管理和设置的 `RESUMED` 生命周期；页面销毁不取消共享检查，后台不弹窗，结果仅消费一次。`AppUpdateDialog` 使用 Fragment 参数恢复旋转时已显示的更新提示，不触及独立游戏进程或上游 runtime。

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
