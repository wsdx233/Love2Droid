# Android 游戏属性与打包

状态：尚未实现，准备实现。

## 范围

在 Drawer 项目标题右侧增加项目属性和打包入口。项目属性写入现有项目元数据；打包基于官方 LÖVE Android 11.5a embed APK 模板修改并重新签名，不在手机上运行 Gradle/NDK。

## 数据与依赖

- 官方模板：`love-11.5-android-embed-norecording.apk`，发布页 `love2d/love-android` tag `11.5a`，运行时下载并缓存，SHA-256 固定为 `dcf71c1b54c5b5a09598ef1e6cf4852ced5e5e612de3d0f30cfdd39b5014e889`。
- 二进制 Manifest 使用 `io.github.reandroid:ARSCLib:1.4.0` 修改。
- APK 使用 Android 端口 `com.github.MuntashirAkon:apksig-android:4.4.0` 生成并验证 v1/v2 签名。
- 不提交 APK 模板、keystore 或其他签名文件。默认调试身份在首次使用时生成到 AndroidKeyStore；设置页可导入 JKS/PKCS#12，密码只在验证和打包时输入，不写入项目或偏好设置。

## 项目属性

- 编辑应用名称、包名、版本名、版本代码、图标和屏幕方向。
- 默认权限遵循官方模板的基础运行需求；录音权限保持显式可选。
- 提供常用权限多选和符合 Android 权限命名规则的自定义权限；不接受任意原始 XML 注入。
- 属性验证、持久化和 UI 分离；图标复制到应用私有的项目资源目录，项目重命名和删除时同步处理。

## 打包流程

1. 保存所有可落盘标签并校验 `main.lua`。
2. 由 `LovePackageBuilder` 生成 `game.love` 快照。
3. 下载或复用已校验模板；移除旧签名项，写入 `assets/game.love`。
4. 修改包名、应用名、版本、方向和权限；按官方密度路径替换图标。
5. 使用默认调试身份或导入身份签名，并用 `ApkVerifier` 验证。
6. 输出到应用缓存，通过 `FileProvider` 提供“分享”和“安装”；安装入口处理 Android 8+ 未知来源授权。

## 验收

- 属性保存后重启仍一致，非法包名、版本代码、权限名和图标得到可理解错误。
- 模板下载必须通过固定 SHA-256；校验失败、网络失败、签名密码错误和空间不足不留下伪成功 APK。
- 生成 APK 内含最新 `game.love`、修改后的 Manifest 和图标，且签名验证通过。
- 分享 Intent 只暴露目标 APK 的临时 Content URI；安装操作不使用 `file://`。
- 打包仓库、Manifest 编辑、归档重写、签名存储和 Bottom Sheet UI 相互独立。
