# 第三方依赖与分发说明

本项目自身代码使用根目录的 MIT 许可证（LICENSE）。第三方库及其子组件仍遵循各自的许可；本项目的 MIT 许可证不替代第三方条款。

本次核对范围是 dependencies.lock.json 锁定的 13 个原始二进制 JAR，核对日期为 2026-09-03。文件 SHA-256 均与清单一致；项目没有修改、合并或删减这些依赖 JAR。

## 依赖与许可材料

### Apache POI / POI OOXML / OOXML Lite（3 个 JAR）

- 版本：5.5.1
- 主要许可及补充说明：Apache-2.0；保留其 LICENSE 中的 MIT、OOXML 等子组件说明及全部 NOTICE
- 原始二进制中的材料：各 JAR 的 META-INF/LICENSE、META-INF/NOTICE

### Apache XMLBeans

- 版本：5.3.0
- 主要许可及补充说明：Apache-2.0；保留随附的 W3C 等说明
- 原始二进制中的材料：META-INF/LICENSE.txt、META-INF/NOTICE.txt

### Apache Commons Compress

- 版本：1.28.0
- 主要许可及补充说明：Apache-2.0
- 原始二进制中的材料：META-INF/LICENSE.txt、META-INF/NOTICE.txt

### Apache Commons IO

- 版本：2.21.0
- 主要许可及补充说明：Apache-2.0
- 原始二进制中的材料：META-INF/LICENSE.txt、META-INF/NOTICE.txt

### Apache Commons Codec

- 版本：1.20.0
- 主要许可及补充说明：Apache-2.0
- 原始二进制中的材料：META-INF/LICENSE.txt、META-INF/NOTICE.txt

### Apache Commons Collections

- 版本：4.5.0
- 主要许可及补充说明：Apache-2.0
- 原始二进制中的材料：META-INF/LICENSE.txt、META-INF/NOTICE.txt

### Apache Commons Math

- 版本：3.6.1
- 主要许可及补充说明：Apache-2.0；另保留 MINPACK、BSD 类条款及相关署名
- 原始二进制中的材料：META-INF/LICENSE.txt、META-INF/NOTICE.txt

### SparseBitSet

- 版本：1.3
- 主要许可及补充说明：Apache-2.0
- 原始二进制中的材料：未附独立许可正文，已从上游补齐

### curvesapi

- 版本：1.08
- 主要许可及补充说明：BSD-3-Clause；内含 Apache Harmony Crossing 类，适用 Apache-2.0
- 原始二进制中的材料：未附独立许可正文，已从上游补齐

### Apache Log4j API / Core（2 个 JAR）

- 版本：2.24.3
- 主要许可及补充说明：Apache-2.0；保留随附署名
- 原始二进制中的材料：各 JAR 的 META-INF/LICENSE、META-INF/NOTICE


完整原文汇总保存在 THIRD_PARTY_LICENSES.txt（src/main/resources/META-INF/THIRD_PARTY_LICENSES.txt），包含：

- 11 个附带许可文件的 JAR 中全部 22 份 LICENSE/NOTICE 正文，不以通用许可模板替换组件自己的文本。
- SparseBitSet 1.3 上游标签的 LICENSE，以及对应源码中的 Paladin Software International、Sun Microsystems 和作者署名。
- curvesapi 1.08 上游标签的 BSD 许可原文、源码中 Harmony 类的许可/作者说明、Apache 2.0 正文和 Harmony 上游 NOTICE。
- 每个依赖的文件名、下载地址和锁定 SHA-256；补充文件的上游来源也写在原文旁。

原文仅统一换行，保留上游条款和署名。部分上游正文还说明其源码发行包中的工具或其他子组件；为避免遗漏保留完整文本，这不表示本项目额外打包或使用了这些组件。

依 Commons Math 所附 MINPACK 条款保留以下致谢：

This product includes software developed by the University of Chicago, as Operator of Argonne National Laboratory.

## 材料来源

- 依赖 JAR 和对应版本源码包：Maven Central（https://repo.maven.apache.org/maven2/），具体二进制地址与校验值见 dependencies.lock.json。
- SparseBitSet：1.3 标签 LICENSE（https://github.com/brettwooldridge/SparseBitSet/blob/SparseBitSet-1.3/LICENSE）；署名取自同版本源码包中的 com/zaxxer/sparsebits/SparseBitSet.java。
- curvesapi：1.08 标签 license.txt（https://github.com/virtuald/curvesapi/blob/1.08/license.txt） 与 上游许可说明（https://github.com/virtuald/curvesapi/tree/1.08#licenses）。Harmony 类的许可头取自 1.08 源码包的 com/graphbuilder/org/apache/harmony/awt/gl/Crossing.java。
- Apache Harmony：官方源码镜像 NOTICE（https://github.com/apache/harmony/blob/trunk/NOTICE）。作为 curvesapi 所含 Harmony 代码的补充材料保留，不分发 Harmony 运行时。
- 条款依据：Apache License 2.0 第 4 节（https://www.apache.org/licenses/LICENSE-2.0.html#redistribution）、各组件自带的 LICENSE/NOTICE 和上述 curvesapi BSD 原文。Apache 项目也可能包含其他许可的子组件，参见 Apache 许可 FAQ（https://www.apache.org/foundation/license-faq.html）。

## 如何分发

### 发布项目源码

- 应保留的许可材料：根目录 LICENSE、本说明、dependencies.lock.json 和 src/main/resources/META-INF/THIRD_PARTY_LICENSES.txt；它们均需提交仓库

### 只分发应用 JAR，依赖启动时下载

- 应保留的许可材料：分发本项目构建出的完整 TemplateTool.jar；上述许可证、说明、原文汇总及锁定清单已包含在包内，不要裁剪

### 附带 lib/ 离线分发

- 应保留的许可材料：完整应用 JAR 加锁定版本的完整依赖 JAR；保留依赖自身的 LICENSE/NOTICE，同时保留应用内为缺失许可的两个库补充的材料


根目录维护本 Markdown 说明；主构建自动转换为纯文本 THIRD_PARTY_NOTICES.txt，供程序内显示。转换文件仅存放于构建临时目录，打包完成后自动清理。

应用 JAR 内的许可材料位置：

- LICENSE：本项目 MIT 许可。
- THIRD_PARTY_NOTICES.txt：组件清单、来源、分发说明及致谢。
- META-INF/THIRD_PARTY_LICENSES.txt：完整第三方许可和署名原文。
- dependencies.lock.json：依赖版本、来源与完整性校验值。

运行后可在主界面右上角“关于” → 第三方依赖 / 许可原文中查看，不需要联网。自动下载只改变依赖的交付方式，并不免除许可、版权或署名要求；不要仅复制缺少许可的单个上游 JAR 来代替完整发布包。

## 后续维护边界

主构建会核对锁定依赖与许可汇总中的文件名、SHA-256、来源，并检查原始 JAR 的 LICENSE/NOTICE 正文未遗漏。测试还会核对最终 JAR 中的原文和补充署名。更新或新增依赖时，应重新核对并同步本说明与原文汇总，再构建发布。

当前仅引用未修改的上游二进制。本次检查未发现要求本项目因这些引用而改用另一种开源许可证的条款；若以后修改依赖、复制其他第三方源码、重打包依赖或附带 Java 运行时，需针对新增内容另行核对。本项目当前不分发 Java/JDK 安装包，其许可不在本次附件清单内。

组件名称仅用于来源识别，不表示获得上游作者或组织背书。本清单是针对当前文件的工程核对记录，不是对所有使用、销售或分发场景的法律保证。
