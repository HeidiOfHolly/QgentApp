# Qgents 技术文档（V1.0）

> 本文档用于说明 Qgents 当前三端实现、系统架构和任务执行机制。
> 当前基线：Web `main`、Android `develop`、后端 `develop`。

## 1. 技术目标与原则

- **边界清晰**：以 Team 和 Project 做权限与数据隔离。
- **过程可追踪**：需求、任务步骤、执行记录、Diff 和交付状态均持久化。
- **结果可验证**：测试、质量门禁和 Review 共同决定任务是否可交付。
- **执行受控**：代码修改发生在持久 Workspace 和临时 Sandbox 中，不能直接把 Agent 当作普通宿主机进程运行。
- **三端一致**：REST 作为业务数据来源，SSE/WebSocket 用于及时通知和刷新。

## 2. 系统总体架构

```text
Web React 应用                 Android 应用
       |                         |
       +----- REST / SSE / WS ---+
                         |
                  Spring Boot 后端
                         |
        +----------------+----------------+
        |                |                |
      MySQL            Redis          GitHub / OSS / FCM
        |
  Task / Chat / Agent / Diff / Delivery 等领域服务
                         |
                  Sandbox Worker
                         |
              Docker Sandbox + Workspace
                         |
                    Git Bare Store
```

后端负责业务编排和状态持久化，Sandbox Worker 负责在受控环境中执行开发工具。GitHub、对象存储和 FCM 分别承担代码托管、附件/头像存储和移动端推送能力。

## 3. 三端仓库与技术栈

| 端 | 本地路径 | 分支 | 技术栈与职责 |
| --- | --- | --- | --- |
| Web | `D:\Android Studio\qgents` | `main` | React 19、Vite、TypeScript strict、React Router、TanStack Query、Zustand、Ant Design、MSW、Vitest；负责完整研发工作台 |
| Android | `D:\Android Studio\qgentApp` | `develop` | Kotlin、Android、ViewBinding、Retrofit、Gson、OkHttp、Room、Glide、Navigation、Coroutines；负责移动协作和通知 |
| 后端 | `D:\Android Studio\qgents-backend` | `develop` | Java 21、Spring Boot 4.1、Spring AI 2.0、LangGraph4j、MyBatis-Plus、MySQL、Redis、Security/JWT、SSE/WebSocket；负责核心业务和 Agent 编排 |

## 4. 后端架构

### 4.1 分层结构

后端采用典型的：

```text
Controller -> Service -> Mapper -> Entity
```

- **Controller**：提供 REST、SSE 和 WebSocket 入口，处理参数与权限上下文。
- **Service**：承载领域规则、状态转换、任务编排和外部服务调用。
- **Mapper**：通过 MyBatis-Plus 访问数据库。
- **Entity**：映射 Team、Project、Task、Message、Diff、MR 等持久化对象。

### 4.2 API 与鉴权

- Web 和 Android 通过 Retrofit/HTTP 客户端调用 REST API。
- Web 客户端自动附加 Bearer Token，并处理 Refresh Token。
- 写请求由 Web 客户端自动生成 `Idempotency-Key`，用于降低重复提交风险。
- API 使用统一 response envelope，客户端按统一结构解析成功和错误结果。
- Spring Security + JWT 负责用户身份认证和访问控制。

### 4.3 实时通信

- **SSE**：用于项目级事件和任务状态变化通知。
- **WebSocket**：Android 用于用户级实时消息通道。
- 实时事件主要用于触发客户端刷新或失效缓存，不作为最终业务事实来源。
- 断线、乱序或游标失效时，客户端应以 REST 查询结果为准恢复状态。

## 5. 核心领域模型

```text
Team
 └─ Project
     ├─ Group / Message / Attachment
     ├─ Agent / Skill / Memory
     ├─ Repository / Branch / Workspace
     └─ Task
          └─ TaskStep
                └─ TaskRun
          └─ DiffReviewBatch
                ├─ Diff
                ├─ Delivery
                └─ Merge Request
```

- **Team / Project**：组织边界和项目边界。
- **Task**：用户提出的一项开发任务。
- **TaskStep**：Planner 拆出的具体步骤。
- **TaskRun**：某个步骤的一次实际执行记录。
- **DiffReviewBatch**：任务级的多仓库 Diff 审查批次。
- **Delivery / MR**：仓库交付和合并请求状态，和任务整体状态关联但保持独立。

## 6. 任务编排与 Agent 执行

### 6.1 执行角色与模式

任务执行链路由 LangGraph4j 等编排能力驱动，主要角色为 Planner、Developer、Tester 和 Reviewer。

步骤的 `executionMode` 比角色名称更能确定执行语义：

| 模式 | 含义 |
| --- | --- |
| `PLAN` | 生成或整理任务计划 |
| `MUTATE` | 可修改代码，通常要求产生真实代码变更 |
| `VERIFY` | 只读验证，不应修改代码 |
| `TEST` | 只读测试或验证 |
| `REVIEW` | 只读代码和质量审查 |

