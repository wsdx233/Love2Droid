# Love2Droid 文档

本目录按稳定主题组织项目文档。当前实现、长期约束和未来计划分开维护，避免把所有信息堆入计划文件。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [architecture.md](architecture.md) | 已采用的系统架构、模块边界、数据流和技术约束 |
| [design.md](design.md) | 当前界面布局、交互和编辑器行为 |
| [reference.md](reference.md) | 外部参考项目、来源、版本和借鉴边界 |
| [verification.md](verification.md) | 逻辑测试、APK 构建和真机验证要求 |
| [status.md](status.md) | 已实现能力、已知限制和已确认的非计划事项 |

## 待实现计划

`plan/` 只存放尚未实现且已经确认要实现的事项，每个主题一个文件：

- [项目最近打开时间](plan/project-recency.md)

计划完成或取消后，应从 `plan/` 移除，并同步更新 [status.md](status.md) 及受影响的稳定主题文档。
