# 验证要求

本文记录可重复的逻辑测试、APK 构建和真机验证边界。当前实现状态见 [status.md](status.md)。

## 逻辑验证

逻辑行为变化应运行对应的聚焦测试，至少覆盖受影响的不变量：

- project id 和相对路径不能逃逸项目根目录。
- 新建、重命名拒绝重名和非法名称。
- 当前目录区间选择包含两个端点，不包含其他目录项目。
- 多选点击是幂等切换；长按不误开启多选；普通单击仍进入目录或打开文件。
- 标签去重、dirty 关闭决策和项目切换状态正确。
- `.love` 快照包含项目文件，且根目录存在 `main.lua`。
- 会话状态通过原子写入保存，损坏状态不得破坏项目源文件。
- 文件加载器在 5 MB 阈值、二进制、非法 UTF-8、读取竞态和 LF/CRLF/CR 换行上保持确定行为；外部修改基线不会静默覆盖 dirty 内容。
- `.love`/`.zip` 归档导入导出保留游戏文件，拒绝 ZIP Slip、重复路径、过多条目和超限解压，并要求根目录 `main.lua`。
- `LanguageResolver` 对 TOML 和 GLSL 着色器扩展名返回已注册 scope。
- 终端 Git 在项目路径执行 `git init`、`git add` 和首次提交时，Git 对象可以正常写入 Android 共享存储；版本控制页面初始化仓库后，状态分组、文件级/全部暂存、取消暂存和提交状态与 Git 一致。
- PRoot 启动参数保留 `argv[0]`，仅绑定可访问的应用目录和项目真实路径；已有 rootfs 挂载占位目录为 `000` 权限时仍可生成启动命令，不修改占位目录。DSH 只接受完整换行的 `dsh web:` loopback 认证 URL，分批 token 不提前加载，重启后的最新完整 URL 生效，后台命令使用 `exec` 和 `--no-open`。
- DSH 加载状态覆盖服务等待、`about:blank`、认证重定向、旧 URL 完成回调、错误终止和再次加载。项目软链接覆盖正确目标、重复准备、含空格路径、失效链接修复及同名真实文件/目录保留；主机逻辑测试以真实主机文件系统操作替代 Android `Os` 调用，Android API 23+ 系统调用仍由真机回归。
- 版本控制 Diff 中新增行显示绿色、删除行显示红色，hunk 显示旧/新行号；未跟踪文本文件显示为新增内容，暂存 Diff 与未暂存 Diff 不混淆。
- 项目属性保存后重新打开面板仍一致；使用非默认应用名、包名、版本、方向和权限打包后，成品 Manifest 与属性一致。
- 主题模式解析：亮色和暗色映射正确，跟随系统按系统 `uiMode` 选择应用或编辑器主题；非法持久化值回退到跟随系统。

测试必须不依赖 Android runtime，保持确定、隔离并可在完整测试集中运行。

## 项目模板

```sh
./gradlew :app:testNormalNoRecordDebugUnitTest --tests top.wsdx233.love2droid.StorageAndPackagingTest
```

- 聚焦测试从真实内置模板生成项目：空项目只输出 `main.lua`，不读取基础模板资产，`.love` 快照也不带字体或 `AGENTS.md`；基础项目逐字节检查 `main.lua`、`AGENTS.md`、OTF 和四份许可证在项目目录及 `.love` 快照中保持完整。两种模板缺失入口，以及基础项目缺失字体或代理说明，均须传播 I/O 错误，不能静默跳过或生成伪成功项目。
- 主机已执行 Lua 模板行为检查：`love.load()` 创建一次 24px 字体、设置字体自身的 `nearest` 过滤并启用，重复 `love.draw()` 不重新创建字体。该检查使用图形 API 替身，不等同于 LÖVE 渲染验证；实际 OTF 另经主机 FreeType 加载和中文样例字形栅格化验证。
- 两种内置模板均已在主机真实 Linux LÖVE 11.5、Xvfb 与 Mesa llvmpipe 下完成三帧运行；该 smoke 验证入口可运行，不等同于 Android 页面、输入法或 App 内置 LÖVE 12.0 验证。
- arm64 真机：从项目管理进入新建页面，确认只显示空项目、基础项目且默认选择基础项目；当前分组正确预填。输入信息后反复切换模板、横竖屏及输入法，确认输入与选择保留，页面可滚动、创建按钮可达，亮暗主题对比清晰。未提交时返回不创建目录；空名称、非法或重复 id 不关闭页面、不丢失表单；创建期间重复点击只生成一个项目，成功后直接进入编辑器。
- 空项目：离线创建后只应有 Hello World 的 `main.lua` 与通用的 `conf.lua`、`.luarc.json`、项目元数据（打开后可产生编辑器会话文件），没有 `assets/` 或 `AGENTS.md`；Play、导出 `.love` 与游戏 APK 均可运行，且不夹带基础模板资产。
- 基础项目：离线创建后确认 `assets/fonts/` 包含字体与许可证；把示例绘制文本改为“你好，世界！开始游戏”，Play 后确认中文可读，并观察高 DPI、横竖屏下的清晰度。导出 `.love` 与游戏 APK 后继续验证中文显示；打开旧项目、导入其他项目时，不应自动添加字体或改写入口。
- 基础项目代理说明：打开根目录的 `AGENTS.md`，确认包含 Linux 11.5 / Android 12.0 的版本区别及 `love-check` 命令；空项目、旧项目或导入不含该文件的项目，不应自动补写。文件属于普通项目内容，导出 `.love` 后仍保留。

