# 产品逻辑记忆（开发约定）

> 本文件记录开发过程中确认的产品逻辑与契约决策，供任何新会话读取，
> 避免依赖对话记忆。修改时同步更新。

## 开发约定：做功能先给方案，不直接改代码（2026-08-19）

- **收到「实现某功能 / 改某逻辑」的需求时，先写多个方案（含取舍：工作量/风险/效果），让用户选择后再动手改代码。**
- 不直接改代码、不直接替换实现；涉及行为/契约变更的改动必须先把方案列表摆出来询问。
- 纯 bug 修复（明确报错、编译错）可直接修，但涉及「要不要改、怎么改」的设计选择必须先问。

## 消息实时刷新：SSE 连接不稳定的现状与候选方案（2026-08-19 存档，待用户决策）

- **现状**：聊天列表/详情页 = SSE（`GET /projects/{projectId}/events`，文档 §12.1）+ 3s 轮询兜底。
  移动端连 `http://47.113.224.195:32500/api/v1`（IP:端口）；web 前端默认 mock，生产示例域名
  `https://api.qgents.dpdns.org/api/v1`。日志显示移动端 SSE 长连接被掐断/超时
  （`sse io: timeout / failed to connect / unexpected end of stream`），事件收不到 → 消息靠 3s 轮询兜底（约 3 秒出现）。
- **已做（不改回）**：`ProjectEventStream` readTimeout 90s→0（对齐浏览器语义，避免后端心跳缺失被误杀）、
  connectTimeout 30s→10s（快速重连）；`ChatDetailFragment` 的 message.created/updated 在 payload 解析不出
  groupId 时不再静默丢弃（直接刷新当前群 + ChatSSE 日志）。
- **待决策方案**（用户选择后再动）：
  1. **保持现状**：3s 轮询兜底 + SSE 加速（SSE 通时 1s 内，不通时 3s）。
  2. **轮询降频**：1~1.5s 轮询，接近实时（代价：请求量/耗电增加）。
  3. **换域名/问后端**：确认 32500 端口 SSE 为何被掐断；或把 BASE_URL 切到 web 生产域名
     `https://api.qgents.dpdns.org/api/v1`（需确认该域名可达且是同一后端）。
  4. **纯 SSE 去掉轮询**：SSE 断线时靠重连 + Last-Event-ID 补事件（后端事件缺失时无兜底）。
- 注：SSE 端点通不通本身是后端/网络层问题，移动端只能调整连接参数与兜底策略。


## TASK_STATUS / DIFF 卡：单消息持续更新（v23，2026-08-18 适配）

- **机制**：每个 Task 在需求群最多两条自动化消息——一条 `TASK_STATUS`（clientMessageId=`task-card-{taskId}`）、
  一条 `DIFF`（clientMessageId=`diff-card-{taskId}`）。状态变化时**更新原消息 content**（id/sequence/createdAt 不变），
  不重复建卡、不增加未读数。卡片定位 = requirement_group_id + client_message_id。
- **TASK_STATUS content（v23）**：`{taskId, status, phase(PLAN/CODING/TESTING/REVIEWING/DELIVERY), deliveryMode,
  deliveryReason, node, message, currentStepId, plan:{summary, steps:[{stepId, sequence, title, role, status, message}]}}`。
  steps[].stepId 必须是数据库 TaskStepEntity.id；按 sequence 升序。
- **DIFF content（v23）**：`{taskId, diffId(必含), reviewBatchId, title, additions, deletions,
  reviewStatus(PENDING_CONFIRMATION/ACCEPTED/REJECTED), deliveryStatus(NOT_STARTED/COMMITTED/PUSHED/MR_CREATED/DELIVERY_FAILED)}`。
- **事件**：卡片 content 更新时发 `message.updated`（payload `{projectId, groupId, messageId}`），
  不等于新建消息；客户端收到后拉群消息接口，**同 id 消息以网络内容覆盖本地**（前端不按连续性聚合）。
- **发送身份**：默认 ORCHESTRATOR Agent（senderType=AGENT）；无可用 ORCHESTRATOR 时 SYSTEM 降级（senderId/name=null）。
- **前端适配**：SseEventType 加 `MESSAGE_UPDATED`；`mergeWithNetwork` 改为 network 在前（同 id 网络内容覆盖）；
  `MessageContentDto`/`ChatMessage`/`MessageEntity` 补 phase/deliveryMode/plan 快照/reviewBatchId/reviewStatus/deliveryStatus；
  TASK_STATUS 卡展示计划摘要+步骤快照；DIFF 卡展示审核/交付状态。
- **本地缓存**：MessageEntity 已持久化全部卡片字段（taskPlanSteps 以 JSON 串存储），升级 DB version 7（fallback 重建）。

## DIFF 卡前端任务清单（2026-08-19 存档，实施状态见各条勾选）

> 来源：后端接口文档 v1.9.4 §7/§11/§12/§15/§16/§20；编排在正式 Diff 生成后向需求群回
> `type=DIFF` 消息卡，用户可引用该卡发起"增量修改"任务（服务端复用源 Workspace）。
> 服务端落点：`TaskOrchestrator.sendDiffCard` / `MessageService` / `TaskTriggerService.resolveQuotedDiffContinuation` / `TaskService.create`（续作）/ `EventService`。

### 契约速览

| 项 | 值 |
|---|---|
| 消息类型 | `type=DIFF`（枚举含 TEXT/CODE/IMAGE/FILE/DIFF/TASK_STATUS/SYSTEM/QUOTE） |
| DIFF 卡 content | `{ diffId: string(必填), title?, additions?, deletions? }` |
| 发送者 | `senderType=AGENT`，`senderName`=编排助手名；SYSTEM 通道不携带 DIFF 卡 |
| 续作判定唯一依据 | 用户消息 `replyToId` 指向一条 `type=DIFF` 消息（不是正文文本） |
| 续作结果 | 服务端从 content.diffId 定位源 Task + Workspace，创建复用同一 Workspace 的增量任务；客户端不得提交 `workspaceId/continuationOfTaskId` |
| diffId 语义 | 批次内按 projectRepositoryId 升序第一条 Diff 的 ID（单仓库 Diff，不是批次 ID）；同批次各 Diff 共享 taskId/workspaceId |