因此，界面不能只显示 `DEVELOPER`、`TEST` 等角色，还应同时显示执行模式和当前阶段。

### 6.2 Workspace 与状态推进

- Workspace 用于保存任务期间的持续代码状态。
- 同一 Workspace 同时只能有一个写入者，避免多个 Developer 并发覆盖代码。
- Planner 生成步骤依赖，默认情况下后续步骤依赖前一步结果。
- 当前执行图主要按 `sequenceNo` 线性推进，依赖关系的动态编排仍需继续完善。
- 测试失败目前可以进入 Review，由 Review 结果进行最终裁决；因此“测试失败”和“任务最终失败”不是同一状态。

### 6.3 任务结果的产品风险

当前如果一个测试步骤返回失败，后续流程仍可能继续，最终任务也可能完成。这个行为来自状态机对测试失败的推进规则，并不一定表示后端把失败错误地返回成成功。

后续应明确：哪些失败必须阻断、哪些失败允许进入人工审查、哪些失败需要回到开发步骤修复；同时在客户端展示失败原因、执行模式和最终裁决来源。

## 7. Workspace、Sandbox 与 Git

Sandbox Worker 与主后端相对独立：

1. Worker 从共享 Bare Git Store 创建 linked worktree。
2. 任务运行时将授权仓库挂载到临时 Docker Sandbox。
3. Agent 通过受控文件工具和固定开发命令读写代码。
4. 变更回到 Workspace，并由后端生成 Diff、交付记录和 MR 信息。

Sandbox 镜像和 Worker 镜像职责不同：Agent Sandbox 侧不安装 Git，Worker 侧保留 Git CLI 处理仓库操作。

这里的隔离是受部署环境和权限配置约束的，不能将 Docker、Worker 或宿主机访问描述为绝对安全。生产部署需要限制 Docker Socket、仓库挂载、命令白名单和密钥暴露范围。

## 8. Web 与 Android 实现

### 8.1 Web

- TanStack Query 管理服务端数据缓存和刷新。
- Zustand 管理部分客户端状态。
- React Router 组织登录、团队、项目、任务、Diff、Agent 和质量审查页面。
- SSE 事件主要使 Query 缓存失效并重新请求 REST 数据。
- MSW 和 Vitest 用于开发辅助及测试场景。

### 8.2 Android

- Retrofit 统一定义后端 API，OkHttp 负责网络请求。
- Room 主要缓存消息，保证聊天页面在短暂断网时仍可读取历史内容。
- Glide 负责头像、附件等图片加载。
- AppContainer 作为手写依赖容器，组织 API、Repository 和领域模块。
- Coroutines 处理异步请求、SSE/WebSocket 和页面生命周期。
- `message.created` 等事件可触发 Android 通知。
- 当前开发联调配置允许 HTTP 明文流量；正式 HTTPS 部署前应移除该配置并收紧网络安全策略。

## 9. 部署与外部依赖

后端采用 Maven 工程，并配套 Sandbox Worker、开发工具镜像和 Docker Compose。主要依赖如下：

- MySQL：业务数据和任务执行记录。
- Redis：缓存、事件或运行态数据。
- GitHub：OAuth/App、仓库、分支和 MR 集成。
- OSS：附件、头像等对象存储。
- FCM：Android 推送。
- Docker：Sandbox Worker 的受控执行环境。

部署时需要通过环境变量或安全配置注入数据库、JWT、GitHub、OSS、FCM 等密钥，不应写入仓库或文档。

## 10. 测试与已知限制

### 10.1 当前已有的质量能力

- Web 侧存在 Vitest 和 MSW 测试辅助能力。
- 后端提供 Testset、Dry-run、Quality Gate、CQ 和 Preflight 相关领域能力。
- 任务结果包含执行日志、测试结果、审查结果和交付状态。

### 10.2 已知限制

- 三端对执行阶段、执行模式、失败原因和最终状态的展示还需要统一。
- 编排图目前仍有按步骤序号推进的实现，复杂依赖关系需要进一步验证。
- `MANUAL` 且没有实际命令时，结果中的 `exitCode=-1` 缺少足够解释。
- Sandbox 的安全性依赖部署权限、Docker 配置、挂载范围和命令控制，仍需生产化加固。
- 本文档没有宣称当前环境已完成构建、部署或性能验证；具体版本发布前应分别执行三端构建和集成测试。

## 11. 开发基线

| 仓库 | 分支 | 基线提交 |
| --- | --- | --- |
| Web | `main` | `ebb05f8` |
| Android | `develop` | `f1ccde7` |
| 后端 | `develop` | `109524d` |

本文档是 V1.0 技术初稿。接口字段、状态机规则、部署方式或仓库分支发生变化时，应同步更新本文件。