## DSH 文件系统兼容性

使用 Node.js 22.15+ 和 Linux 主机，测试真实 npm 包而不是模拟 DSH 后端；下载内容仅放在被忽略的 `.proot-debug/`。测试使用 `/tmp` 和 `/dev/shm` 的不同文件系统验证 `EXDEV`，不调用模型 API，也不需要 Android runtime：

```sh
npm install --prefix .proot-debug/dsh-fs-verification --ignore-scripts --no-audit --no-fund --save-exact @deepseek-ai/dsh@0.1.2-rc.1 @deepseek-ai/dsh-fs-local@0.1.2-rc.1 @deepseek-ai/dsh-session-persistence-jsonl@0.1.2-rc.1 @deepseek-ai/dsh-attachment-local@0.1.2-rc.1
node --test tools/dsh-filesystem-compat.test.mjs
./gradlew :app:testNormalNoRecordDebugUnitTest --tests top.wsdx233.love2droid.ProotRuntimeTest --tests top.wsdx233.love2droid.DshDaemonTest --tests top.wsdx233.love2droid.DshWebLoadStateTest
```

- 测试依赖使用 npm 发布的原生预构建包；`--ignore-scripts` 仅用于隔离主机研究依赖的生命周期脚本，不是产品安装或 APK 构建的跳过检查开关。原生依赖加载失败时必须处理依赖问题，不跳过对应测试。可用 `DSH_FS_FIXTURE` 指定已安装同版依赖的其他目录。
- 禁用 Node 硬链接后，未适配 DSH 新建返回 `FS_IO_ERROR/EPERM`，适配后同一路径成功；包含嵌套目录、空文件、Unicode/空格/引号名称和完整大内容。
- 四进程在临时文件写完后同时发布，只能有一个成功；其余 `FS_NOT_OBSERVED`，成功内容完整且无临时目录残留。现有文件、并发出现的目录/悬空软链接均不能被覆盖。
- 覆盖/编辑保留 CRLF 和旧版本保护；取消发生在暂存后时不生成目标；原生调用的 `ENOENT`、`EXDEV` 等错误保留源文件并原样报告，不降级覆盖。Node 自身的硬链接语义不变。
- 首次会话日志提交后可读取，竞争者不能覆盖；附件新建、重复内容去重、读回和损坏对象检测正常，清理接受 rename 已消费临时文件。严格依赖布局的 Koffi 解析不依赖 npm 提升。
- `NODE_OPTIONS` 传递到 worker；上游提交结构不匹配时明确失败。Kotlin 测试覆盖旧环境部署、幂等更新、路径逃逸拒绝和 PRoot 环境参数。
- 已通过主机 PRoot `--link2symlink --root-id` 执行真实 DSH 文件、会话和附件发布 smoke；该结果只验证主机 Linux/PRoot，不等同于 Android FUSE/厂商内核验证。

## DSH Web 文件打开

沿用上节的真实 DSH npm fixture，运行：