### 前端任务（勾选状态 = 2026-08-19 核对）

**A. 消息渲染：DIFF 卡 UI（P0）**
- [x] A1 类型分支：MessageType.DIFF → DiffVH（独立卡片，不走 TEXT 气泡）
- [x] A2 卡片内容：展示 `content.title`（任务标题）+ `+additions/-deletions`（总统计，缺省按已加载文件汇总）+ 发送者
- [x] A3 点击跳转：点击卡片优先按 diffId 反向映射（SSE diff 事件缓存的 diffId→taskId）反查 taskId → 打开 Task Diff 审核对话框；反查不到时降级全屏查看 diff 文件【待后端确认项：卡片 content 若直接带 taskId 可去掉反查】
- [ ] A4 引用态样式：引用 DIFF 卡时输入区/气泡按 QUOTE 语义展示被引卡片摘要（复用引用 UI，类型图标用 Diff 卡样式）【部分完成：引用条有提示文案，卡片图标样式未单独做】
- [ ] A5 空态与降级：卡缺 diffId 视为异常；与 SYSTEM 的 TASK_STATUS 卡互不混淆【缺 diffId 已 toast，其余无需处理】

**B. 引用交互：续作入口（P0）**
- [x] B1 引用 DIFF 卡：replyToId = DIFF 卡消息 ID（DiffVH 已接长按菜单引用）
- [x] B2 增量提示：引用 DIFF 卡时引用条追加提示"引用 Diff 卡将发起增量修改（复用源工作区）"
- [x] B3 追问文本：输入作为新任务 requirement（trigger-task 已支持 requirement；续作允许纯引用不填文本，空 requirement 不传）
- [x] B4 触发方式：显式 trigger-task 已实现（@Agent + 成功后调用 trigger-task）；自动路径待确认
- [ ] B5 引用窗口说明（可选 UI）：暂不处理

**C. 显式触发接口对接（P1）**
- [x] C1 接口：POST .../messages/{messageId}/trigger-task 已实现（Idempotency-Key 必填）
- [x] C2 请求体：TaskTriggerRequest 已补 `deliveryMode`（可选）；**续作引用时不选仓库、repositoryIds 空 → 请求体不携带**（避免 409 WORKSPACE_CONTINUATION_REPOSITORIES_FORBIDDEN）；创建任务弹窗续作模式隐藏仓库多选并提示"将复用源工作区"
- [x] C3 错误提示：422 QUOTED_DIFF_INVALID / QUOTED_DIFF_NOT_ACCESSIBLE → toast 展示 e.message，不静默重试

**D. 实时刷新与状态联动（P0）**
- [x] D1 群消息 SSE：message.created → pollMessages（已实现）
- [ ] D2 TASK_STATUS 卡与任务列表联动：WAITING_DIFF_CONFIRMATION 提示"Diff 待验收"（部分已有，需核对）
- [x] D3 Task 详情 diffReviewSummary：available=true 渲染「查看 Diff 审核」入口，点击弹审核对话框（批次摘要 + 代表性 diffId 的 Diff 内容 + 按规则确认/拒绝/重试）；available=false 不展示
- [ ] D4 事件联动：task.diff-review.created / failed(DIFF_SNAPSHOT_STALE) / diff-review.skipped → 刷新 Task 与 Diff 面板【部分：SSE 已刷新 task.updated 等，diff-review 专用事件未单独处理】

**E. 通知与动态中心（P1）**
- [ ] E1 通知：DIFF_CREATED 通知 target={type:DIFF, id:diffId, title}；点击按 diffId 跳 Diff 审核页（当前通知点击只处理 TASK_FAILED/INVITED/通用群跳转）
- [ ] E2 动态流：团队动态 DIFF_CREATED 按 target.type+id 拼路由（勿解析 title）

**F. 群列表摘要（P1）**
- [x] F1 latestMessage：type=DIFF 且 text=null 时展示 `[Diff 待验收]`（toSummary() 已补 DIFF 分支）

**G. 任务中心 attention（P1）**
- [ ] G1：attention kind=DIFF_CONFIRMATION_REQUIRED / DELIVERY_FAILED 返回 diffReviewBatchId（批次 ID ≠ diffId）；跳转用 Task 详情路由，不把 diffReviewBatchId 当 diffId

### 验收用例（自测清单）

1. 群内发起任务 → 编排完成 → 群内出现 DIFF 卡（AGENT 身份、含标题/增删行）→ 点击跳转 Diff 审核面板
2. 引用 DIFF 卡 + @Agent → 新任务创建 → 任务列表出现增量任务（服务端复用 Workspace）
3. 引用 DIFF 卡 + 显式 trigger-task（带 Idempotency-Key）→ 续作成功；重放同 key 不重复建任务
4. 对普通 TEXT 消息引用 + @Agent → 走普通新建任务路径（不续作、新 Workspace）
5. 引用卡被删除/跨群引用 → 稳定 422 提示，不静默降级
6. 另开浏览器窗口 → 收到 message.created → DIFF 卡实时出现
7. 任务完成但无代码变更 → available=false / 无卡片，前端不展示 Diff 审核入口

### 待后端确认

- DIFF 卡点击跳转目标：卡片只有 diffId（单仓库 Diff），建议前端统一跳到「Task Diff 审核面板」（用 GET /tasks/{taskId}/diff-review 反查 taskId 或由通知/attention 带 taskId）；如需卡片直接带 taskId 可提需求（服务端可在 content 中补充）。

## MR_FIRST 自动交付（后端已合入 develop，2026-08-19 存档，2026-08-19 前端已实施）

