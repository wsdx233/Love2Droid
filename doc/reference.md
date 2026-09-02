# 外部参考

本文记录参考项目、来源、版本和允许借鉴的范围。`ref/` 仅保存本地研究副本，不属于产品源码，也不提交 Git。

## LÖVE Android

- 本地目录：`ref/love-android/`
- 上游：<https://github.com/love2d/love-android>
- 子模块：已递归拉取 `love` 与 `megasource`
- 参考范围：`GameActivity`、SDL/NDK/CMake 构建、native library 加载顺序、Content URI 启动和 Android Manifest
- 使用边界：保留上游 runtime 结构，只在产品启动参数和生命周期边界做最小适配；不把研究副本直接当产品代码

## Sora Editor

- 本地目录：`ref/sora-editor/`
- 上游：<https://github.com/Rosemoe/sora-editor>
- 参考范围：`CodeEditor` 初始化、TextMate 注册、语言资源、`editor-lsp` 和 Java 17 配置
- 使用边界：只接入产品需要的 editor、language-textmate 和 editor-lsp 能力；不复制 Demo Activity、测试页面和无关资源

## r2droid

- 本地目录：`ref/r2droid/`
- 上游：<https://github.com/wsdx233/r2droid>
- 参考提交：`f1c63598688027dcbf4404c27ef14a3893ccb9e1`
- 参考范围：Termux `TerminalView`/`TerminalSession`、双行快捷键栏、PRoot 启动参数和安装进度日志
- 使用边界：只借鉴与终端和 PRoot 集成直接相关的实现，不复制无关产品代码

## AndroLua Pro

- 上游：<https://github.com/nirenr/AndroLua_pro>
- 参考提交：`abe5c25148a60ee44ddcccc212db6b96e836c793`
- 许可证：MIT
- 参考范围：项目发布属性、权限选择、APK 归档重写和签名交互。
- 使用边界：只参考流程与交互，不复制旧版打包器、过时 Android 支持代码或签名文件；成品使用当前 Love2Droid 构建生成的最小资源模板和同版本 LÖVE runtime。

## Android apksig

- 来源：Google Maven `com.android.tools.build:apksig:9.3.1`。
- 参考范围：Android APK Signature Scheme v1/v2 生成。
- 使用边界：签名私钥由 AndroidKeyStore 持有或从用户选择的 PKCS#12 导入；项目元数据、日志和发布 APK 均不保存密码或私钥。

## Material Symbols

- Android 产品图标统一从 Google Material Symbols 获取，默认使用 Rounded、未填充、weight 400；确有强调层级时才使用 `--fill` 或调整 `--weight`。
- 新增或替换图标必须使用 `npx add-material-symbols`，禁止手绘路径、复制来源不明的 Vector Drawable 或使用 `android.R.drawable`。常用命令：

```sh
# Rounded
npx add-material-symbols --style rounded home search

# Filled
npx add-material-symbols --fill favorite

# Rounded + filled
npx add-material-symbols --style rounded --fill home favorite settings

# weight 300
npx add-material-symbols --weight 300 star

# 直接输出 Android drawable
npx add-material-symbols -o app/src/main/res/drawable home search settings favorite
```

- 运行命令后只提交实际使用的 XML，并通过资源引用搜索移除被替换的旧图标。命令无法解析包或图标名时应先确认 npm 包源和 Material Symbol 名称，不得猜测 path data。

## 许可证要求

- LÖVE、SDL、Termux 组件、参考项目和第三方 TextMate grammar 的上游许可证必须保留。
- grammar 的来源、版本或 commit 和许可证应记录在第三方声明中。
- 不把 VS Code 等项目的语法资源视为无许可证内容复制。
- `ref/` 中的 Demo、测试资源和研究副本不进入产品源码或发布包。