```sh
node --test tools/dsh-web-opener.test.mjs
./gradlew :app:testNormalNoRecordDebugUnitTest --tests top.wsdx233.love2droid.EditorFileAccessTest --tests top.wsdx233.love2droid.EditorFileRobustnessTest --tests top.wsdx233.love2droid.StorageAndPackagingTest --tests top.wsdx233.love2droid.ProotRuntimeTest --tests top.wsdx233.love2droid.DshDaemonTest --tests top.wsdx233.love2droid.DshWebLoadStateTest
```

- Node 回归覆盖 WebView 文件请求的 guest realpath、Unicode/空格/引号及 file URI、等待原生确认、多请求乱序回复、取消与插件卸载；普通浏览器、目录、网页及其他 RPC 不改道。激活测试运行真实 DSH CLI，验证首次初始化、重复启动不改写 profile、保留用户设置、重建丢失软链接和保留同名真实目录。
- Kotlin 回归覆盖 guest/app 绑定映射、虚拟设备拒绝、软链接逃逸拒绝、外部标签别名去重且保留 dirty 文本、原路径原子保存、工作区序列化与恢复边界，以及外部文件和编辑器状态不进入 `.love`。
- 主机已启动真实 DSH Web，并在 Chromium 中验证插件被发现、`session/openWorkspacePath` 经过认证 RPC 解析路径并收到桥接确认；浏览器中的 `Love2DroidFiles` 为原生端替身，不代表 Android WebView 或 Sora 标签交互已验证。

## LÖVE 无界面检查

在设置的“环境与扩展组件”中安装“LÖVE 11.5 无界面检查”。安装会固定 `love=11.5-1build1` 并运行三帧真实自检，只有成功后才显示已安装。普通终端、OMP、DSH 共用命令：

```sh
# 环境自检：图片、字体、Shader、音频 API、隔离存档和 llvmpipe
love-check doctor --frames 3 --timeout 60

# 先保存编辑器内容；默认完成 300 次呈现，总超时 30 秒
love-check check "$HOME/projects/我的游戏"
love-check check "$HOME/projects/我的游戏" --frames 120 --timeout 30

# 只检查 LuaJIT 语法，不执行 conf.lua、main.lua 或其他模块
love-check check "$HOME/projects/我的游戏" --syntax-only
```

- 输入为包含根目录 `main.lua` 的目录；不自动保存编辑器标签，不直接接收 `.love` 文件。隐藏文件与现有 Play 快照一样排除；超出项目边界的软链接、目录循环和归档大小上限明确报错。
- `--frames` 为 1–100000；`--timeout` 为 1–600 秒，包含快照、语法检查、显示初始化和游戏执行。帧数不是模拟时间；不把加载画面的呈现计作标准 `love.load` 之后的运行帧。
- 有效命令输出单个 JSON：`status`、`phase`、`frames`、`lua_files`、`elapsed_seconds`、进程日志及截断标记；运行模式还包含 `engine`、`renderer` 或 `error`。JSON 的 `exit_code` 是子进程状态，命令自身退出码见下表。参数错误和 `--help` 使用普通 CLI 文本。

| 命令退出码 | 含义 |
| --- | --- |
| 0 | 语法检查通过，或已完成指定呈现帧并确认 LÖVE 11.5/llvmpipe |
| 1 | 语法、配置、资源、运行、环境或清理错误 |
| 2 | CLI 参数错误 |
| 3 | 游戏提前退出，未完成指定呈现帧 |
| 124 | 超时，不算通过 |
| 130 / 143 | SIGINT / SIGTERM 取消，不算通过 |

主机行为回归使用真实 Linux LÖVE 11.5、LuaJIT、Xvfb 和 Mesa，不使用 Android runtime 或图形 API 替身。Ubuntu 24.04 安装依赖后运行：

```sh
sudo apt-get install --no-install-recommends love=11.5-1build1 luajit libluajit-5.1-2 python3 xvfb libgl1-mesa-dri libglx-mesa0
PYTHONDONTWRITEBYTECODE=1 python3 tools/love-check.test.py
./gradlew :app:testNormalNoRecordDebugUnitTest --tests top.wsdx233.love2droid.ProotRuntimeTest
```