> 来源：后端接口文档 v1.10.0 B 方案（MR_FIRST）。前端改造已完成（P0 全部 + P1 全部），
> 待联调确认项见下方 P2 清单。实施要点与决策记录如下。

### 前端实现要点（2026-08-19 完成）

- **类型**：`TaskCreateRequest.deliveryMode`（可选，不传由后端判定）；`TaskListItemDto/TaskDetailDto`
  新增 `deliveryReason`；`DiffReviewBatchDto` 新增 `confirmationSource`、`repositoryDeliveries[]`
  （新 DTO `RepositoryDeliveryDto`：repositoryId/repositoryName/deliveryStatus/mergeRequest/
  failureCode/failureReason/updatedAt；`RepositoryDeliveryMergeRequestDto`：webUrl/number/title）。
  `confirmationSource` 只读，客户端不提交。
- **按钮规则**（纯函数集中在 `ui/diffreview/DiffReviewRules.kt`，UI 共用避免分叉）：
  - 确认/拒绝：仅 `reviewStatus=PENDING_CONFIRMATION` 且 `confirmationSource != SYSTEM`；
    **缺省（null）按 USER 兜底**，保证 DIFF_FIRST 旧后端回归。
  - `ACCEPTED+USER` →「已由用户确认」；`ACCEPTED+SYSTEM` →「自动交付」，均无按钮。
  - 重试：`PARTIALLY_DELIVERED / FAILED / DELIVERY_FAILED`（含旧枚举）或任务 `DELIVERY_FAILED`，
    `canRetryDelivery` 能力位优先；只调既有 `retry-delivery` 接口。
  - 409 冲突（`HTTP_409` + 已知业务码 `DIFF_BATCH_REVIEW_REQUIRED` 等）：刷新 Task + DiffReview 再定按钮状态。
- **SSE**：`SseEventType` 新增 `DELIVERY_STARTED("delivery.started")`；以 `taskId+operationId`
  去重（`DiffReviewRules.deliveryStartedKey`），聊天页刷新消息卡片、详情页刷新 Task+DiffReview；
  断线重连以查询接口为准。
- **任务详情页**：MR_FIRST 显示「自动交付」标签 + deliveryReason；批次级交付状态稳定文案
  （进行中/完成/部分失败）；`repositoryDeliveries[]` 逐仓库进度卡片（名称/状态/失败原因/更新时间），
  `MR_CREATED` 且 `mergeRequest.webUrl` 非空才渲染「查看合并请求 #N ↗」（系统浏览器打开，webUrl 空不渲染）；
  404 `DIFF_REVIEW_NOT_FOUND` 按无批次处理。
- **任务卡片**：MR_FIRST 显示「自动交付」小标签（读取后端 deliveryMode，不本地推断）。
- **Mock**：`MockDiffRepository` 按 taskId 后缀提供场景——`-mr-auto`（ACCEPTED+SYSTEM+DELIVERING）、
  `-mr-partial`（一个 MR_CREATED + 一个 FAILED）、`-mr-failed`（FAILED 含脱敏原因）；默认 PENDING_CONFIRMATION+USER。
- **测试**：`DiffReviewRulesTest`（按钮权限/文案/webUrl 空不渲染/409 识别）、`DeliveryStartedDedupTest`
  （重复/乱序/晚到去重、重连以查询为准）、`SseEventTypeTest`（wire 映射）、`DiffReviewBatchDtoTest`
  （Gson 解析 confirmationSource/repositoryDeliveries/webUrl=null）。

### 核心概念

- **MR_FIRST（自动交付）**：Reviewer 通过后任务**不等待人工确认 Diff**，直接进入交付
  （推送分支/创建 MR）。DIFF_FIRST（人工确认）原流程保持不变。
- **confirmationSource**：`USER | SYSTEM`，只读字段，仅服务端返回；客户端**不得**提交或修改。
  - `USER` = 用户确认；`SYSTEM` = 后端自动判定交付。
- **deliveryMode**：`DIFF_FIRST | MR_FIRST`；创建 Task 请求支持可选 `deliveryMode`，
  不传时前端不自行判定，由后端 Planner/规则决定。
- **deliveryReason**：服务端判定的理由（如规则命中自动交付），用于展示。

### 状态枚举（校准后）

- `reviewStatus`：`PENDING_CONFIRMATION | ACCEPTED | REJECTED`
- `deliveryStatus`（批次级）：`NOT_STARTED | DELIVERING | DELIVERED | PARTIALLY_DELIVERED | FAILED`
- `repositoryDeliveries[].deliveryStatus`（单仓库级）：`NOT_STARTED | COMMITTED | MR_CREATED | FAILED`
- 任务状态流：MR_FIRST `RUNNING -> DELIVERING -> SUCCEEDED / DELIVERY_FAILED`；
  DIFF_FIRST 保持 `RUNNING -> WAITING_DIFF_CONFIRMATION -> DELIVERING -> SUCCEEDED / DELIVERY_FAILED`。

### 按钮规则（前端必须遵循）

| reviewStatus | confirmationSource | 展示 |
|---|---|---|
| PENDING_CONFIRMATION | USER | 「确认交付」「拒绝交付」 |
| ACCEPTED | USER | 「已由用户确认」，无确认/拒绝按钮 |
| ACCEPTED | SYSTEM | 「自动交付」，不得显示「用户已确认」，无确认/拒绝按钮 |
| deliveryStatus=PARTIALLY_DELIVERED / FAILED（或任务 DELIVERY_FAILED） | — | 按权限显示「重试交付」 |

- 重试只调既有接口 `POST /api/v1/projects/{projectId}/tasks/{taskId}/diff-review/retry-delivery`，
  不新增前端自定义交付接口。
- `confirm` / `reject` / `retry-delivery` 均携带新的 `Idempotency-Key`（不同请求不得复用同一 key）。
- 写操作进行中禁用重复点击；收到 `409` 时刷新 Task 与 DiffReview 后再决定按钮状态。

### delivery.started SSE 事件

