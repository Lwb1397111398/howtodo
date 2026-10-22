# 工作如何做（howtodo）— 项目长期记忆

## 工程位置（唯一有效）

- 代码：`E:/AI Agent/work area/howtodo`（包名 `com.howtodo`）
- 旧目录 `E:/AI Agent/work area/工作如何做APP/howtodo` **已废弃**（上级目录含中文，导致 Gradle 单元测试
  classpath argfile 编码错乱）。不要再去那里改代码。
- 交付物：`dist/工作如何做-v0.1.1-release.apk`（1.7MB）、`dist/工作如何做-v0.1.1-debug.apk`
  （v0.1.0 为旧包，versionCode=1，保留可回滚）

## 构建（本机环境特殊，别用默认方式）

```bash
cd "E:/AI Agent/work area/howtodo"
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export GRADLE_USER_HOME="E:/gradle-home"
export ANDROID_HOME="E:/engine/androidstudioSDK"
"E:/gradle-8.9/bin/gradle" :app:assembleDebug --console=plain --no-daemon --max-workers=1
```

或直接 `build.cmd assembleDebug`（脚本内已设好全部环境变量）。

- 内存仅 7.3GB，**必须** `--no-daemon --max-workers=1`，gradle.properties 已限制
  `-Xmx1280m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`，否则 JVM 原生内存崩溃。
- 版本矩阵：Gradle 8.9 / AGP 8.7.3 / Kotlin 2.0.21 / Compose 1.7.5 / Material3 1.3.1 / Room 2.6.1。
- 签名：`app/keystore/howtodo.jks`，alias / storepass / keypass 均为 `howtodo`。
- `signingConfigs` 块必须写在 `buildTypes` 之前（Kotlin DSL 自上而下求值）。

## 技术约定

- 中文检索：SQLite FTS5 + 自建 bigram（零 embedding 成本），运行时探测 FTS5，失败降级 LIKE。
- 增量分析：补丁式（add/update/cancel），不重跑全量。
- 防幻觉：日期必须有 `raw_text` 原文依据，引用必须逐字回验，对不上标「需确认」；变更走提案 → 逐条确认 → 可撤销。
- 不引入的库（缓存缺失会卡构建）：Coil、pdfbox、kotlinx-serialization、Hilt、security-crypto、DocumentFile、Accompanist。
- **文档文本提取统一入口 `ingest/DocumentExtract`**：先看魔数（zip / OLE2 / `{\rtf` / HTML / UTF-16 BOM）再看扩展名，
  因为材料在私有目录里存成 `<id>.bin`，且很多「.doc」其实是 RTF 或网页。
  老格式自己解析：`CfbReader`（OLE2）+ `LegacyOffice`（doc 片段表 / xls BIFF8 / ppt 记录树）+ `RtfReader`，
  **不要引入 Apache POI**（10MB+，本机缓存没有）。
- 解析旧 Office 的关键编码坑：doc 压缩片段按文档代码页（`\x05SummaryInformation` 的 PID_CODEPAGE），
  xls SST 的 CONTINUE 续记录首字节是新的编码标志，RTF 的 `\'hh` 必须成对攒字节再解码。
- 真实样本冒烟测试：`HOWTODO_SAMPLE_DIR=<含 doc/xls 的目录>` 跑 `:app:testDebugUnitTest`，
  会校验字数与汉字数量，防止「能解析但全是乱码」。

## 已知未验证项

- 真机/模拟器回归未做：本机 `C:\Users\李文彬\.android\avd` 下是坏的 ARM AVD 配置，模拟器 FATAL 退出；
  可用 AVD 在 `E:/AndroidAVD/avd`（属用户个人目录，未擅自改动）。