- 主机不需要修改系统安装时，可将上述缺失包下载并解压到被忽略的 `.proot-debug/`，通过 PATH/LD_LIBRARY_PATH 使用；发行版包的 `love` alternatives 入口需指向解压后的 `love-11.5`。不要把主机二进制加入 APK。
- 回归覆盖纯解析不执行代码、未加载 Lua 模块的语法错误、初始化/绘制错误、缺失图片、真实 GLSL 编译错误、配置错误/版本不兼容、提前退出、直接 `os.exit`、加载帧排除、自定义阻塞 run、无限循环、源文件/存档隔离、路径逃逸/循环、日志截断、并发会话、取消后进程回收及显示认证。
- CPU 目标回归覆盖 ARM64 自动启用通用目标、覆盖继承的 `LLVM_CPUINFO`、父进程环境不变，以及其他架构保留原设置；在主机替换架构检测结果后，继续执行真实 `doctor` 和项目渲染，验证设置确实传入子进程。架构检测替身不等同于 ARM64 指令执行验证。
- Kotlin 回归覆盖组件只依赖 rootfs、完成标记与文件完整性、幂等原子部署、更新及路径逃逸拒绝。
- 已在主机 PRoot `--root-id --link2symlink` 下运行真实 `doctor`。Xvfb 直接使用 `-displayfd` 和一次性认证文件，避免 xauth 硬链接锁导致的清理失败；不能通过忽略临时目录清理错误得到成功结果。
- arm64 真机必须确认：组件首次安装/失败重试、`doctor` 返回 llvmpipe、真实项目完成指定帧数、初始化和绘制错误立即失败、死循环返回 124、取消后没有遗留游戏/Xvfb、再次检查存档仍为空。当前本地验证不涵盖 Android 厂商内核、PRoot guest 图形栈和应用后台存活行为。
- 用户真机已确认 `LLVM_CPUINFO=/dev/null love-check doctor --frames 3 --timeout 60` 可将同设备的 `SIGILL` 变为三帧 `passed`。更新兼容修复 APK 后，重新进入组件安装或新建 PRoot 终端，在不手动设置该变量的情况下运行 `love-check doctor --frames 3 --timeout 60`，确认自动设置生效；再检查真实项目。无需把变量写入 `.bashrc` 或更换图形驱动。
- 该检查不替代 App 内置 12.0 的 Play/真机验证，也不承诺未执行路径、画面设计、真实听感或手机 GPU 性能正确。游戏以应用权限运行，临时副本不是恶意 Lua/FFI 的安全沙箱。

### Ubuntu Base 安装与失败恢复回归

`tools/love-check-install.test.py` 在临时 Ubuntu Base 中执行真实 APT、dpkg 和三帧 `doctor`，不修改主机软件包，也不使用包管理器或图形 API 替身。要求 x86_64 Linux、可访问 Ubuntu 软件源，并使用 `tools/proot-debug.sh` 的主机 PRoot 工具；首次运行可能下载该工具的构建依赖。

先下载官方 [Ubuntu Base 24.04.4 amd64 归档](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-amd64.tar.gz) 到被忽略的 `.proot-debug/`。测试自动核对固定 SHA-256，然后运行：

```sh
LOVE_CHECK_TEST_UBUNTU_BASE="$PWD/.proot-debug/ubuntu-base-24.04.4-base-amd64.tar.gz" \
    PYTHONDONTWRITEBYTECODE=1 python3 tools/love-check-install.test.py
```

- 覆盖原始 `path-exclude=/usr/share/man/*` 下全新安装、真实包维护脚本进入 `half-configured` 后的重试、恢复的手册内容完整、dpkg 无遗留异常，以及再次安装不重新解包健康包；原始 `excludes` 文件必须保持不变，其他包的手册仍被裁剪。
- 主机安装回归使用原生硬链接。本机 x86_64 PRoot 的 `--link2symlink` 在更新 `dpkg/status-old` 时遇到独立的 `Operation not permitted`，因此这项安装回归不代表 Android 的硬链接转换已经验证；不修改 App 的 PRoot 参数，实际 arm64 安装与恢复仍由真机确认。
- 真机已有此次 man 手册缺失错误时，覆盖安装修复 APK，然后在“环境与扩展组件”重试“LÖVE 11.5 无界面检查”；确认不再出现 `alternative path` / `No file name`，包配置完成后 `doctor` 返回 `passed`。不要删除 Ubuntu 或项目，也不要创建空手册、跳过 postinst 或吞掉 dpkg 错误。

## APK 构建

修改任何源码、资源、Manifest、Gradle、native/CMake 或 TextMate 资源后，必须执行：

```sh
CMAKE_BUILD_PARALLEL_LEVEL=2 ./gradlew :app:assembleNormalNoRecordDebug
```