- 事件名 `delivery.started`，payload：`{projectId, taskId, reviewBatchId, deliveryMode, operationId, reason?}`。
- 收到后立即展示「开始自动交付」和判定理由，**不把事件当作交付成功**。
- 收到后重新请求 `GET /tasks/{taskId}` 与 `GET /tasks/{taskId}/diff-review`。
- 以 `taskId + operationId` 去重：重复、乱序、晚到事件不重复创建页面数据。
- SSE 断线重连后以查询接口为准，不依赖本地事件缓存。
- 相关事件：`delivery.started` / `delivery.repository.updated` / `delivery.completed` / `delivery.failed`
  到达后刷新对应 Task / DiffReview / MR 数据。

### 逐仓库交付进度（任务详情页）

- 展示总体 `deliveryStatus`；按 `repositoryDeliveries[]` 展示每个仓库的名称、状态、更新时间、脱敏失败原因。
- `MR_CREATED` 时仅展示真实 `mergeRequest.webUrl`（为空不渲染 MR 链接）、编号、标题；MR 链接用系统浏览器/外部打开。
- `NOT_STARTED / COMMITTED / MR_CREATED / FAILED` 使用稳定文案；**不把 COMMITTED 当作 MR 已创建**。
- `PARTIALLY_DELIVERED` 明确展示「部分仓库成功，部分仓库失败」，只对失败仓库提供重试提示（动作本身是任务级接口）。
- `DELIVERED` 仅表示所有目标仓库交付成功；MR 是否合并由 MR 状态表达。
- MR_FIRST 全仓库预检完成前不产生任何 Commit，前端不要提前展示「已提交」。

### 前端待办清单（MR_FIRST / B 方案，P0+P1 已实施 2026-08-19）

**P0：必须完成（已实施）**
- [x] Task：`deliveryMode` 扩展 DIFF_FIRST/MR_FIRST；新增 `deliveryReason`；TaskCreateRequest 支持可选
  `deliveryMode`（不传不判定）；Task 详情/列表/卡片读取并展示 deliveryMode；`confirmationSource` 只读。
- [x] DiffReviewBatch：新增 `confirmationSource: USER|SYSTEM`；校准 `reviewStatus`、`deliveryStatus`；
  `repositoryDeliveries[]` 单仓库状态；`mergeRequest.webUrl` 空时不渲染链接；`failureCode`/`failureReason`
  可空，失败原因只展示后端脱敏文本。
- [x] 按钮规则（见上表）；`confirm`/`reject`/`retry` 带新 Idempotency-Key；写操作禁用重复点击；
  409 后刷新 Task + DiffReview。
- [x] SSE 接入 `delivery.started`：类型、字段、展示、双查询刷新、`taskId+operationId` 去重、重连以查询为准。
- [x] 任务详情页展示总体 deliveryStatus + 逐仓库进度（名称/状态/更新时间/脱敏失败原因）+ 真实 MR 链接 +
  稳定文案；PARTIALLY_DELIVERED 与失败重试提示。

**P1：应完成（已实施）**
- [x] MR_FIRST 状态流不进入 WAITING_DIFF_CONFIRMATION；列表/详情/卡片统一用后端状态，不本地推断交付结果。
- [x] 移动端：MR_FIRST 显示「自动交付」标签 + deliveryReason；不显示 Diff 确认/拒绝入口；
  失败/部分失败显示稳定失败码 + 脱敏原因 + 重试；MR 链接外部打开且处理 webUrl=null；
  SSE 不可用时进入详情仍能查询恢复；网络重试不复用不同请求的 Idempotency-Key。
- [x] Mock 场景：MR_FIRST+ACCEPTED+SYSTEM+DELIVERING；MR_FIRST+PARTIALLY_DELIVERED
  （至少一个仓库 MR_CREATED、一个 FAILED）；MR_FIRST+FAILED 与重试成功。
- [x] 测试：delivery.started 重复/乱序/断线重连；按钮权限（SYSTEM 无确认/拒绝、失败才重试）；
  mergeRequest.webUrl 为空不显示链接。

**P2：联调前与后端确认（待确认）**
- [ ] 响应字段确认是 `confirmationSource`（非 `confirmedByType`）。
- [ ] `repositoryDeliveries[].deliveryStatus` 最终枚举与失败原因字段名。
- [ ] `delivery.repository.updated` 完整 payload（前端按事件刷新，不假设事件含完整 Diff）。
- [ ] `reviewBatchId` 是 Task 级批次 ID，不当作单个 Diff ID。
- [ ] `diff-review` 不存在按 `404 DIFF_REVIEW_NOT_FOUND` 处理，不当作空批次。
- [ ] MR_FIRST 全仓库预检完成前不产生 Commit，前端不提前展示「已提交」。

**验收标准**
- [x] （代码层已满足，需真机回归）DIFF_FIRST 原流程回归：查看 Diff、确认、拒绝、交付、失败重试均可用。
- [x] （代码层已满足，需真机回归）MR_FIRST 从 REVIEWER 成功后进入自动交付，不出现确认按钮。
- [x] （代码层已满足，需真机回归）实时看到开始交付、逐仓库状态、真实 MR 链接与失败重试状态。
- [x] （代码层已满足，需真机回归）刷新页面、SSE 断线重连、重复事件、移动端切后台后状态与查询接口一致。
- [x] 不把 confirmationSource=SYSTEM 展示成「用户已确认」；不把批次 ID 当 Diff ID / MR ID。

## Diff 确认（2026-08-17 实现，2026-08-18 改为 Task 级批次契约）

- **触发**：任务跑完停在"待确认 Diff"时，群里收到 TASK_STATUS 卡片（"任务开发完成，等待你对 diff 的确认"）。
- **App 端交互**：点击 TASK_STATUS 卡片 → 用 `message.taskId` 拉任务详情 → 解析 `diffReviewSummary`（reviewStatus、
  capabilities）→ 弹 Diff Review 确认对话框（批次摘要 + 首个 Diff 文件内容 + 「确认 Diff / 拒绝 Diff」按钮）。
