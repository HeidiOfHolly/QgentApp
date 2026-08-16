# 产品逻辑记忆（开发约定）

> 本文件记录开发过程中确认的产品逻辑与契约决策，供任何新会话读取，
> 避免依赖对话记忆。修改时同步更新。

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