成功产物应位于：

```text
app/build/outputs/apk/normalNoRecord/debug/app-normal-noRecord-debug.apk
```

不得通过跳过任务、关闭检查或吞掉异常换取构建成功。只修改 `doc/` 或其他 Markdown 文档时不要求重新构建 APK。

## arm64 真机验证

不使用 Android 模拟器验证界面、输入法、文件系统、PRoot、Ubuntu、LuaLS 或 LÖVE runtime。相关变更需在 arm64 真机观察：

- 首次安装、安装失败重试、应用重启、横竖屏切换、后台恢复。
- Tab 栏在真机上单击文件或终端标签应一次完成切换；即使 OMP 终端连续刷新内容，标签点击仍应及时响应；关闭和新建按钮均可直接操作。
- 打开普通终端，确认登录 shell 不输出 `groups: cannot find name for group ID`；关闭“OMP 使用项目目录”后新建 OMP 标签，确认 OMP 自动恢复当前目录下的第一个可恢复 session，没有可恢复 session 时创建新 session；退出并重启应用后，确认恢复的 OMP 标签仍直接执行 `omp --allow-home --continue`。
- 首次安装以及从旧版本升级后，在普通终端确认 `/etc/resolv.conf` 包含 `8.8.8.8`、`8.8.4.4` 和 `options use-vc timeout:2 attempts:2`；执行 `getent hosts baidu.com` 与 `curl` 域名请求，确认默认命令无需临时设置 `RES_OPTIONS` 即可解析。
- 从项目打开普通终端，确认不再闪退或输出 `proot warning: can't chdir`，`pwd` 为项目真实路径且可读取 `main.lua`；保持一个终端运行，再打开第二个普通终端和 DSH，确认挂载占位目录不影响并行会话。只安装 rootfs、未安装 OMP/LuaLS 的环境中，普通终端仍可打开。
- 打开 DSH Web 标签，确认不会调起外部浏览器，等待服务启动后不显示 `ERR_CLEARTEXT_NOT_PERMITTED` 或未认证错误；切换到文件再返回 DSH，不重新加载或丢失页面状态。停止后台服务并重新打开时使用新的完整认证 URL；加载失败时应看到错误提示。主机 Chromium 的认证 Cookie/页面验证不能替代此 Android WebView 回归。
- DSH 冷启动等待服务和加载文档时，Tab 内容区顶部应显示进度条；认证跳转完成、主页面错误或服务启动准备失败后消失。加载期间切换到文件/终端不显示进度条，切回仍加载的 DSH 时恢复显示，已完成页面不闪现加载条。
- 新安装及旧环境升级后启动 PRoot，确认 `readlink ~/projects` 指向应用项目根目录，`cd ~/projects/<项目>` 可读取 `main.lua`；在 DSH 的工作目录选择器中从 Home → projects 进入项目。同名真实文件/文件夹必须保持原样，不自动覆盖。
- 在终端执行 `dsh plugin --profile web add <可用插件>`，确认不再出现 `ERR_PNPM_ADDING_TO_ROOT`；从插件市场安装插件执行同一 profile 安装路径并成功进入队列。新安装的 profile 应预装 `dsh-web-mobile`，确认 profile 原有 `.npmrc` 其他设置保留，重复启动不会重复追加该配置。
- 升级 APK 后完全退出并重开应用，让普通终端和 DSH 后台进程重新启动；无需删除 rootfs 或重装 DSH。在应用专属项目真实路径及 `~/projects` 入口下，让 DSH 新建 `src/audio.lua`，再读取、编辑并覆盖，确认没有 `link ... EPERM`，文件完整且无 `.tmpdir` 残留。同名文件未经读取不能被静默覆盖；同时回归附件上传/重复上传和新会话关闭后恢复。
- 如果出现 `unsupported ... publication code`，记录实际 DSH 包版本，需要更新适配；如果 `renameat2` 返回不支持/权限错误，记录 Android 版本与目标挂载点，不能用普通 rename、copy 或关闭观察保护绕过。该适配不支持第三方工具在共享存储上任意创建硬/软链接。
- 在 DSH Web 分别点击当前项目文件、其他项目文件及 guest `/root/` 下的文本配置文件：均在同一编辑器工作区打开，项目标题、Drawer 根目录和运行目标不变；重复点击与软链接入口不产生重复标签，dirty 内容仍保留。修改后保存，确认写回原文件且当前项目没有复制件；关闭并重开应用，确认外部标签、选区和未保存内容恢复。
- 真机验证二进制、非 UTF-8、超过 5 MB、缺失文件和越界路径的明确错误；运行/打包当前项目不得包含外部文件。普通终端 `xdg-open`、DSH 目录动作及外部浏览器原行为保持不变；非 loopback 页面或子 frame 不应获得文件打开权限。
- 真机打开 DSH Web 标签，对照桌面浏览器页面：应用内会话标题应紧接 Tab 下方显示，不应出现额外顶部空白；滚动、输入框和移动端插件交互保持可用。
- 真机滚动问题尚未定位：分别记录键盘关闭/打开、竖屏/横屏时的内容区和 WebView 实测高度，并区分文档根滚动、DSH 内部列表滚动与移动插件布局；不要仅凭滚动条存在就隐藏滚动条或强制屏幕高度。
- 新建项目 → 编辑 `main.lua` → Play → LÖVE 渲染 → 返回编辑器。
- 在游戏窗口中分别执行竖屏 → 横屏和横屏 → 竖屏旋转；调试悬浮球均停靠在右侧，纵向位置不跳到底部且仍可拖动和点击。
- 打开游戏调试扳手悬浮球，确认黑色半透明 scrim、白色文字、Material 图标和按压 Ripple；竖屏面板从底部滑出并显示纯图标横向 Tab，横屏面板从左侧滑出并显示左侧纯图标竖直导航，切换页面后旋转仍保持当前页面，关闭后游戏仍可操作。面板入场前不得在最终位置闪现一帧。
- 分别在横屏和竖屏聚焦日志搜索、监视输入和 REPL，确认系统窗口只平移到当前输入框位于虚拟键盘上方，输入框不会被遮挡；锁定日志滚动并持续输出日志，当前输入框焦点和已输入内容不被刷新重置。
- 在控制台页确认日志从内容区顶部开始显示；搜索和筛选默认不占空间，点击左下角放大镜或漏斗后才展开对应控件。确认垃圾桶清空日志，锁定按钮切换自动跟随，右下角箭头发送 REPL，右上角展开按钮可增大和还原多行 Lua 输入区；持续刷新日志时焦点和输入内容保持不变。
- 在监视页添加固定和未固定的 Lua 表达式，确认运行时结果刷新、错误不崩溃；关闭面板后，黑色半透明 HUD 中的固定值仍随游戏状态持续刷新。退出游戏再 Play 同一项目，确认全部表达式和固定状态自动恢复，固定项无需先打开面板即可显示并刷新；项目重命名后状态继续保留，删除后以相同 id 新建项目不得读取旧状态。旋转后 HUD 与悬浮球仍在安全区域。
- 在项目 Lua 编辑器轻触行号，确认亮色主题显示浅红色行号背景、暗色主题显示红色行号背景；插入或删除多行后断点随行迁移，重开项目后仍保留。Play 命中断点后，断点页不显示编辑表单，只显示执行控制、调用栈和只读 Lua 源码；当前暂停行高亮，继续和各单步操作后源码位置同步更新。
- 顶栏标题只显示“调试”，暂停恢复按钮随运行时暂停状态切换图标和功能；断点导航使用 `bug_report` 小虫图标，面板使用 `open_in_full`/`close_fullscreen` Material Symbols 切换全屏，退出游戏和关闭按钮均为图标按钮，点击白色面板内容不会关闭面板。
- 修改多个文件 → 切换标签 → 保存 → 再次 Play，运行内容与最新保存一致。
- 打开非 ASCII 文件名、空目录和大目录。
- 新建、重命名、复制/剪切、粘贴、删除和多选操作不越过项目根目录。
- Android 11+ 不申请广泛存储权限仍能读写应用专属 `projects/`。
- 缺少 `main.lua`、打包失败和 LÖVE runtime 异常显示可理解错误，不崩溃回桌面。
- 文件夹长按菜单和顶部目录菜单的“导入”先选择“文件”或“文件夹”，再打开标准 SAF DocumentsUI；选择外部文件或目录后，文件或目录本身及全部内容复制到目标目录，目标同名时不覆盖。
- 文件夹长按菜单和顶部目录菜单的“导出”均通过 SAF 选择外部目标目录，导出目录本身及全部内容；文件长按菜单的“导出”通过 SAF CreateDocument 写出原文件。确认 `com.google.android.documentsui`/`com.android.documentsui` 可用时选择器不落到国内定制文件选择器。
- 项目管理页面的“导入 `.love` / `.zip` 项目”应在标准 DocumentsUI 中选择归档，导入后自动生成项目并打开；项目长按菜单的“导出为 `.love`”应生成可被 LÖVE 读取的 ZIP，且不包含编辑器元数据。
- 外部终端修改、重命名或删除已打开文件后，切换标签或恢复应用：clean 标签自动更新；dirty 标签显示重新载入/保留编辑内容提示；取消前确认磁盘文件和编辑器内容均仍存在。
- 打开超过 5 MB、二进制和非 UTF-8 文件，确认显示提示且不会创建可保存的乱码标签；TOML、`.vert` 和 `.frag` 文件显示对应 TextMate 高亮，切换编辑器主题后颜色同步变化。
- 多个终端标签独立运行，关闭时终止对应会话；重启后按保存状态重建。
- LuaLS 的补全、诊断、悬浮、签名提示和保存通知在真实项目路径下工作；关闭“LuaLS 悬浮信息”后不再显示浮窗，重新开启后恢复，并在重启应用后保持选择。
- 长按项目内 Lua 符号仍进入默认文本选择；选区操作浮动菜单出现定义和用法图标，点击后分别执行跳转或在 Bottom Sheet 展示结果，点击结果打开正确标签并定位到对应范围。
- 在设置中分别切换应用主题和编辑器主题的亮色、暗色、跟随系统，确认两者可以独立组合；亮色顶栏和状态栏为纯白且标题、返回/导航、action 与状态栏图标为黑色，暗色背景为纯黑且前景为白色，重启应用后主题选择仍保留。
- 在应用暗色主题下逐一打开欢迎页、文件侧栏及其多选操作栏、项目管理、设置、模型设置和组件安装页（包括安装进度与日志），确认没有固定白底、文字与图标清晰、系统导航栏为深色配浅色图标；再切回亮色或跟随系统，确认背景与前景同步恢复。项目列表反复滚动、进入/退出多选、切换分组，确认复用卡片和 Chip 的普通/选中状态不残留浅色背景；同时检查项目搜索、Git、项目属性及打包 Bottom Sheet 的深色背景与文字。
- 分别组合“应用亮色 + 编辑器暗色”和“应用暗色 + 编辑器亮色”，反复新建、关闭、切换及编辑标签，确认标签栏与代码区同色、文字和关闭/新建图标可见，侧栏及欢迎页仍随应用主题；暗色编辑器的标签栏在刷新后不恢复白底。
- 在亮色编辑器中确认代码区、标签栏、快捷输入栏和选区浮动菜单不再固定为黑色；切换“符号栏”后显示状态立即变化且重启后保留。分别对两个文件标签开启/关闭“只读”，确认只能修改非只读标签，切换标签后各自状态不串用，关闭应用后恢复可编辑。确认保存和撤销图标始终直接显示在顶栏，窄屏时标题被压缩而操作按钮不进入 overflow。
- 在项目终端的真实项目路径执行 `git init` → `git add main.lua` → `git commit`，确认 `.git/objects/` 不再报告写入失败。
- 打开版本控制页面初始化仓库，修改多个文件，确认“已暂存”和“更改”两组正确；分别使用文件按钮、全部按钮暂存/取消暂存，打开暂存与未暂存 Diff，确认新增为绿色、删除为红色且行号正确。
- 输入提交消息并提交，确认提交按钮只在有暂存内容时可用，提交成功后状态清空且历史页出现新提交；缺少 Git 身份或提交失败时页面显示错误，不丢失未提交更改。
- 修改项目属性并保存，重新进入打包面板确认摘要更新；生成 APK 后检查应用名、包名、版本、方向、权限和图标均为项目值。
- 点击 LuaLS 悬浮信息中的项目文件链接在应用内打开文件，不触发 `FileUriExposedException`；悬浮窗口外点击会关闭窗口，项目外和无效 `file:` 链接不启动外部 Intent。

## 交付记录

验证结果应准确区分：

- 本地逻辑测试已经执行的行为；
- APK 已成功构建的变体与产物；
- 必须由用户在真机完成、当前尚未观察的交互。

不得把仅完成 APK 构建描述成已经验证真机交互。