- **接口（§12.3 Task 级最终 Diff Review 批次）**：确认/拒绝必须走 Task 级接口，批次内 Diff 禁止用单 Diff
  accept/reject（返回 409 DIFF_BATCH_REVIEW_REQUIRED）：
  - `GET /projects/{projectId}/tasks/{taskId}/diff-review` — 查批次（可能为 null）
  - `GET .../tasks/{taskId}/diff-review/diffs/{diffId}/patch` — 读不可变 patch
  - `POST .../tasks/{taskId}/diff-review/confirm` — 确认整个批次，开始逐仓库交付（body 空对象 {}）
  - `POST .../tasks/{taskId}/diff-review/reject` — 拒绝整个批次（body `{"reason":"..."}`）
  - `POST .../tasks/{taskId}/diff-review/retry-delivery` — 交付失败后重试
  - 三个写接口均要求 Idempotency-Key。
- 数据层：`DiffRepository` 加 getTaskDiffReview/getDiffReviewPatch/confirmDiffReview/rejectDiffReview/retryDiffDelivery；
  `TaskDetailDto.diffReviewSummary` 用 JsonElement 兼容解析；`ChatMessage.taskId` 从 TASK_STATUS content 解析。
- **reviewStatus 语义**：仅 `PENDING_CONFIRMATION` 显示确认/拒绝按钮；`capabilities.canConfirmDiffReview`/
  `canRejectDiffReview`/`canRetryDelivery` 优先，缺省按 reviewStatus 兜底。
- 拒绝可填原因；确认后任务进入交付（DELIVERING → SUCCEEDED），Agent 提交 MR 等待审核。

## 交付失败（DELIVERY_FAILED，2026-08-18 处理）

- **含义**：确认 Diff 后任务进入交付阶段，推送分支/创建 MR 等交付动作出错 → 任务状态变 DELIVERY_FAILED（列表红字"交付失败"）。
- **App 端展示**：任务详情页状态下方展示失败原因（从 `diffReviewSummary` 解析 deliveryFailedReason/failedReason/
  deliveryError/errorMessage/message 等字段，取首个非空）+「重试交付」按钮（capabilities.canRetryDelivery 或状态
  DELIVERY_FAILED 时显示）；聊天 TASK_STATUS 卡片点击弹出的 Diff Review 对话框同样展示交付状态与失败原因。
- **重试**：`POST /projects/{projectId}/tasks/{taskId}/diff-review/retry-delivery`（Idempotency-Key 必填），成功后
  任务回到交付流程；详情页与对话框均提供入口。
- **SSE**：`delivery.failed` / `delivery.completed` / `delivery.repository.updated` 到达时，聊天页刷新消息
  （同步 TASK_STATUS 卡片状态），任务列表/详情刷新数据。
- **定位失败原因**：详情页运行列表 → 查看执行日志（§12.2）；聊天对话框展示的失败原因字段来自任务详情
  diffReviewSummary，若后端未返回原因则提示"详见执行日志"。

## 编排助手与任务启动失败（2026-08-17 后端待办完成）

- **TASK_STATUS 卡片**：不假设发送者是 Developer/Tester/Reviewer（正常来自 ORCHESTRATOR Agent）。
  `senderType=SYSTEM` 时（ORCHESTRATOR 缺失降级）状态标签显示"系统"，不读 senderId/头像/Agent 详情
  （TaskStatusVH 已按 senderType 区分）。
- **message.created**：收到后仍以消息列表接口为准刷新（ChatDetailFragment pollMessages，不依赖 SSE payload）。
- **task.updated status=FAILED**：任务卡片显示失败状态；TASK_STATUS 卡片展示后端 content.message。
- **notification.created kind=TASK_FAILED**：通知点击 → 跳任务详情（resourceId=taskId，projectId=notification.projectId）。
  BaseMessageListFragment 加 taskDetailActionRes（子类覆盖各自 action）；导航图给 messageList/taskMessageList 加 action。
- **不调用 orchestrate/start 等推进接口**：App 只展示后端状态，客户端不推进任务。
- **日志为空原因**：Planner 启动阶段（LLM/Worker acquire）失败时 execution_logs 无记录，TaskRun 日志接口返回空——
  后端行为，非 App 问题。

## 前端待办清单 v1.9.4（2026-08-17 完成）

- **清单一（自动建仓）**：创建项目表单加「自动新建 GitHub 仓库」开关（SwitchMaterial）+ 仓库名输入。
  newRepository（name/description/isPrivate/installationId/displayName）与绑定已有仓库二选一：
  开自动建仓时清空已选仓库；提交传 `NewRepositoryRequest` 给 POST /projects（后端自动建仓绑定）。
  仓库名校验：`^[a-z0-9._-]+$`。错误码 409 GITHUB_REPOSITORY_CREATE_CONFLICT（改名）、
  422 GITHUB_INSTALLATION_REQUIRED（多安装需指定 installationId，当前不处理）。
- **清单二（触发任务 repositoryIds）**：repositoryIds 已用 `GET /projects/{id}/repositories` 的 `id`
  （project_repositories.id，正确）；补充 **baseRef = 仓库 defaultBranch**（不再留空）。
- **清单三（软解绑）**：App 无解绑 UI（unbindProjectRepository 仅数据层），无需改动；
  无旧错误码 `PROJECT_REPOSITORY_REFERENCED_BY_*` 文案需清理。
- **清单四（错误提示）**：`ApiException` 加 `requestId`；500 错误 Toast 追加 requestId 方便后端排查；
  任务卡片在 PLANNING/PENDING/RUNNING 超 5 分钟未更新时显示「任务ID: xxx」（卡死提示）。
- 清单五/六为后端说明与确认项，前端无改动。

## @ Agent 自动触发任务（2026-08-16 实现，2026-08-XX 契约改版）

