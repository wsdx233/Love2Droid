# 当前实现状态

本文记录已经实现的能力、已知限制和已确认决策。未来工作只记录在 [`plan/`](plan/) 中。

## 已实现能力

- LÖVE Android runtime 与编辑器集成在同一 APK；游戏使用独立 `LoveGameActivity` 和 `:game` 进程。
- 应用专属项目目录、项目元数据、项目管理和 Drawer 单目录文件浏览器。项目管理界面采用适配亮暗主题的现代 MD3 设计，支持项目自定义图标加载显示、元数据驱动的分组与分组筛选 ChipGroup、新建/移动/管理分组、右上角“新建”及“导入”快捷操作，并支持长按进入多选状态机进行批量移动分组、批量删除与批量导出为 .love。
- 新项目离线携带固定版本的 Fusion Pixel 12px 等宽 `zh_hans` OTF 和完整随包许可证，模板自动以 24px、`nearest` 过滤启用；创建及字体复制在后台执行，字体随 `.love` 和游戏 APK 分发。已有及导入项目不自动注入字体，来源见 [reference.md](reference.md#fusion-pixel-font)。
- 新项目自动生成中文 `AGENTS.md`，说明 LuaJIT 语法检查、`love-check` 使用流程及 Linux LÖVE 11.5 / Android 12.0 验证边界；只在创建时写入，已有及导入项目保持不变。
- 文件新建、重命名、复制/剪切、粘贴、删除和详情；文件浏览器支持滑动进入多选、再次滑动选择同目录区间，以及底部全选、反选和取消选择操作栏；文件夹和顶部目录菜单支持通过指定 DocumentsUI 的 SAF 导入文件或文件夹并导出，文件支持 SAF 导出；项目管理支持 `.love`/`.zip` 导入和 `.love` 导出。
- Sora Editor、TextMate 语法注册、文件/终端混合多标签、未命名文档和原子保存；打开文件使用后台受限 UTF-8 加载，拒绝二进制、非 UTF-8 和超过 5 MB 的文件，并保留 LF/CRLF/CR 换行风格。AppBar 支持符号栏、当前标签只读和 LuaLS 悬浮信息开关，其中符号栏与悬浮信息状态跨重启保存。
- `.love` 快照构建、FileProvider Content URI 和 Play 启动闭环。
- arm64 PRoot 支持模块化按需安装（Ubuntu Base、LuaLS、OMP、Git、DSH、LÖVE 无界面检查），安装向导仅在应用首次启动时展示一次并支持跳过，设置中提供“环境与扩展组件”管理入口。
- 支持 DeepSeek Harness (DSH) 智能助手：包含 nvm、node、dsh CLI 及指定 Web 插件的安装管理；支持设置后台自启服务并在编辑器中以专用 Web Tab (WebView) 嵌入访问 `http://127.0.0.1:3080`，当服务未启动时提供交互式提示与一键启用。
- DSH 后台会话显式初始化 Terminal emulator，先离线激活应用内置插件，再以 `exec dsh --profile web --no-open --port 3080` 启动；等待完整启动行中的认证 URL 后加载 WebView，避免无 token 请求和半截 token。APK 显式配置 loopback HTTP 许可，认证 token 不写入工作区。
- DSH Web 标签提供等待服务及页面加载的顶部进度条；PRoot 启动自动维护 `~/projects` 项目入口，支持从 WebUI 的 Home 浏览项目，不覆盖同名真实文件或文件夹。
- DSH profile 已自动配置 pnpm workspace root 依赖安装；终端命令和插件市场不再因缺少 `-w` 触发 `ERR_PNPM_ADDING_TO_ROOT`。升级旧环境时由下一次 PRoot 启动自动迁移。
- DSH 新文件、附件和首次会话日志的硬链接发布已替换为不覆盖原子重命名。主机真实 DSH 包回归覆盖禁用硬链接、并发冲突、旧版本保护、取消、临时文件清理及附件完整性；旧环境下次 PRoot 启动自动部署，运行中的 DSH 需重启。实际 Android 存储行为仍需真机回归，不把主机 PRoot 结果视为真机验证。
- 应用内 DSH Web 通过本地 `dsh-love2droid` 插件将文件打开请求交给编辑器。当前项目外的可访问文本文件也在当前工作区复用/新建标签，不切换项目；外部路径、dirty 内容和选区可恢复，文件保存回原位置，不进入当前项目的运行或打包快照。普通浏览器、终端 `xdg-open` 和目录打开未被替换。已完成真实 DSH + Chromium 的桥接路由验证（原生端使用替身）和主机逻辑测试，Android WebView 到原生标签的交互仍需真机回归。
- WebView 高度调查未确认手机屏幕高度误用：布局使用 Tab 下方剩余内容区；同版 DSH 原生 WebUI 在主机 Chromium 的 400/640/780/915px 视口下，文档滚动高度均等于视口高度。用户真机滚动条来源尚未定位，本次不改高度、Insets 或滚动样式；主机结果不涵盖真机 WebView 和移动插件组合。
- LuaLS 使用项目真实路径工作；新建项目生成 `.luarc.json` 并加载 LuaJIT 与 LuaLS 内置 LÖVE 11.5 API library。
- 已提供 `love-check doctor`、`love-check check <项目目录>` 和 `--syntax-only`：固定 Linux LÖVE 11.5，真实 Xvfb/Mesa llvmpipe 渲染，有限呈现帧、原文件名错误堆栈、提前退出/超时/取消区分，源码与测试存档隔离。主机真实 LÖVE 和 PRoot `--root-id --link2symlink` 已完成图形/音频自检；不修改 App 内置的 12.0 runtime，手机安装与渲染仍需真机回归。
- 已修复 Ubuntu Base 裁剪 LÖVE man 手册导致组件安装报 `alternative path ... doesn't exist`：新安装保留必要目录与手册，旧半配置安装重试时自动下载并重新解包固定版本 `love`。主机隔离 Ubuntu Base 的完整安装及半配置恢复均已完成真实 `doctor`；Android 上更新 APK 后直接重试组件安装，无需清空 Ubuntu 或项目，真机结果仍需确认。
- ARM64 检查自动使用 LLVM 通用 CPU 目标，修复已确认的 llvmpipe `SIGILL` 兼容问题。用户真机对照：原配置返回 `exit_code=-4`，手动设置 `LLVM_CPUINFO=/dev/null` 后三帧 `doctor` 返回 `passed`，renderer 为 Mesa 25.2.8 / LLVM 20.1.2 / llvmpipe（128 bits）。此结果验证了该设备上的规避方式；更新 APK 后的自动安装流程和实际项目仍需真机回归。
- Lua 文件支持长按选择符号后，在文本选区操作浮动菜单中转到定义和查找用法；结果在 Bottom Sheet 中显示并在项目内安全跳转，LuaLS 悬浮 `file:` 链接不再交给外部 Intent，点击窗口外区域会关闭悬浮窗口。
- 应用和编辑器主题均支持亮色、暗色和跟随系统；应用顶栏与状态栏固定为亮色纯白/暗色纯黑，并使用对应的黑/白标题、导航及 action 图标，编辑器主题使用 `quietlight` 或 `darcula` 并独立于应用主题解析。
- 欢迎页、侧栏及多选操作栏、项目管理、设置、模型设置和组件安装页的背景随应用主题切换；项目卡片与分组控件的普通/选中状态同步适配。标签栏背景、文字、分隔线和关闭/新建图标跟随编辑器颜色方案，不再在刷新标签时覆盖为白底；应用主题按夜间资源选择背景和系统栏图标，统一保留字体及基础主题配置。
- 选区浮动菜单中的定义和用法按钮跟随编辑器颜色方案；点击时保留符号位置，关闭菜单后执行 LuaLS 导航。
- 项目搜索支持文件、文本和符号三类查询，提供普通/正则模式、LuaLS 语义结果与本地降级结果；版本控制面板支持页面初始化仓库、按 VS Code 结构查看已暂存/未暂存文件、文件级和全部暂存/取消暂存、提交消息与提交，以及带旧/新行号的红绿 Diff。
- 项目属性可编辑应用名、包名、版本、方向、权限和图标；Android APK 发布会重新读取最新项目元数据，写入 `game.love`、Manifest 和图标后签名，并提供分享/安装。
- bash-prompt 使用 `PROMPT_DIRTRIM=1`，终端提示不展示完整 `Android/data` 长路径。
- 游戏调试悬浮球、固定监视 HUD 和自适应调试控制台已实现；游戏窗口使用 Material 3 主题，黑色半透明悬浮层配白色 Material 图标和 Ripple 反馈，面板在横屏从左侧滑出、竖屏从底部滑出，并通过 `Window.adjustPan` 把当前输入框抬到输入法上方。竖屏使用纯图标 `TabLayout`，横屏使用纯图标竖直 `NavigationRailView`。控制台日志顶对齐显示，搜索和筛选按需展开；描边 REPL 底栏集成搜索、筛选、清空、滚动锁定、输入区展开和右侧发送操作。监视表达式与固定状态按项目持久化，每次 Play 自动恢复，固定项在面板关闭后仍持续刷新 HUD。Lua 编辑器支持点击行号切换项目持久化断点，Play 时传入 runtime；断点页提供执行控制、调用栈及高亮当前执行行的只读源码视图，不再编辑断点。

## 会话恢复

每个项目根目录的 `.lovedroid` 通过原子写入保存：

- 打开的编辑器标签、光标和滚动位置；
- 未命名或未保存的编辑文本；
- 当前文件浏览目录；
- 终端标签及其工作目录。

应用下次打开项目时重建终端进程。OMP 标签不再由应用记录或恢复 session ID；每次启动 OMP 都直接执行 `omp --allow-home --continue`，由 OMP 自己选择当前工作目录下第一个可恢复 session（通常为最近 session），没有可恢复 session 时创建新 session。未启动的恢复标签会在首次选中并创建终端进程时再发送命令，避免启动输入丢失。

## 已确认界面状态

- 标签栏高度为 `24dp`，标签宽度随文件名自适应。
- 文件名超过 15 个字符时显示前 15 个字符和 `...`。
- 标签、关闭按钮和新建按钮使用波纹反馈。
- 活动标签底线使用主题色，其他标签使用灰色。

## 已修正问题

源码侧已经处理：

- 打开文件时编辑内容被覆盖；
- 保存后 dirty 圆点未刷新；
- 游戏退出影响编辑器进程；
- 外部修改或删除已打开文件时，clean 标签自动重载，dirty 标签在切换/保存/恢复时要求明确选择重新载入或保留编辑内容，暂停自动保存不会盲目覆盖外部内容。
- 文件打开在后台完成大小、二进制和严格 UTF-8 检测；5 MB 以上和不可安全编辑的文件不会创建文本标签。
- 保存会保留已识别的 LF、CRLF 或 CR 换行风格；TextMate 已扩展 TOML 和 GLSL（`.vert`/`.frag` 等）grammar，并按编辑器主题设置加载 `quietlight`/`darcula`。
- `.love`/`.zip` 项目归档导入检查 `main.lua`、路径越界、重复项和解压上限；导出沿用项目打包过滤规则。
- 横竖屏切换不再重建编辑器 Activity；游戏窗口恢复 LÖVE/SDL 原生方向语义，不再强制视为可调整大小；调试悬浮球在横竖屏双向旋转后停靠在右侧，并保持旋转前的纵向比例。
- 终端 PRoot 注入 Git `core.createObject=rename`，避免 Android 共享存储上的硬链接对象写入失败；打包流程在开始构建时重新读取项目属性，避免使用旧快照。
- PRoot guest 使用静态公共 DNS，并通过 glibc `use-vc` 强制走真机已验证可用的 TCP DNS，避开失败的 UDP 53 路径；旧安装会在配置阶段自动修复 `/etc/resolv.conf`。
- PRoot 启动不再向 rootfs 内的挂载占位目录递归 `mkdirs()`，避免占位目录权限为 `000` 时触发 `Guest working directory cannot be prepared` 闪退；改为绑定应用可访问目录并由 PRoot 准备临时 glue。终端保留 `execvp()` 所需的 `argv[0]`，普通终端与 DSH 不依赖可选 OMP/LuaLS；启动准备失败显示错误而不退出编辑器。
- DSH WebView 等待完整认证 URL 后再发起 HTTP 请求，标签切换不重复认证。主机同版 DSH 已验证 token → Cookie → 主界面流程；Android WebView 网络策略和真机共享存储行为仍按验证文档回归。
- LuaLS 悬浮窗口点击 `file://` 链接不再因 `FileUriExposedException` 崩溃。

## 待实现计划

以下能力尚未实现，但已经确认需要实现：

- [编辑器文件健壮性](plan/editor-file-robustness.md)：外部修改冲突、换行风格、二进制探测和大文件保护。
- [项目最近打开时间](plan/project-recency.md)：记录并展示项目最近打开时间。

## 已知限制与非计划事项

- PRoot 当前只支持 `arm64-v8a`。
- Android 6.0/7.x 不加载 Sora `editor-lsp`，仅保留 TextMate 编辑能力。
- 标签暂不支持拖拽排序。
- 新项目像素字体不保证覆盖全部生僻字；24px 与高 DPI 下的实际显示效果仍需真机确认。模板只设置当前绘制字体，不改变引擎内置字体或提供自动系统字体兜底。
- 调试控制台的真实 LÖVE/SDL 交互、输入法表现和断点语义仍需在 arm64 真机确认；完整 VS Code 级 IDE 能力不在当前计划范围。
- 无界面检查仅覆盖 Linux LÖVE 11.5 实际执行到的路径；不判断画面是否符合设计、完整游戏逻辑、真实听感、手机 GPU 性能或 Android 生命周期。它不提供安全沙箱，也不自动读取未保存编辑器内容或控制游戏输入。

## 验证边界

本地维护执行不依赖 Android runtime 的聚焦逻辑测试和 APK 构建。界面、输入法、PRoot、Ubuntu 文件系统、LuaLS 和 LÖVE runtime 的实际交互必须在 arm64 真机验证，详见 [verification.md](verification.md)。
