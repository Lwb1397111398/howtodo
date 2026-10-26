# AI 调用层模块总览

> 对应代码目录：`app/src/main/java/com/howtodo/ai/`

## 模块职责

把「用户配置的任意 OpenAI 兼容供应商」变成应用里按角色分工的模型能力：解析材料 → 产出任务提案，问答改写关键词，识图转写，全部走统一的路由与客户端，业务代码不写死模型名。

## 运作流程（按数据流动顺序）

### 1. 选模型（AiRouter.kt）

所有 AI 调用先经 `AiRouter.target(role)`：按角色查 `role_binding` 表绑定（vision/reasoning/fast/chat），该角色没绑定时沿回退链找（如视觉→推理→问答）；每级取供应商行，`ProviderRepo.apiKeyOf()` 解密 Key——**没存 Key** 换下一级，**有密文但解不开**（换机迁移/Keystore 被清）抛 `KeyBroken` 明确报错。产出 `Target`（baseUrl/apiKey/modelId/上下文预算/附加头）。

### 2. 发请求（LlmClient.kt）

`LlmClient.chat()` 是挂起函数：请求体按 OpenAI Chat Completions 组装（多模态内容用 base64 data URL），阻塞调用跑在 IO 线程并响应协程取消（`runInterruptible`）。内置三层兼容处理：`response_format` 不被支持→去掉重试；`max_tokens`「不被支持」→换 `max_completion_tokens` 重试（判断要求两者同时成立，防误切）；正文为空取 `reasoning_content` 兜底。可重试错误（408/429/5xx）指数退避，**429 优先按服务端 Retry-After 等（封顶 30 秒）**。

### 3. 组提示词（Prompts.kt + TokenBudget.kt）

- `Prompts.BASELINE_SYSTEM / PATCH_SYSTEM`：基线抽取（完整清单）与增量补丁（只输出差异）两套系统提示词，硬性要求「日期必须有原文依据、证据逐字摘录、只输出 JSON」；今天的日期只出现在 user 消息里（旧版系统提示词里的 `{today}` 死占位符已删）。
- `TokenBudget.estimate()`：CJK（含中文标点、全角符号）按 0.75 token/字、ASCII 按 0.25 估算；`usable()` 按模型上下文预算算可装材料的空间；`truncate()` 保留首尾截断超长文本。

### 4. 装上下文（ExtractionService.renderContext）

每个片段带 `[C{id}]` 编号，按预算多源轮转装包；单块超预算（OCR 长输出）用 `truncate` 压回预算内（证据回查仍用原文）。分析跑在**应用级作用域**（`AppContainer.startAnalysis`），用户离开页面不中断；进程被杀留下的 EXTRACTING 批次由启动时 `failStaleBatches()` 置 FAILED，用户可重新分析。

### 5. 解析响应 → 提案

`JsonExtract.objectFrom()` 从模型文本里抠出 JSON 对象；`SchemaParser` 把它解析成基线/补丁的强类型结构；`PatchEngine.buildBaseline/buildPatch` 与现有任务比对生成 `ProposalItemEntity` 列表（含证据引用）；`ExtractionService.analyze()` 最后创建提案（batch 置 PROPOSED），应用与否由用户在提案页决定（写路径见数据层总览，已包事务）。

## 入口文件清单

- `ai/AiRouter.kt`：角色路由与 Target
- `ai/LlmClient.kt`：HTTP 客户端（含 `/models` 列表拉取）
- `ai/ExtractionService.kt`：分析用例编排（含 `failStaleBatches` 启动兜底）
- `ai/Prompts.kt`：全部提示词模板（带版本号）
- `ai/TokenBudget.kt`：token 估算 / 预算 / 截断
- `ai/JsonExtract.kt`、`ai/SchemaParser`（在 SchemaDtos.kt）、`ai/PatchEngine.kt`、`ai/SchemaDtos.kt`：响应解析与提案构建

## 对外接口 / 被谁调用

- `AppContainer.startAnalysis/dismissAnalysis/analysisState`：`ui/project/ProjectScreen`（后台分析进度）。
- `AiRouter.chat/chatWith/vision/chatJson`：`ExtractionService`、`rag/Answerer`、`ingest/VisionOcr`、`ui/knowledge/KnowledgeScreen`。
- `LlmClient.listModels`：`ui/settings/ProviderScreen`（获取模型列表）。

## 依赖的上游模块

`data`（provider/role_binding/batch/proposal 表、ProviderRepo）、`util`（Storage 落盘原始报文、Format）、`ingest`（分析前补解析材料）。

## 关键数据结构

- `AiRouter.Target`：baseUrl/apiKey/modelId/binding（含 contextBudget、maxOutputTokens）。
- `LlmClient.Result`：`Ok(Response)` / `Err(Failure(message, code, retryAfterMillis))`。
- `AnalysisState`（di 包）：projectId/message/proposalId/error，后台分析的 UI 投影。
- `BatchEntity.status`：EXTRACTING → PROPOSED / FAILED。

## 已知坑与约束

1. **所有调用必须经 AiRouter**，不允许业务里写死模型名（产品要求按角色分模型）。
2. **兼容性降级只重试 1 次**：`json_mode` 与 `max_completion_tokens` 各最多切一次，第三次还失败就原样返回错误。
3. **429 的 Retry-After 封顶 30 秒**：服务端要求等更久也会截断，避免 App 看似卡死。
4. **token 估算是经验系数**：与各家模型真实 tokenizer 有偏差，靠 usage 回写（batch.requestTokens/responseTokens）校准认知，不追求精确。
5. **Key 解密失败 ≠ 未配置**：`KeyBroken` 与 `NotConfigured` 文案不同，UI 不要把两者混成一句「请到设置绑定」。

## 最后更新

2026-10-27：批次二——`chat` 改挂起可取消（runInterruptible + delay）、429 尊重 Retry-After、修复 max_tokens 降级判断的优先级 bug；TokenBudget 计入中文标点/全角并接入 truncate 兜底；分析改应用级后台跑 + 启动兜底置 FAILED；AiRouter 区分 Key 解密失败；删 Prompts 死占位符。首次建立本总览。