- **机制（旧）**：在需求群发消息 @ 了 Agent（mention type=AGENT）→ 消息发送成功后**自动弹「发起任务」弹窗**，
  预填标题（消息前 30 字）和需求（消息全文）→ 用户选仓库确认 → POST /tasks 创建任务 →
  后端自动建 `feat/task-{taskId}` 分支并开始编排（Planner→Developer→Tester→Reviewer）。
- **契约改版（契约 §7）**：`POST .../messages` **请求体不再携带 `mentions`**（带 mentions 会 400「请求体格式不对」）；
  @Agent 触发任务改为：发消息成功后，用返回的 `messageId` 显式调
  `POST /projects/{projectId}/groups/{groupId}/messages/{messageId}/trigger-task`
  （`TaskTriggerRequest{title, requirement?, repositoryIds?, baseRef?}`，title 必填，data 恒为 null）。
- 实现：`sendTextMessage`/`resendText` 客户端解析 @Agent（不随消息体发送）→ 成功后
  `showCreateTaskDialog(..., messageId = dto.id)` → 确认后 `taskRepo().triggerTask(...)`。
- 「+ 菜单 → 发起任务」仍走 `POST /tasks`（TaskCreateRequest.requirementGroupId=当前群，无 messageId）。
- `TaskTriggerRequest` / `QgApiService.triggerTask` / `TaskRepository.triggerTask` 已就绪。

## Memory / Skill 池交互（2026-08-16 完善）

- **生成 Memory 草稿**：多选聊天记录 → 弹窗填**标题（必填）+ 简介（可选，留空拼接选中消息）** →
  POST /memories（DRAFT）→ submit-review（PENDING_REVIEW 进审核队列）。
- **审核队列条目（待审核）**：点击**直接弹审核操作（通过/拒绝）**；非 Admin Toast 无权限不弹。
  审核权限 = 项目 Admin（getProjectMembers role=PROJECT_ADMIN）**或** 团队 Owner
  （getTeamMembers role=TEAM_OWNER，文档 §3.1 兜底管理权限），实时读取不依赖写死 isProjectAdmin。
- **共享池条目（已通过）**：点击弹 ResourceDetailSheet 只读纯文本详情（白底圆角 bg_card，
  类型标签 + 标题 + 内容）。

## 总群 vs 需求群：Agent 规则（2026-08-16 确认）- **项目总群（PROJECT_MAIN）是纯人类聊天页面**：不合并 Agent，@ 弹窗只有真实群成员。
- **需求群（REQUIREMENT）自动带 Agent**：创建群弹窗成员选择区并入团队 ACTIVE Agent（默认勾选，
  显示 "Agent" 标签）；Agent 入群靠后端 `sendAsAgent` 回消息（不随创建群提交 Agent id，
  `checkedUserIds()` 只提交真实用户）；@ 弹窗合并 Agent。
- ChatDetailFragment `rebuildMemberMaps()` 按群类型（mainViewModel.groups 反查）决定是否合并 Agent。

## 群里发起任务（D 组，2026-08-16 完成）- **后端机制**：@ Agent 不会自动触发任务（后端无消息→任务监听器）。Agent 干活 =
  显式 `POST /projects/{projectId}/tasks`（TaskCreateRequest：requirementGroupId=当前需求群、
  title、requirement、repositoryIds 至少 1 个、baseRef 可选）。创建后后端 Orchestrator 编排
  （Planner→Developer→Tester→Reviewer），各 Agent 执行时经 `sendAsAgent` 回群发消息
  （TASK_STATUS/DIFF 等），App 端已支持卡片展示。
- **App 端入口**：聊天详情页「+ → 发起任务」→ 弹窗（标题 + 需求描述 + 项目绑定仓库多选）→ 创建任务。
- 数据层：`TaskCreateRequest` DTO + `QgApiService.createTask` + `TaskRepository.createTask`
  （Impl 真实 / Mock 保底，AppContainer 用 TaskRepositoryImpl）。
- 任务侧（同事合并）：TaskRepository 查询/详情/步骤/运行/取消/换 Agent 已就绪。

## 消息系统提示与引用（2026-08-16 确认）

- **系统提示（SYSTEM 消息）**：成员进群/退群等由后端以 `SYSTEM` 消息推送
  （senderType=SYSTEM、senderName=null）。前端在 `ChatMessageAdapter` 用独立 viewType
  （TYPE_SYSTEM）渲染：**居中、灰色小字、无头像无气泡**（item_message_system.xml）。
- **长按消息菜单**：引用 / 复制 / 多选（多选预留，暂 Toast 提示开发中）。全部消息可引用。
- **引用实现**：长按 → 引用 → 输入框上方显示引用条（tvQuoteBar，可关闭）→ 发送时带 `replyToId`；
  收到 QUOTE 消息在气泡上方显示引用摘要（tvQuoteSummary）。
- 数据层：`ChatRepository.sendMessage` 贯通 `replyToId`；`ChatMessage` 加 `replyToId/replyToSummary`；
  Room `MessageEntity` 加 `replyToId` 列（DB v3→v4，fallbackToDestructiveMigration）。
- 引用摘要（replyToSummary）：后端 GroupMessageDto 仅返回 replyToId 无被引用内容，摘要字段当前为 null，
  展示"回复 xxx：内容"（本地拼接发送者名 + displayContent）；收到他人 QUOTE 时显示"引用"。

## 群列表排序与总群标识（2026-08-16 确认）

- 创建项目时后端自动创建**项目总群（PROJECT_MAIN）**，包含全部成员，不可归档/删除。
- 群列表排序（MainViewModel.sortGroups 与 ChatListAdapter.submitList 一致）：
  1. **项目总群恒置顶**（多总群时按最新活跃倒序）；
  2. 手动置顶（pin）群次之；
  3. 其余需求群按最新活跃（lastActiveTime）倒序。
- 总群行显示「总群」标签（item_chat.xml tvGroupTag，PROJECT_MAIN 显示，REQUIREMENT 隐藏）。
- `ChatGroup` 模型加 `type: GroupType`（PROJECT_MAIN / REQUIREMENT），由 `GroupDto.type` 映射。

