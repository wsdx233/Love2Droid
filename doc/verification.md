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

测试必须不依赖 Android runtime，保持确定、隔离并可在完整测试集中运行。

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
- 新建项目 → 编辑 `main.lua` → Play → LÖVE 渲染 → 返回编辑器。
- 修改多个文件 → 切换标签 → 保存 → 再次 Play，运行内容与最新保存一致。
- 打开非 ASCII 文件名、空目录和大目录。
- 新建、重命名、复制/剪切、粘贴、删除和多选操作不越过项目根目录。
- Android 11+ 不申请广泛存储权限仍能读写应用专属 `projects/`。
- 缺少 `main.lua`、打包失败和 LÖVE runtime 异常显示可理解错误，不崩溃回桌面。
- 多个终端标签独立运行，关闭时终止对应会话；重启后按保存状态重建。
- LuaLS 的补全、诊断、悬浮、签名提示和保存通知在真实项目路径下工作。

## 交付记录

验证结果应准确区分：

- 本地逻辑测试已经执行的行为；
- APK 已成功构建的变体与产物；
- 必须由用户在真机完成、当前尚未观察的交互。

不得把仅完成 APK 构建描述成已经验证真机交互。
