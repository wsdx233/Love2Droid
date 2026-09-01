# 当前实现状态

本文记录已经实现的能力、已知限制和已确认决策。未来工作只记录在 [`plan/`](plan/) 中。

## 已实现能力

- LÖVE Android runtime 与编辑器集成在同一 APK；游戏使用独立 `LoveGameActivity` 和 `:game` 进程。
- 应用专属项目目录、项目元数据、项目管理和 Drawer 单目录文件浏览器。
- 文件新建、重命名、复制/剪切、粘贴、删除、详情、多选和同目录区间选择。
- Sora Editor、TextMate 语法注册、文件/终端混合多标签、未命名文档和原子保存。
- `.love` 快照构建、`FileProvider` Content URI 和 Play 启动闭环。
- arm64 PRoot、Ubuntu Base 首次安装、Termux 终端快捷键栏、LuaLS、omp 和 bash-prompt。
- LuaLS 使用项目真实路径工作；新建项目生成 `.luarc.json` 并加载 LuaJIT 与 LuaLS 内置 LÖVE 11.5 API library。
- bash-prompt 使用 `PROMPT_DIRTRIM=1`，终端提示不展示完整 `Android/data` 长路径。

## 会话恢复

每个项目根目录的 `.lovedroid` 通过原子写入保存：

- 打开的编辑器标签、光标和滚动位置；
- 未命名或未保存的编辑文本；
- 当前文件浏览目录；
- 终端标签及其工作目录。

应用下次打开项目时重建终端进程。OMP 命令只在终端子进程 PID 就绪后发送；未启动的恢复标签会在首次选中并创建终端进程时再发送，避免启动输入丢失。OMP 标签在 JSONL 落盘后优先通过对应 PTY 的 terminal breadcrumb 精确记录 session ID，不再依赖固定时间窗口；旧版 OMP 无 breadcrumb 时，在终端输出变化后继续扫描新增的顶层 JSONL 会话文件作为兼容回退。后续使用 `omp -r <id>` 恢复。

## 已确认界面状态

- 标签栏高度为 `24dp`，标签宽度随文件名自适应。
- 文件名超过 15 个字符时显示前 15 个字符和 `...`。
- 标签、关闭按钮和新建按钮使用波纹反馈。
- 活动标签底线使用主题色，其他标签使用灰色。

## 已修正问题

源码侧已经处理：

- 打开文件时编辑内容被覆盖；
- 保存后 dirty 圆点未刷新；
- 游戏退出影响编辑器进程。
- 横竖屏切换不再重建编辑器 Activity；游戏窗口默认支持横竖屏切换，调试悬浮球会在窗口尺寸变化后重新约束位置。

## 待实现计划

以下能力尚未实现，但已经确认需要实现：

- [编辑器文件健壮性](plan/editor-file-robustness.md)：外部修改冲突、换行风格、二进制探测和大文件保护。
- [项目最近打开时间](plan/project-recency.md)：记录并展示项目最近打开时间。

## 已知限制与非计划事项

- PRoot 当前只支持 `arm64-v8a`。
- Android 6.0/7.x 不加载 Sora `editor-lsp`，仅保留 TextMate 编辑能力。
- 标签暂不支持拖拽排序。
- 调试器、Git、云同步、通用插件系统和完整 VS Code 级 IDE 能力不在当前计划范围；不得因“尚未实现”自动把它们写入 `plan/`。

## 验证边界

本地维护执行不依赖 Android runtime 的聚焦逻辑测试和 APK 构建。界面、输入法、PRoot、Ubuntu 文件系统、LuaLS 和 LÖVE runtime 的实际交互必须在 arm64 真机验证，详见 [verification.md](verification.md)。