## 仓库授权撤销处理（2026-08-16 确认）

- 架构：GitHub 账号授权是**团队级**（Installation），仓库绑定是**项目级**（ProjectRepository）。
  仓库授权在 GitHub 网页端管理，App 只同步状态（`GithubViewModel.loadRepositories` 过滤 AUTHORIZED）。
- 问题：网页端撤销已绑定项目的仓库授权后，`project_repositories` 绑定记录仍在，
  `ProjectRepositoryDto.authorizationStatus` 变 `REVOKED` —— 形成「死绑定」，后续 Task/Diff/MR 会引用。
- 处理（已实现）：团队详情页「管理仓库」分组，除 AUTHORIZED 仓库外，遍历团队所有项目，
  收集 `authorizationStatus == "REVOKED"` 的绑定仓库，**标红 + "授权已撤销"标签**展示，
  点击提示原因（`repo_revoked_hint`）。保留绑定不自动解绑（用户确认方案）。
- 后端保护：删除 Installation 时若仍被项目绑定引用返回 `409 GITHUB_INSTALLATION_IN_USE`（文档 §6）。

## SSE 事件流（2026-08-16 后端文档更新）

三条流，`data/sse/ProjectEventStream.kt` 已支持（同一时刻只连一条，切换自动断开旧连接）：

1. **项目级** `GET /projects/{projectId}/events`：
   - 任务/Diff/交付 21 种事件（§12.1）；
   - **`message.created`**：有人/Agent 发群消息，payload `{projectId, groupId, messageId}`——
     聊天实时刷新已接此事件（详情页匹配 groupId 后立即拉消息）；
   - `group.created/updated/archived`、`group.member.updated`：payload `{projectId, groupId}`；
   - `memory.submit-review/approved/rejected/archived`：payload `{projectId, resourceType, resourceId, eventVersion, updatedAt}`。
2. **团队级** `GET /teams/{teamId}/events`：`project.member.added`（`{teamId, projectId}`）、
   `team.member.updated`（`{teamId, userId}`）、`activity.created`（暂未发布）。
3. **通知级** `GET /notifications/events`：`notification.created`（`{notificationId, kind}`）。

契约：`id` = 流内 sequenceNo（Last-Event-ID 续传），15s 心跳，409 EVENT_CURSOR_EXPIRED 清游标重连。
事件仅刷新界面，处理后必须重新拉查询接口。客户端 API：`startProject(id)` / `startTeam(id)` / `startNotifications()`。

## 群聊体系（2026-08-16 确认）

- **创建项目**时后端自动创建项目总群（`PROJECT_MAIN`），包含项目全部成员，不可归档/删除。
- **需求群**（`REQUIREMENT`）按需求创建，**只包含部分成员**（创建时从项目成员中多选挑人）。
- **任何项目成员都可以创建需求群**，群内成员权限平等、无角色区分（文档 §7）。
- 创建需求群 UI：群名 + 描述（可选），成员从项目成员**多选**，**默认全不选**，提供**全选**按钮。
- 后端契约现状（文档 v1.9.2）：`POST /projects/{projectId}/groups` 请求体文档只写了
  `{title, description, repositoryIds?, type="REQUIREMENT"}`；经确认后端支持 `memberIds` 字段
  （userId 列表），已在 `CreateGroupRequest` 中加 `memberIds` 贯通到请求体。
- 创建需求群入口：群聊列表「加号 → 创建群聊」（重做后的真实弹窗，不再是邮箱邀请占位）。

## 拉人进群（2026-08-19 确认：拉人进群不做，显示"XXX 加入群聊"待后端补推）

- **背景**：需求群成员在创建群时选定；群设置页「添加成员」实际调 `POST /projects/{projectId}/members`
  （**项目成员**接口）；项目成员加入后自动进入**总群（PROJECT_MAIN）**。
- **决策**：后端当前**无"把成员加入现有需求群"的接口**，**「拉人进群」功能暂不实现**；
  显示"XXX 加入群聊"选择 **A 路径：后端补推 SYSTEM 消息**（2026-08-19 已定，前端零改动）。
- **前端已就绪（无需再改）**：
  - 渲染：`type=SYSTEM` 消息走 SystemVH 居中灰色小字（成员进群/退群注释样式）；
  - 实时：`ChatDetailFragment` SSE 已监听 `group.member.updated`（匹配 groupId）→ 立即
    `pollMessages` + `refreshGroupMembers`（@ 列表同步）；`message.created` 也会触发刷新；
  - 诊断日志（验证用，联调通过后可移除）：`ProjectEventStream` 打所有 SSE 事件（tag=ProjectEventStream）、
    `ChatSSE` 打 group.member.updated 匹配结果、`ChatPoll` 打 SYSTEM 消息数量、`ChatMember` 打成员刷新结果。
- **诊断结论（2026-08-19 真机日志）**：拉人进总群后，`GET .../groups/{groupId}/messages` 持续 30 条、
  无新增（含非 SYSTEM 消息），后端**未推送任何消息**；`sse io: timeout` 为 90s 心跳超时瞬断，自动重连，非问题。
- **后端需要做的（对接要求）**：
  1. 成员加入项目/总群（及受影响需求群）时，向对应群推送一条消息：
     `type=SYSTEM`、`senderType=SYSTEM`、`senderName=null`、`content.text="XXX 加入群聊"`（多人为"XXX、YYY 加入群聊"）；
  2. 推送 `group.member.updated` SSE 事件（payload `{projectId, groupId}`）触发前端实时刷新；
  3. 该消息必须进入 `GET /groups/{groupId}/messages` 分页（持久存在，刷新/重进可见，所有设备可见）。

## 拉成员进项目（2026-08-16 确认）

- 入口：群聊列表「加号 → 添加成员」；同时加到**群设置页**功能卡片。
- 流程：列出团队中未加入当前项目的成员 → 勾选 → 每个成员可选身份（项目成员/项目管理员，
  点击身份标签切换，默认项目成员）→ 加入。
