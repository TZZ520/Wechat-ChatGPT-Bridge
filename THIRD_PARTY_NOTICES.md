# 第三方组件与素材说明

本仓库 `LICENSE` 中的 MIT 许可仅适用于本项目有权授权的自有源码和文档，不改变下列第三方组件的许可、商标和其他权利。各组件的完整许可和适用范围以其上游发布内容为准。

| 组件 | 本项目中的用途 | 上游与许可 |
| --- | --- | --- |
| Gradle Wrapper | `gradlew`、`gradlew.bat` 与 `gradle/wrapper/gradle-wrapper.jar` 用于下载和启动构建工具 | [Gradle](https://github.com/gradle/gradle)，[Apache License 2.0](https://github.com/gradle/gradle/blob/v8.10.2/LICENSE)，脚本保留了上游版权和许可声明 |
| Android Gradle Plugin | 构建插件 `com.android.application:8.7.2`，构建时下载 | [Android Gradle Plugin](https://developer.android.com/build/releases/about-agp)；适用许可见组件及其发行包 |
| Xposed API | `de.robv.android.xposed:api:82`，仅供编译引用，运行实现由用户自行安装的框架提供 | [XposedBridge](https://github.com/rovo89/XposedBridge)、[API 使用说明](https://github.com/rovo89/XposedBridge/wiki/Using-the-Xposed-Framework-API)、[上游 NOTICE](https://github.com/rovo89/XposedBridge/blob/master/NOTICE.txt)（主产品 Apache License 2.0；其中包含的其他组件以各自声明为准） |
| JUnit 4 | `junit:junit:4.13.2`，仅用于单元测试 | [JUnit 4](https://github.com/junit-team/junit4)，[Eclipse Public License 1.0](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |
| JSON-java | `org.json:json:20240303`，仅用于单元测试 | [JSON-java](https://github.com/stleary/JSON-java/tree/20240303)，[该版本许可声明：Public Domain](https://github.com/stleary/JSON-java/blob/20240303/LICENSE) |

运行环境需要用户自行获取并配置微信、微 X（也称微叉、微插；包名 `cn.android.x`）及兼容的 Xposed/LSPosed 框架。这些软件均由各自权利人提供，本仓库不转载微信、微 X、原修复模块或其安装包。可从 [LSPosed 模块目录中的 X 条目](https://modules.lsposed.org/module/cn.android.x/)查找微 X 的发布信息；请自行核验来源、版本和适用条款。

原工程部分自测图片与语音素材的公开再分发权属尚未确认，本公开目录未收录这些素材。相关媒体自测因此可能不可用；这不代表真实媒体自动回复已完成当前版本的完整回归。若后续补充素材，应先核验权属并说明来源。