- 实现：`POST /projects/{projectId}/members`（AddProjectMemberRequest `{userId}`，初始 PROJECT_MEMBER）
  → 选管理员的再 `PATCH /projects/{projectId}/members/{userId}`（UpdateProjectMemberRequest `{role}`）。
- 加人成功后**只提示"已加入"，留在当前页**（不跳转）。

## 团队成员排序（2026-08-16 确认）

- 团队详情页成员列表：**创建者（TEAM_OWNER）置顶**，其余按后端返回顺序。
- `TeamMemberDto` 无加入时间字段，暂不支持按加入时间排序。

## 接口契约（用户提供 OpenAPI 补充）

- `POST /projects/{projectId}/members`：AddProjectMemberRequest `{userId}`（必填），
  返回 ProjectMemberResponse `{userId, role}`，初始 PROJECT_MEMBER。
- `PATCH /projects/{projectId}/members/{userId}`：UpdateProjectMemberRequest `{role}`（必填），
  在 PROJECT_MEMBER / PROJECT_ADMIN 间调整，保护最后一名 Project Admin。
- `GET /projects/{projectId}/members`：分页返回 ProjectMemberResponse 列表（userId + role），
  选人界面显示名字时需用团队成员的 displayName 按 userId 关联。
- 均需 `Idempotency-Key` 头，Bearer 鉴权。

## 加载性能优化（2026-08-20，用户反馈「项目/群聊加载慢」）

- **A1 日志降级**：`RetrofitClient` Http 日志 `BODY` → debug `BASIC` / release `NONE`（BODY 级会让 OkHttp 先整读大响应体再交 Gson 解析，高频轮询/事件下拖慢所有请求）；
  删除 `getMembers` 的 `MemberRaw`（整响应体 Gson 序列化）与 `loadGroups` 的 `GroupBadge` 逐群诊断日志。
- **A2 抽屉红点节流**：`refreshDrawerUnread(force=false)` 默认先延后 800ms（让 loadGroups 等主链路请求先发出，
  不抢同一 host 的 5 并发名额）且 3s 窗口合并（事件风暴首尾各算一次）；`markGroupRead` 与 `openDrawer` 走
  `force=true` 即时算。**当前项目群列表实时性不受影响**（消息事件仍立即 refreshGroups，红点只降「其他团队/项目」的频）。
- **C1 群列表先显示后过滤**：`loadGroups` 拿到群列表立即渲染，每群 `getMembers` 成员校验改后台异步（校验完剔除不在的群），
  列表不再等 N 个成员请求；`visible.size != dtos.size` 时才二次发数据。
- **C3 切项目红点延后**：切团队/项目/冷启动的红点遍历走节流默认（延后 800ms），不与 loadGroups 抢并发。
- **A3 排序接口（未动）**：`GET /teams/by-last-activity`、`GET /teams/{id}/projects/by-last-activity` 保持
  「排序优先 + 失败回退普通列表」；后端是否已实现这两个接口待确认（文档 v2.0.8 无对应条目）。

## @我（MESSAGE_MENTION）通知中心（2026-08-20 完成）

- 跳转链路原本已就绪（BaseMessageListFragment 点击 → ChatDetailFragment `targetMessageId` 滚动高亮 +
  `fromMention` 兜底滚到最上面 @ 消息），缺口只是两个通知列表 filter 都排除了 MESSAGE_MENTION。
- **抽屉铃铛 = 个人通知中心**：`MessageListFragment` filter 改 `INVITED || MESSAGE_MENTION`；
  `MainViewModel.refreshUnreadInvitations` 红点统计同步覆盖 @我；`refreshUnreadTaskNotifications` 排除
  MESSAGE_MENTION（修复任务铃铛幽灵红点：此前 @我 未读点亮任务铃铛但列表不展示）。

## 群免打扰（2026-08-20 完成，本地实现 + 预留后端同步）

- 语义（微信式）：免打扰群后台不弹系统通知（`QgentApp.notifyMessageCreated` 拦截），未读红点/角标照常累计。
- `data/DndStore.kt`：SharedPreferences 存免打扰 groupId 集合（仅本机）；群设置页「功能入口」卡片加 Switch。
- **不需要后端接口**：免打扰是纯本地行为；后端偏好接口只在「多端同步设置」时才需要，届时按 DndStore 注释替换读写即可。

## 应用图标（2026-08-20 更换）

- 素材：`C:\Users\24772\Pictures\qgents_2.png`（455×440，米白底 #FDFBFA + 深色 #25303A Logo）。
- 结构：`drawable-nodpi/ic_launcher_foreground_img.png`（432×432，Logo 缩到 **48%** 居中——自适应图标前景不会被系统缩放，
  整图铺满会被圆形遮罩裁剪，必须把主体预缩到安全区内）；背景米白纯色 vector；旧系统各密度 PNG 替换默认 webp。
- 通知栏小图标 `ic_bell` 独立，未换。

## Testset / Dry Run：方案 A 定稿（2026-08-20）

- **Testset 概念**：项目自建可复用的测试配置（command/timeoutSeconds/passRule/acceptanceNotes，绑定仓库，
  Project Admin 管理，本期只管理配置不负责执行；§10）。
- **移动端只读，管理归 web**：移动端唯一用到 testset/dry-run 的地方 = MR 详情门禁区只读展示
  TESTSET / DRY_RUN / AI_REVIEW / CQ_PLUS_ONE 状态（已实现，零改动）。
- **自动路径已闭环**：移动端创建任务 → 后端编排 → Diff 确认/MR_FIRST 自动交付 → **后端自动跑必选 Testset + DryRun** →
  结果回写 MR checks → 移动端看状态 → CQ+1 → Admin 合并。移动端不做任何执行操作。
- **不做**：手动触发 test-runs/dry-runs 按钮（web 端职责）；A+ 增强（FAILED 时反查测试集名/命令）暂缓，需要时再加。
