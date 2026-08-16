# Qgents 接口文档v1\.9\.2

# Qgents接口文档v1\.9\.2



**版本**：v1\.9\.2



**状态**：第 6 节 GitHub 集成接口已冻结；通知中心（§7\.1）与群列表/消息字段按 A 联调约定补全；团队邀请收件人视角与团队最近动态已落地（§19，接口表见 §5\.1）；新增两个SSE事件



**更新日期**：`2026-08-15`



---



## 1\. 范围



本文定义 Qgents 的账号、团队、项目、群聊、Skill、Memory、Agent、Task、Workspace、受控执行、Diff、测试与 MR 接口。



本轮 P0 闭环为：

```Plain Text
需求群 -> 创建 Task -> Planner 写入 TaskStep -> TaskRun 执行 -> 查看 Diff / MR
```



- 当前执行模型唯一为 `Task -> TaskStep -> TaskRun`；`orchestrationRun`、`workPackage`、`Deliverable`、`TaskDelivery` 等旧模型不属于当前版本。

    

- 项目总群与需求群统一为 Group，使用 `PROJECT_MAIN` 与 `REQUIREMENT` 区分。

    

- 客户端只能发起受控执行、读取状态和查看产物；不得直接操作 Workspace、Sandbox、Git 凭据或宿主机文件系统。

    

- 当前分支已经包含 Task、Workspace repository、TaskRun、Diff、Test Run、Dry Run 和 MR 镜像的持久模型；真实 Git commit/push 与 GitHub PR 接缝已接入受控执行服务，具体状态以交付字段为准。

    

- 通知中心已按用户维度持久化（§7\.1）；离线推送、会话个人偏好（未读数/置顶）、搜索、分支查询和 Test Run / Dry Run 历史列表不在本轮范围。

    

---



## 2\. 通用约定



**基础地址**：`https://`[`api.qgents.dpdns.org`](https://api.qgents.dpdns.org/)`/api/v1`



**请求和响应**：均为 JSON，时间为 UTC RFC 3339，所有资源 ID 为 UUID。



除注册、登录、刷新 Token、重置密码和 GitHub App 回调外，均需在请求头中携带：

```HTTP
Authorization: Bearer <accessToken>
```



**成功响应**统一为：

```JSON
{
  "data": {},
  "requestId": "req_01J..."
}
```



**失败响应**统一为：

```JSON
{
  "error": {
    "code": "PROJECT_ADMIN_REQUIRED",
    "message": "需要项目 Admin 权限",
    "details": [{"field": "role", "reason": "PROJECT_MEMBER"}]
  },
  "requestId": "req_01J..."
}
```



**HTTP 状态码**：



- `400`：参数错误

    

- `401`：未认证或 Token 失效

    

- `403`：权限不足

    

- `404`：资源不存在或不可见

    

- `409`：幂等/状态冲突

    

- `422`：业务校验失败

    

- `429`：限流

    

- `500`：服务异常

    

**幂等性**：



- 写操作必须支持 `Idempotency-Key` 头。

    

- 同一用户在 24 小时内重复提交相同键与请求体时，返回首次结果。

    

- 相同键但请求体不同时，返回 `409 IDEMPOTENCY_KEY_REUSED`。

    

**列表分页**：



- 使用 `cursor`、`limit`，默认 `30`、最大 `100`。

    

- 响应格式：

```JSON
{
    "data": [],
    "page": {"nextCursor": "cursor_...", "hasMore": true},
    "requestId": "req_..."
}
```



---



## 3\. 权限与状态



### 3\.1 角色



|角色|作用域|主要权限|
|---|---|---|
|`TEAM_OWNER`|团队|创建/归档项目；邀请、移除团队成员；管理团队级 GitHub App 集成；跨项目兜底管理|
|`TEAM_MEMBER`|团队|仅可访问被添加的项目|
|`PROJECT_ADMIN`|项目|管理项目成员（含其他 `PROJECT_ADMIN`）；绑定仓库；配置分支策略、Testset、门禁；审核 Skill/Memory；合并 MR|
|`PROJECT_MEMBER`|项目|参与需求群聊；创建 Skill/Memory 草稿；使用已发布 Skill 和已批准 Memory；查看项目内配置和 MR 状态|



**注意**：



- 服务端从 Token 和资源归属判断权限，客户端传入的 `userId`、`role` 不得作为授权依据。

    

- Project Admin 只能将已在项目中的成员提升为 Project Admin。

    

- 最后一名 Project Admin 不能被移除或降级。

    

- Project Admin 不能直接邀请团队外用户，团队邀请仅由 Team Owner 完成。

    

### 3\.2 状态枚举



|资源|状态与约束|
|---|---|
|团队邀请|`PENDING -> ACCEPTED / REVOKED / EXPIRED`|
|项目|`ACTIVE -> ARCHIVED`|
|Skill|`DRAFT -> PENDING_REVIEW -> PUBLISHED / REJECTED / ARCHIVED`|
|Memory|`DRAFT -> PENDING_REVIEW -> APPROVED / REJECTED / ARCHIVED`|
|Testset|`ENABLED / DISABLED`|
|质量检查|`PENDING / RUNNING / PASSED / FAILED`|
|Group|`PROJECT_MAIN` 或 `REQUIREMENT`；仅 `REQUIREMENT` 可归档，`PROJECT_MAIN` 永远保持 `ACTIVE`|
|Task|`PLANNING -> PENDING -> RUNNING -> WAITING_DIFF_CONFIRMATION -> DELIVERING -> SUCCEEDED / DELIVERY_FAILED / FAILED`；取消时 `RUNNING -> CANCELLING -> CANCELLED`|
|TaskStep|`PENDING -> RUNNING -> SUCCEEDED / FAILED / SKIPPED`|
|TaskRun|`QUEUED -> RUNNING -> SUCCEEDED / FAILED`；可进入 `WAITING_INPUT`、`WAITING_APPROVAL`、`BLOCKED`；取消时 `RUNNING -> CANCELLING -> CANCELLED`|
|Diff|`PENDING_REVIEW -> ACCEPTED / REJECTED`|
|试运行|`QUEUED -> RUNNING -> PASSED / FAILED / CANCELLED`|



---



## 4\. 认证与账户



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/auth/register`|匿名|邮箱注册平台账号|
|`POST`|`/auth/login`|匿名|邮箱密码登录|
|`POST`|`/auth/refresh`|匿名|轮换 refresh token|
|`POST`|`/auth/logout`|登录用户|使当前 refresh token 失效|
|`POST`|`/auth/password-reset-requests`|匿名|发起找回密码邮件|
|`POST`|`/auth/password-resets`|匿名|使用重置令牌设置新密码|
|`GET`|`/me`|登录用户|当前账户、团队和项目角色摘要|
|`PATCH`|`/me`|登录用户|修改昵称和头像|



**注册请求示例**：

```JSON
{
  "email": "member@example.com",
  "passwordKeyId": "rsa-2026-08",
  "password": "Base64(RSA-PKCS1-v1_5(password))",
  "displayName": "Lin"
}
```



**登录响应示例**：

```JSON
{
  "data": {
    "accessToken": "eyJ...",
    "accessTokenExpiresIn": 900,
    "refreshToken": "rt_...",
    "refreshTokenExpiresIn": 2592000,
    "user": {
      "id": "user-uuid",
      "email": "member@example.com",
      "displayName": "Lin"
    }
  }
}
```



### 4\.1 密码传输与存储



- 当前演示环境注册、登录和重置密码请求中的 `password`、`newPassword` 必须为前端使用平台 RSA 公钥加密后的 Base64 密文。

    

- 账号、邮箱等非敏感标识可保持明文。

    

- 客户端不得自行生成密钥。

    

- 服务端使用与 `keyId` 对应的私钥解密，解密失败、`keyId` 不存在或明文提交均返回 `400 INVALID_ENCRYPTED_PASSWORD`。

    

- 解密后的明文仅在内存中用于复杂度校验和密码验证，随后使用 Argon2id 或 BCrypt 加盐哈希后存储。

    

- 密码、私钥、重置令牌和 Token 不得写入日志、群聊内容或后续 Agent 上下文。

    

- GitHub OAuth 登录不属于本期必需能力。

    

**安全提醒**：RSA 加密仅降低演示环境中密码明文直接出现在请求体或普通网络日志中的风险，不能替代 HTTPS。部署具备域名与证书条件后，必须迁移到 HTTPS，并保留服务端密码哈希。



---



## 5\. 团队、项目与成员



### 5\.1 团队与邀请



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams`|登录用户|创建团队，创建者成为 `TEAM_OWNER`|
|`GET`|`/teams`|登录用户|获取我加入的团队|
|`GET`/`PATCH`|`/teams/{teamId}`|团队成员/Team Owner|获取/修改团队资料|
|`GET`|`/teams/{teamId}/members`|团队成员|团队成员列表|
|`POST`|`/teams/{teamId}/invitations`|Team Owner|按邮箱创建团队邀请|
|`GET`|`/teams/{teamId}/invitations`|Team Owner|查询邀请状态|
|`GET`|`/team-invitations`|登录用户|获取我收到的待处理团队邀请（分页，不返回明文 token）|
|`POST`|`/team-invitations/{reference}/accept`|登录用户|接受邀请并加入团队（`reference` 为邀请 id 或明文 token）|
|`DELETE`|`/teams/{teamId}/invitations/{invitationId}`|Team Owner|撤销未接受邀请|
|`PATCH`|`/teams/{teamId}/members/{userId}`|Team Owner|调整团队角色|
|`DELETE`|`/teams/{teamId}/members/{userId}`|Team Owner|移除团队成员|
|`GET`|`/teams/{teamId}/activities`|团队成员|团队最近动态（分页，覆盖最近 24 小时）|



**邀请请求示例**：

```JSON
{
  "email": "new-member@example.com",
  "role": "TEAM_MEMBER",
  "expiresInDays": 7
}
```



受邀邮箱尚未注册时，系统发送注册链接和邀请令牌；用户以相同邮箱注册后可接受邀请。



### 5\.2 项目与项目成员



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams/{teamId}/projects`|Team Owner|创建项目|
|`GET`|`/teams/{teamId}/projects`|团队成员|仅返回我有权限访问的项目|
|`GET`/`PATCH`|`/projects/{projectId}`|项目成员/`PROJECT_ADMIN`|获取/修改项目资料|
|`POST`|`/projects/{projectId}/archive`|Team Owner 或 Project Admin|归档项目|
|`POST`|`/projects/{projectId}/restore`|Team Owner 或 Project Admin|恢复项目|
|`GET`|`/projects/{projectId}/members`|项目成员|项目成员与角色|
|`POST`|`/projects/{projectId}/members`|Project Admin|将现有团队成员加入项目|
|`PATCH`|`/projects/{projectId}/members/{userId}`|Project Admin|在 `PROJECT_MEMBER`/`PROJECT_ADMIN` 间调整|
|`DELETE`|`/projects/{projectId}/members/{userId}`|Project Admin|从项目移除成员\<br\>|



**创建项目请求示例**：

```JSON
{
  "name": "Qgents Web",
  "description": "Web client",
  "memberIds": ["user-uuid"]
}
```



添加项目成员时 `userId` 必须已属于该团队。项目创建者自动成为 `PROJECT_ADMIN`；Team Owner 对本团队项目具有兜底管理权限。



---



## 6\. GitHub App 与项目仓库



GitHub App 授权是团队级代码访问授权，不等同于 Qgents 的成员角色。仓库无需属于 `PROJECT_ADMIN`；只要组织/仓库管理员已为团队安装并授权 Qgents GitHub App，`PROJECT_ADMIN` 即可绑定该仓库。



GitHub 集成使用三层不同 ID：



|资源|字段|实际含义|使用位置|
|---|---|---|---|
|Installation|`id`|`github_installations.id`，Qgents 本地 UUID|绑定请求、同步路径、按安装筛选|
|Installation|`providerInstallationId`|GitHub Installation 数字 ID|仅展示或排查|
|Repository|`id`|`github_repositories.id`，Qgents 本地 UUID|绑定请求中的 `repositoryId`|
|Repository|`providerRepositoryId`|GitHub Repository 数字 ID|仅展示或排查|
|ProjectRepository|`id`|`project_repositories.id`，项目仓库绑定 UUID|PATCH、DELETE，以及 Task/Workspace/Diff/MR 等开发链路|



绑定请求不得传 `providerInstallationId` 或 `providerRepositoryId`。绑定成功后，下游接口中名为 `repositoryId` / `repositoryIds` 的字段均表示 `project_repositories.id`。



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams/{teamId}/integrations/github/installations`|Team Owner|生成 GitHub App 安装跳转地址；可通过 `client` 指定回跳端|
|`GET`|`/integrations/github/callback`|GitHub/浏览器|接收安装或授权回调|
|`GET`|`/teams/{teamId}/integrations/github/installations`|Team Owner|团队已安装的 GitHub App 列表|
|`DELETE`|`/teams/{teamId}/integrations/github/installations/{installationId}`|Team Owner|解除团队安装记录|
|`POST`|`/teams/{teamId}/integrations/github/installations/{installationId}/sync`|Team Owner|手动刷新 Installation 与授权仓库元数据|
|`GET`|`/teams/{teamId}/integrations/github/repositories`|Team Owner 或 Project Admin|查询该团队被授权的仓库\<br\>|
|`GET`|`/projects/{projectId}/repositories`|项目成员|获取项目已绑定仓库|
|`POST`|`/projects/{projectId}/repositories`|Project Admin|绑定团队已授权仓库|
|`PATCH`|`/projects/{projectId}/repositories/{projectRepositoryId}`|Project Admin|保留的项目绑定更新接口；第一版前端不提供修改默认分支入口|
|`DELETE`|`/projects/{projectId}/repositories/{projectRepositoryId}`|Project Admin|解绑仓库，成功返回 `204`|



### GitHub 枚举与状态

```Plain Text
Installation.status = ACTIVE | SUSPENDED | DELETED
Installation.accountType = USER | ORGANIZATION
Repository.visibility = PUBLIC | PRIVATE | INTERNAL
Repository.authorizationStatus = AUTHORIZED | REVOKED
```



- Installation 不使用 `EXPIRED`；短期 Installation Token 过期不代表 App Installation 过期。

    

- `archived=true` 表示 GitHub 仓库已归档；`authorizationStatus=REVOKED` 表示 GitHub App 已无权访问。

    

- 第一版不返回 `authorizedRepositoryCount`，客户端可按授权仓库的 `installationId` 统计。

    ### 安装与元数据刷新

    

    

    

    发起安装请求必须携带 `Idempotency-Key`。

    

    

    

    安装请求通过查询参数指定发起端，`client` 只允许以下值：

    

    

    

    |    参数|    类型|    必填|    说明|
    |---|---|---|---|
    |    `client`|    `WEB` 或 `MOBILE`|    否|    回调成功后的前端类型，省略时默认为 `WEB`|

    

    

    

    Web 端请求：

    

    ```HTTP
    POST /teams/{teamId}/integrations/github/installations?client=WEB
    Idempotency-Key: <unique-key>
    Authorization: Bearer <accessToken>
    ```

    

    

    

    移动端请求：

    

    ```HTTP
    POST /teams/{teamId}/integrations/github/installations?client=MOBILE
    Idempotency-Key: <unique-key>
    Authorization: Bearer <accessToken>
    ```

    

    

    

    `client` 不是回跳 URL，前端不得传入任意 URL。后端会将该值写入签名 `state`，GitHub 回调时由后端验证并决定回跳地址。

    

    

    

    成功返回：

    

    ```JSON
    {
      "data": {
        "installationUrl": "https://github.com/apps/qgents/installations/new?state=...",
        "expiresAt": "2026-08-13T11:00:00Z"
      },
      "requestId": "req_01J..."
    }
    ```

    

    

    

    Installation 列表项：

    

    ```JSON
    {
      "id": "installation-local-uuid",
      "providerInstallationId": 12345678,
      "accountLogin": "Yjingwen-svg",
      "accountType": "ORGANIZATION",
      "status": "ACTIVE",
      "installedAt": "2026-08-01T08:00:00Z",
      "metadataSyncedAt": "2026-08-13T10:00:00Z"
    }
    ```

    

    

    

    callback 固定为：

    

    ```HTTP
    GET /integrations/github/callback?installation_id=...&setup_action=...&state=...
    ```

    

    

    

    其中 `setup_action` 由 GitHub 回调携带，常见值为 `install` 或 `update`，后端不要求前端额外处理。

    

    

    

    后端校验签名 `state`，保存 Installation 并刷新授权仓库元数据。`state` 中包含客户端类型，例如：

    

    ```JSON
    {
      "sub": "<teamId>",
      "client": "WEB"
    }
    ```

    

    

    

    旧版本没有 `client` 字段的 `state` 默认按 `WEB` 处理。

    

    

    

    成功返回：

    

    ```HTTP
    302 Location: {FRONTEND_URL_WEB}/app/integrations/github?teamId={teamId}&installed=1
    ```

    

    

    

    当安装请求使用 `client=MOBILE` 时，回跳地址为：

    

    ```HTTP
    302 Location: {FRONTEND_URL_MOBILE}/app/integrations/github?teamId={teamId}&installed=1
    ```

    

    **归属冲突回跳**：同一 GitHub 账号已绑定到其他团队时，回调不保存本次安装，回跳地址携带 `conflict` 与 `message` 参数（WEB/MOBILE 均同构）：

    

    ```HTTP
    302 Location: {FRONTEND_URL_WEB}/app/integrations/github?teamId={teamId}&installed=0&conflict=GITHUB_INSTALLATION_TEAM_CONFLICT&message={URL编码的中文提示}
    ```

    

    前端应检测 `conflict` 参数并展示 `message` 中的提示，然后清理该参数；`installed` 为 `0` 表示安装未完成。

    

    > **业务规则：一个 GitHub 账号只能授权给一个团队。** GitHub App 的 Installation 是「账号对 App」级别唯一的，一个 GitHub 账号对 qgents App 只有一个安装，因此只能归属一个团队。若该账号已绑定其他团队，从当前团队发起安装时会进入已有的 Installation（GitHub 侧不会新建），无法重复绑定；如需更换团队，须先到原团队解绑或卸载 GitHub App 后重新安装。仓库变更 webhook 不携带来源团队信息，故「在 GitHub 配置页直接增删仓库」作用于原 Installation，后端无法据 webhook 阻断该操作，前端应在安装入口提示该规则。
    > 
    > 

    

    

    

    后端部署配置：

    

    ```Plain Text
    FRONTEND_URL_WEB=https://qgents.dpdns.org
    FRONTEND_URL_MOBILE=https://mobile.qgents.dpdns.org
    ```

    

    

    

    Web 和移动端共用同一个 GitHub App 和同一个 callback 地址：

    

    ```Plain Text
    https://api.qgents.dpdns.org/api/v1/integrations/github/callback
    ```

    

    

    

    区别只在于创建安装链接时传递的 `client` 参数。

    

    

    

    手动刷新请求中的 `{installationId}` 是 Installation 本地 `id`。该操作只刷新 GitHub Installation 与授权仓库元数据，不执行 clone/fetch，也不改变 Workspace 状态；成功返回 `200` 和刷新后的 Installation 对象。客户端随后重新 GET Installation 列表和授权仓库列表。

    

    

    

    解除团队 Installation 成功返回 `204 No Content`；仍被项目仓库绑定引用时返回 `409 GITHUB_INSTALLATION_IN_USE`。

    



### 授权仓库



授权仓库列表项：

```JSON
{
  "id": "repository-local-uuid",
  "installationId": "installation-local-uuid",
  "providerRepositoryId": 987654321,
  "fullName": "Yjingwen-svg/qgents-web",
  "githubUrl": "https://github.com/Yjingwen-svg/qgents-web",
  "defaultBranch": "main",
  "visibility": "PRIVATE",
  "archived": false,
  "authorizationStatus": "AUTHORIZED",
  "metadataSyncedAt": "2026-08-13T10:00:00Z"
}
```



客户端只允许绑定 `authorizationStatus=AUTHORIZED`、`archived=false`、`defaultBranch` 非空且对应 Installation 为 `ACTIVE` 的仓库。`defaultBranch` 缺失时不得回退为固定的 `main`。



### 项目仓库绑定



项目仓库绑定列表项及绑定成功响应：

```JSON
{
  "id": "project-repository-binding-uuid",
  "repositoryId": "repository-local-uuid",
  "installationId": "installation-local-uuid",
  "providerRepositoryId": 987654321,
  "fullName": "Yjingwen-svg/qgents-web",
  "githubUrl": "https://github.com/Yjingwen-svg/qgents-web",
  "defaultBranch": "main",
  "displayName": "qgents-web",
  "authorizationStatus": "AUTHORIZED",
  "metadataSyncedAt": "2026-08-13T10:00:00Z",
  "boundAt": "2026-08-13T10:00:00Z"
}
```



项目绑定 DTO 不返回代码含义的 `syncStatus`、`lastSyncedAt` 或 `syncError`。项目上下文已由路径确定，不重复返回 `boundProjectId` / `boundProjectName`。第一版不提供按授权仓库批量反查所有已绑定项目的接口。



绑定仓库请求：



一个项目可绑定多个仓库，前端如需批量绑定，可循环调用本接口，每次传入不同的 repositoryId 即可。

```JSON
{
  "installationId": "installation-local-uuid",
  "repositoryId": "repository-local-uuid",
  "defaultBranch": "main",
  "displayName": "qgents-web"
}
```



`defaultBranch` 是可选兼容字段。后端始终以授权仓库元数据中的真实默认分支为可信来源，忽略客户端覆盖值；真实默认分支缺失时拒绝绑定。成功返回 `200` 和完整 ProjectRepository 对象，其中 `id` 是后续 PATCH、DELETE 和 Task 创建使用的项目仓库绑定 ID。



PATCH 与 DELETE 路径中的 `{projectRepositoryId}` 均为 ProjectRepository 响应的 `id`，不是授权仓库 `repositoryId` 或 GitHub 数字 ID。第一版前端不调用 PATCH 修改默认分支。DELETE 成功返回 `204 No Content`，客户端随后重新 GET 项目绑定列表。



### GitHub 写接口幂等与主要错误



生成安装链接、删除 Installation、手动刷新、绑定项目仓库、PATCH 项目仓库和解绑项目仓库均要求 `Idempotency-Key`。callback 不要求前端传该请求头。



|HTTP|错误码|含义|
|---|---|---|
|`400`|`INVALID_GITHUB_INSTALLATION_STATE`|callback state 无效或过期|
|`400`|`INVALID_REQUEST`|`client` 不是 `WEB` 或 `MOBILE`|
|`400`|`IDEMPOTENCY_KEY_REQUIRED`|缺少幂等键|
|`403`|`GITHUB_REPOSITORY_ACCESS_DENIED`|无团队或项目操作权限|
|`404`|`GITHUB_RESOURCE_NOT_FOUND`|Installation、仓库或项目绑定不存在|
|`409`|`GITHUB_INSTALLATION_IN_USE`|Installation 仍被项目绑定引用|
|`409`|`GITHUB_INSTALLATION_TEAM_CONFLICT`|Installation 已关联其他 Team|
|`409`|`PROJECT_REPOSITORY_ALREADY_BOUND`|项目已绑定该仓库|
|`409`|`PROJECT_REPOSITORY_REFERENCED_BY_BRANCH_CONFIG`|项目绑定仍被分支配置引用|
|`409`|`IDEMPOTENCY_KEY_REUSED`|同一幂等键用于不同请求|
|`422`|`REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT`|仓库不在项目所属 Team 的有效授权范围|
|`502`|`GITHUB_API_UNAVAILABLE`|GitHub API 暂时不可用|



callback 只保存 Installation 元数据和授权仓库范围。GitHub App 私钥、安装访问令牌和用户 PAT 永不通过前端 API 返回；后续代码操作必须使用服务端受控 Git 服务或短期权限，凭据不得进入前端、Agent、日志或 Workspace 配置。



### 6\.1 分支策略与质量门禁



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`/`PUT`|`/projects/{projectId}/repositories/{projectRepositoryId}/branch-policies/{branch}`|项目成员/Project Admin|查询/配置受保护分支策略|
|`GET`/`PUT`|`/projects/{projectId}/repositories/{projectRepositoryId}/quality-gates/{branch}`|项目成员/Project Admin|查询/配置目标分支的门禁|
|**质量门禁示例**：||||

```JSON
{
  "requirePullRequest": true,
  "requiredChecks": ["TESTSET", "AI_REVIEW", "DRY_RUN", "CQ_PLUS_ONE"],
  "requiredTestsetIds": ["testset-uuid"],
  "minimumHumanApprovals": 1,
  "allowDirectPush": false
}
```



`requiredTestsetIds` 是不可被普通成员绕过的强制测试集。门禁规则定义期望条件，检查的实际运行和回写由后续执行/同步服务处理。



### 6\.10 GitHub Webhook 接收（公开接口，无 JWT）

新增公开接口，供 GitHub App 事件投递。接口不携带 Qgents JWT，安全依据为 `X-Hub-Signature-256`、`X-GitHub-Event`、`X-GitHub-Delivery` 和 Webhook Secret。

|方法|路径|认证|说明|
|---|---|---|---|
|`POST`|`/api/v1/integrations/github/webhook`|`X-Hub-Signature-256` 验签（无 Qgents JWT）|接收 ping/installation/installation\_repositories/pull\_request 事件，同步本地仓库/MR 镜像并发布项目级 SSE|
|**安全与幂等约定**：||||

- Secret 由 `GITHUB_WEBHOOK_SECRET` 环境变量注入；未配置时接口 fail\-closed 返回 503，不得为联调提供空 Secret 或固定默认值。

- 验签使用 HMAC\-SHA256 与常量时间比较，输入为原始 body 字节；Secret 不出现在日志、异常、SSE 或响应中。

- 以 `X-GitHub-Delivery` 作为唯一键防重复处理：相同 delivery 已处理返回 200 幂等；`FAILED` 的重投必须重新通过原始 body 验签后才允许再次处理；处理中并发请求返回 503 让 GitHub 重试。

- 本轮采用同步处理：业务同步完成后才返回 200；不引入 202、消息队列或未定义的后台消费者。

- 事务内不得调用 GitHub、Worker 或其他外部 HTTP；仓库详情补齐在事务外通过受控 GitHub 客户端完成，补齐失败记录 FAILED 等待重投，不把半成品标记为 AUTHORIZED。

**返回策略**：

|情况|HTTP|说明|
|---|---|---|
|签名正确并已处理|200|已完成本次同步并记录 PROCESSED/IGNORED|
|相同 `X-GitHub-Delivery` 已处理|200|幂等返回，不重复写入|
|签名缺失或不匹配|401|不落业务数据|
|Header/body 不完整或 JSON 非法|400|不落业务数据|
|Secret 未配置|503|fail\-closed，不当作验签失败|
|请求体超过上限|413|不落投递业务数据|
|暂时无法处理（仓库详情补齐失败等）|500|本次不标记为成功，允许 GitHub 重投|
|**第一版事件白名单**：|||
|GitHub 事件|处理内容|SSE|
|\-\-\-|\-\-\-|\-\-\-|
|`ping`|校验配置可用性，记录成功投递|否|
|`installation`|`created`/`unsuspend`/`new_permissions_accepted` → ACTIVE，`suspend` → SUSPENDED，`deleted` → DELETED|仅能确定团队/项目归属时|
|`installation_repositories`|`added`/`removed` 按 provider repository id upsert 或标记 REVOKED，校验 installation 一致|按受影响项目分别发布|
|`pull_request`|`opened`/`reopened`/`synchronize` → OPEN，`closed`\+merged → MERGED，`closed`\+未 merged → CLOSED；按 provider repository \+ PR number 幂等更新全部项目绑定 MR 镜像|每个成功更新的项目一次|
|**投递审计表 ****`github_webhook_deliveries`****：以 ****`provider_delivery_id`**** 唯一，状态 ****`RECEIVED → PROCESSED/IGNORED/FAILED`****，只存原始 body 的 SHA\-256 摘要；****`attempt_count`**** 记录同一 delivery 实际处理次数；****`RECEIVED`**** 超过 5 分钟视为处理中断，允许后续投递重新领取处理；已完成记录保留 30 天后由定时任务清理，超过 30 天的孤儿 ****`RECEIVED`**** 一并清除。**|||
|**SSE 事件契约补充**：|||

```Plain Text
github-installation.updated  -> { installationId, status }
github-repository.updated    -> { installationId, repositoryId, authorizationStatus, archived }
merge-request.updated        -> { projectId, mergeRequestId, repositoryId, number, status, headCommit, providerUpdatedAt, qualityGateStatus, timestamp }
```

上述 `installationId`/`repositoryId`/`mergeRequestId` 均为 Qgents 本地 UUID；事件只发送给能通过本地绑定关系确定的 Project。`merge-request.updated` 使用 `number`（不另造 `providerNumber`），`headCommit` 为真实完整 SHA；SSE 事件 `id` 仍使用项目内 `sequenceNo`，payload 不伪造 `sequence`。客户端收到事件后重新拉取 MR 详情或列表，不把 SSE payload 当作完整 DTO。

---

## 7\. 统一 Group 与消息







项目可有一个项目主讨论群和多个需求群，统一建模为 Group。创建项目时服务端自动创建唯一的 `PROJECT_MAIN` Group；它不可归档或删除。`REQUIREMENT` Group 是上下文与协作边界，可引用多个仓库；它不代表 Git `main`，也不天然生成或绑定分支。







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/groups`|项目成员|项目总群与需求群列表，按最近活跃排序|
|`POST`|`/projects/{projectId}/groups`|项目成员|创建 `REQUIREMENT` Group|
|`GET`/`PATCH`|`/projects/{projectId}/groups/{groupId}`|项目成员/创建者或 Project Admin|获取群详情（含 `memberCount` 成员数）、修改需求群标题、描述和关联仓库|
|`GET`|`/projects/{projectId}/groups/{groupId}/members`|项目成员|获取群成员列表（= 项目成员 \+ 参与群聊的 Agent，响应含 `memberType`：USER/AGENT）；群内成员平等，无角色|
|`POST`|`/projects/{projectId}/groups/{groupId}/leave`|项目成员|当前用户退出群聊（即移出本项目成员，移出后失去该项目全部群/消息/资源的访问权限）|
|`POST`|`/projects/{projectId}/groups/{groupId}/archive`|创建者或 Project Admin|归档需求群\<br\>|
|`GET`|`/projects/{projectId}/groups/{groupId}/messages`|项目成员|游标拉取消息|
|`POST`|`/projects/{projectId}/groups/{groupId}/messages`|项目成员|发送文本、代码块、图片、文件或引用\<br\>|
|`POST`|`/projects/{projectId}/attachments`|项目成员|创建对象存储直传凭证\<br\>|
|`GET`|`/projects/{projectId}/groups/{groupId}/context`|项目成员|组装群聊上下文（需求 \+ 近期消息 \+ 关联仓库 \+ 已发布 Skill \+ 已批准 Memory），供 Agent 作为输入；`limit` 参数控制近期消息条数（默认 50，上限 200）|







> **群详情说明**：
> 
> 
> 
> - 群详情与改名已由 `GET`/`PATCH /projects/{projectId}/groups/{groupId}` 覆盖。
> 
>     
> 
> - 详情响应包含 `memberCount`（群成员数 = 项目成员数 \+ 群内 Agent 数）。
> 
>     
> 
> - 群成员 = 项目成员 \+ 参与群聊的 Agent，群内成员平等、无角色区分。
> 
>     
> 
> - Agent 通过服务端内部 `sendAsAgent` 首次回群后自动成为群参与者（`group_agents` 表），成员响应含 `memberType`（USER/AGENT）。
> 
>     
> 
> - `POST .../leave`（退出群聊）即当前用户移出本项目成员，移出后失去对该项目全部群/消息/资源的访问权限；最后一名 Project Admin 不可退群（与 §3\.1 一致）。
> 
>     
> 
>     
> 
> 







**创建需求群请求示例**：







```JSON
{
  "title": "登录功能",
  "description": "讨论账号与登录体验",
  "repositoryIds": ["project-repository-binding-uuid"],
  "type": "REQUIREMENT"
}
```







> `POST /groups` 只接受 `REQUIREMENT` 或省略 `type`；传入 `PROJECT_MAIN` 返回 `422 SYSTEM_GROUP_MANAGED`。
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 







> 项目总群可收发消息，但仅用于项目级讨论和结构化动态；执行任务请求中的 `groupId` 必须指向同一项目下状态为 `ACTIVE` 的 `REQUIREMENT` Group。
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 
> 







**发送消息请求示例**：







```JSON
{
  "type": "TEXT",
  "content": {"text": "登录接口需要支持邮箱和密码。"},
  "mentions": [
    {"type": "USER", "id": "user-uuid"},
    {"type": "AGENT", "id": "agent-uuid"}
  ],
  "replyToId": null,
  "clientMessageId": "cmsg_01J..."
}
```



> `mentions` 为对象数组 `Mention[]`，每项 `{ "type": "USER" | "AGENT", "id": <UUID> }`（type 必填枚举 USER/AGENT，id 为被提及的用户或 Agent ID）；不提及传 `null` 或 `[]`。响应 `MessageResponse.mentions` 同构回显。
> 
> 







**消息类型说明**：







- `type` 为 `TEXT`、`CODE`、`IMAGE`、`FILE`、`DIFF`、`TASK_STATUS`、`SYSTEM` 或 `QUOTE`。

    

    

    

- 服务端写入单调递增 `sequence`；`clientMessageId` 在同一需求群内唯一，断线重试返回原消息。

    

    

    

- `DIFF`（Diff 卡片）content 至少含 `diffId`，如 `{"diffId":"...","title":"实现邮箱登录","additions":12,"deletions":3}`。

    

    

    

- `TASK_STATUS`（任务状态卡片）content 至少含 `taskId`、`status`，如 `{"taskId":"...","status":"RUNNING","node":"DEVELOPER","message":"正在执行测试"}`。

    

    

    

- `IMAGE`/`FILE`（图片/文件消息）content 至少含 `url`（展示/下载地址），如 `{"url":"/projects/{projectId}/attachments/{attachmentId}/content"}`。`url` 填附件稳定展示地址（服务端强校验 url 必填），获取方式见 §18\.5。

    

    

    

- 消息响应 `senderType` 为 `USER`/`AGENT`/`SYSTEM`：用户发送为 USER，Agent 通过服务端内部 `sendAsAgent` 回群为 AGENT，系统消息为 SYSTEM。

    

    

    

- Agent 可参与项目群聊（回群消息），但私聊与 Agent 好友不在本期范围。

    

    

    

**群列表 DTO 补充**（`GET /projects/{id}/groups`，A 联调约定 §2）：







- `latestActivityAt`：后端返回，ISO8601 UTC 字符串，作为列表排序依据；从未发言时以创建时间兜底。

    

    

    

- `latestMessage`：后端返回，列表摘要对象 `{ "senderName": string|null, "text": string|null, "type": string|null }`；`senderName` 为用户昵称或 Agent 名称（SYSTEM 消息为空），`text` 为最新消息文本（仅 TEXT/QUOTE 等含 `$.text` 的类型可取到，其余为空），`type` 为最新消息类型，取值与消息类型枚举一致（`TEXT`/`CODE`/`IMAGE`/`FILE`/`SYSTEM`/`QUOTE`/`DIFF`/`TASK_STATUS`），客户端可据此对 `IMAGE`/`FILE` 等无文本消息展示 `[图片]`/`[文件]` 摘要。

    

    

    

- `unreadCount`、`isPinned`：本轮后端不返回，由前端 localStorage 兜底（会话个人偏好不在本轮）。

    

    

    

- 归档判断：使用 `status`（`ACTIVE`/`ARCHIVED`），不设独立 `isArchived` 字段。

    

    

    

**消息 DTO 补充**（`GET/POST .../messages`，A 联调约定 §3）：







- `senderId`：后端返回；USER 消息 = `userId`，AGENT 消息 = `agentId`，前端据此判断是否本人发送。

    

    

    

- `senderName`：后端不返回，前端用 `senderId` 反查群成员列表（`GET .../members`）获取 `displayName`。

    

    

    

- `sequence`：后端返回，单调递增，作为分页游标。

    

    

    

- `clientMessageId`：前端发送时携带，服务端在同一需求群内幂等去重，断线重试返回原消息。

    



### 7\.1 通知中心







通知中心按用户维度持久化已读状态与历史列表；SSE（§12\.1）只负责「实时提醒」（新事件铃铛提醒），不承担历史列表与已读状态。任务类通知由事件触发写入，接收人为任务发起人；团队/项目成员类通知（`INVITED`/`TEAM_JOINED`/`PROJECT_ADDED`）由对应业务接口直接写入，接收人为被邀请用户 / 邀请者 / 被加入项目的成员。







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/notifications`|登录用户|返回当前用户通知列表（含 `isRead`），按 `createdAt` 倒序；演示阶段不分页，一次性返回全量|
|`POST`|`/notifications/{id}/read`|登录用户|标记单条已读（幂等）|
|`POST`|`/notifications/read-all`|登录用户|全部已读（幂等）|







**通知字段（****`Notification`****）：**







|字段|类型|说明|
|---|---|---|
|`id`|string|通知 id|
|`kind`|string|`TASK_COMPLETED` / `TASK_FAILED` / `AGENT_INPUT_REQUIRED` / `DELIVERABLE_PENDING` / `MR_PENDING` / `INVITED` / `TEAM_JOINED` / `PROJECT_ADDED`|
|`title`|string|一行标题|
|`description`|string?|补充说明|
|`isRead`|boolean|是否已读|
|`createdAt`|string|ISO8601 UTC|
|`projectId`|string?|所属项目，点击跳转用|
|`groupId`|string?|来源需求群|
|`resourceId`|string?|关联资源 id（taskId / mrId / diffId），跳转定位用|







**通知数据来源（后端在对应事件或业务路径发生时写入通知表）：**



|触发来源|通知 kind|
|---|---|
|`task.updated`（SUCCEEDED / FAILED）|`TASK_COMPLETED` / `TASK_FAILED`|
|`delivery.completed`|`TASK_COMPLETED`|
|`delivery.failed` / `task.diff-review.failed`|`TASK_FAILED`|
|`input-required` / `approval-required`|`AGENT_INPUT_REQUIRED`|
|`diff.created`|`DELIVERABLE_PENDING`|
|`merge-request.updated`|`MR_PENDING`|
|团队邀请创建 `POST /teams/{teamId}/invitations`（被邀请邮箱已注册）|`INVITED`|
|团队邀请接受 `POST /team-invitations/{reference}/accept`|`TEAM_JOINED`|
|项目加成员 `POST /projects/{projectId}/members`|`PROJECT_ADDED`|



> 成员类通知无对应 SSE 事件，由业务接口直接写入：`INVITED` 接收人为被邀请用户（未注册邮箱无 `userId`，仅走邮件邀请）；`TEAM_JOINED` 接收人为邀请者；`PROJECT_ADDED` 接收人为被加入项目的成员。标题示例：「你被邀请加入团队 \{团队名\}」「\{用户\} 已加入团队 \{团队名\}」「你被加入项目 \{项目名\}」，`resourceId` 分别为团队 ID / 团队 ID / 项目 ID。
> 
> 



### 7\.2 群搜索（第三批，暂不实现）







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`<br>|`/search?q={q}&type=groups`|登录用户|按关键字搜索当前用户可访问的群（群名匹配），返回群摘要列表；该能力属于后续批次，本期不实现|







---



## 8\. 共享 Skill



Skill 是项目需求群内可复用的能力片段，例如规范、提示词、操作指引或工具调用约束。成员先创建自己的 `PRIVATE` Skill，并可装配给自己拥有的 Agent；Project Admin 可将其发布为 `PROJECT_SHARED`，供该项目成员的 Agent 使用。Skill 不是 Memory，不能承载未经确认的客观事实。



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/skills`|项目成员|查询 Skill，支持状态、标签过滤|
|`POST`|`/projects/{projectId}/skills`|项目成员|创建草稿 Skill|
|`GET`/`PATCH`|`/projects/{projectId}/skills/{skillId}`|项目成员/创建者或 Project Admin|获取/编辑草稿或审核中内容|
|`POST`|`/projects/{projectId}/skills/{skillId}/submit-review`|创建者或 Project Admin|提交审核|
|`POST`|`/projects/{projectId}/skills/{skillId}/approve`|Project Admin|发布 Skill|
|`POST`|`/projects/{projectId}/skills/{skillId}/reject`|Project Admin|拒绝并给出原因|
|`POST`|`/projects/{projectId}/skills/{skillId}/archive`|Project Admin|下线已发布 Skill|



**创建 Skill 请求示例**：

```JSON
{
  "name": "Java API 规范",
  "content": "Controller 仅处理 HTTP 协议，业务逻辑放入 Service。",
  "tags": ["java", "backend"],
  "visibility": "PRIVATE"
}
```



被共享的 Skill 在后续执行时可由 Agent 卡片或工作流节点引用；Agent 仅获得其当前项目中有权使用的 Skill，归档后不再装配到新执行中。



---



## 9\. 共享 Memory



Memory 是经人工确认后供项目复用的知识，不是原始聊天记录。AI 可以根据多条消息生成草稿，但不得直接批准或发布；后续 Agent 只能按相关性和标签检索已批准的 Memory，不能默认注入全部内容。



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/memories`|项目成员|查询 Memory，默认仅 `APPROVED`|
|`POST`|`/projects/{projectId}/memories`|项目成员|手动创建草稿|
|`POST`|`/projects/{projectId}/memories/drafts`|项目成员|根据选中的群聊消息生成 AI 草稿|
|`GET`/`PATCH`|`/projects/{projectId}/memories/{memoryId}`|项目成员/创建者或 Project Admin|获取/编辑草稿或审核中内容|
|`POST`|`/projects/{projectId}/memories/{memoryId}/submit-review`|创建者或 Project Admin|提交审核|
|`POST`|`/projects/{projectId}/memories/{memoryId}/approve`|Project Admin|批准并发布|
|`POST`|`/projects/{projectId}/memories/{memoryId}/reject`|Project Admin|拒绝并给出原因|
|`POST`|`/projects/{projectId}/memories/{memoryId}/archive`|Project Admin|归档 Memory|



**手动创建草稿请求示例**：

```JSON
{
  "title": "密码存储约定",
  "content": "密码仅存储 bcrypt 哈希，登录时使用 bcrypt.compare 校验。",
  "category": "ENGINEERING_DECISION",
  "tags": ["auth", "security"]
}
```



**群聊生成草稿请求示例**：

```JSON
{
  "sourceMessages": [
    {"groupId": "group-uuid", "messageId": "message-uuid"},
    {"groupId": "group-uuid", "messageId": "message-uuid-2"}
  ],
  "instruction": "沉淀为项目认证安全约定"
}
```



Memory 响应必须包含 `creator`、`reviewer`、`reviewedAt`、`category`、`tags` 与 `sources`。当前支持 `MANUAL`、`MESSAGE` 来源；未来任务/Diff 来源可扩展，但不在本版创建接口范围。



---



## 10\. Testset



Testset 是项目自建、可复用的测试配置，而不是特殊语法。它可表示单元、接口或集成测试；本期只管理配置，实际执行由后续系统承担。



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/testsets`|项目成员|查询 Testset，支持仓库与启用状态过滤|
|`POST`|`/projects/{projectId}/testsets`|Project Admin|创建 Testset|
|`GET`/`PATCH`|`/projects/{projectId}/testsets/{testsetId}`|项目成员/Project Admin|获取/修改配置|
|`POST`|`/projects/{projectId}/testsets/{testsetId}/enable`|Project Admin|启用|
|`POST`|`/projects/{projectId}/testsets/{testsetId}/disable`|Project Admin|禁用|
|`DELETE`|`/projects/{projectId}/testsets/{testsetId}`|Project Admin|删除未被门禁引用的 Testset|



**创建 Testset 请求示例**：

```JSON
{
  "name": "后端单元测试",
  "repositoryId": "project-repository-binding-uuid",
  "scopeTags": ["backend", "unit"],
  "command": "./mvnw test",
  "timeoutSeconds": 900,
  "passRule": {"type": "EXIT_CODE", "expected": 0},
  "acceptanceNotes": "登录成功、错误密码和不存在用户均需覆盖。"
}
```



Project Member 可查看并在未来任务计划中选择 `ENABLED` Testset；受保护分支的必选 Testset 以质量门禁配置为准，成员不能替换或跳过。



---



## 11\. Agent、团队工作流与任务拆分



系统随每个团队提供不可修改的“新手大礼包”：`AgentOrchestrator`、`Planner`、`Developer`、`Tester`、`Reviewer` 及内置默认代码交付工作流。用户不配置任何内容时，在需求群中 @ `AgentOrchestrator` 即可运行该默认流程：`Planner` 拆分 TaskStep → `Developer` 实现 → `Tester` 执行 Testset → `Reviewer` 审查 → 质量门禁汇总。



团队成员可创建仅自己可用的 Agent；发布后成为团队可用资源。产品界面至少展示每张 Agent 身份卡的昵称、头像、角色、能力标签、可用状态、创建者和可访问的 Skill 摘要；不得向其他成员泄露私有提示词或凭据。



### 11\.1 个人与团队 Agent



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/teams/{teamId}/agents`|团队成员|查询系统 Agent、本人私有 Agent 和团队已发布 Agent|
|`POST`|`/teams/{teamId}/agents`|团队成员|创建自己的 `PRIVATE` Agent|
|`GET`/`PATCH`|`/teams/{teamId}/agents/{agentId}`|可见成员/创建者|获取 Agent 卡；创建者编辑自己的 Agent|
|`POST`|`/teams/{teamId}/agents/{agentId}/publish`|创建者|发布为 `TEAM`（团队共享），供团队成员使用|
|`POST`|`/teams/{teamId}/agents/{agentId}/unpublish`|创建者或 Team Owner|收回为私有 Agent|
|`POST`|`/teams/{teamId}/agents/{agentId}/archive`|创建者或 Team Owner|下线 Agent，已运行任务不受影响|
|`GET`|`/projects/{projectId}/agent-skill-bindings/{agentId}`|项目成员|读取指定 Agent 在当前项目的 Skill 绑定集|
|`PUT`|`/projects/{projectId}/agent-skill-bindings/{agentId}`|Agent 创建者或 Project Admin|全量替换绑定集；空数组清空（幂等，无需 Idempotency\-Key）|



**创建 Agent 请求示例**：

```JSON
{
  "name": "Java 后端 Agent",
  "avatar": "https://cdn.example.com/avatars/java.png",
  "role": "DEVELOPER",
  "capabilities": ["java", "spring-boot", "api"],
  "prompt": "遵循项目 API 规范和测试要求。"
}
```



**Agent 卡响应示例**（`GET`/`PATCH /teams/{teamId}/agents/{agentId}` 返回）：

```JSON
{
  "id": "agent-uuid",
  "name": "Java 后端 Agent",
  "avatar": "https://cdn.example.com/avatars/java.png",
  "role": "DEVELOPER",
  "capabilities": ["java", "spring-boot", "api"],
  "prompt": "遵循项目 API 规范和测试要求。",
  "visibility": "PRIVATE",
  "status": "ACTIVE",
  "createdBy": "user-uuid"
}
```



身份卡字段由 `agents` 表持久化（产品需求 §2\.3）：`name`（昵称）、`avatar`（头像）、`role`（角色标签）、`capabilities`（能力标签，JSON 数组）、`prompt`（系统提示词）。私有提示词与私有可见性仅创建者可见，不得向其他成员泄露。



`role` 为 `ORCHESTRATOR`、`PLANNER`、`DEVELOPER`、`TESTER`、`REVIEWER` 或 `GENERAL`。角色和能力是身份卡上的调度线索，不是权限绕过手段；工作流节点可按角色选择 Agent，调度器再从可用 Agent 中选择实际执行者。



#### 11\.1\.1 Agent\-Skill 绑定



绑定将项目内可用 Skill 装配到 Team 级 Agent，同一 Agent 在不同项目可绑定不同技能集；绑定按项目隔离。可绑定 Skill：本人 `PRIVATE`（未归档）或已发布的 `PROJECT_SHARED`。修改权限：Agent 创建者或 Project Admin；读取权限：项目成员。



**请求体（PUT，全量替换，空数组清空）**：

```JSON
{"skillIds": ["skill-uuid-1", "skill-uuid-2"]}
```



**响应体**：

```JSON
{
  "agentId": "agent-uuid",
  "skillIds": ["skill-uuid-1"],
  "skills": [
    {"id": "skill-uuid-1", "name": "Java API 规范", "visibility": "PROJECT_SHARED", "status": "PUBLISHED"}
  ],
  "updatedAt": "2026-08-12T10:00:00Z"
}
```



|状态码|错误码|条件|
|---|---|---|
|`403`|`AGENT_BINDING_FORBIDDEN`|修改他人 `PRIVATE` Agent 且非 Project Admin|
|`404`|`AGENT_NOT_FOUND`/`SKILL_NOT_FOUND`|Agent/Skill 不存在|
|`409`|`AGENT_SKILL_DUPLICATE`|请求体 `skillIds` 存在重复|
|`422`|`AGENT_NOT_IN_PROJECT_TEAM`|Agent 不属于当前项目的 Team|
|`422`|`AGENT_NOT_ACTIVE`|Agent 未启用|
|`422`|`SKILL_NOT_IN_PROJECT`|Skill 不属于当前项目|
|`422`|`SKILL_NOT_BINDABLE`|Skill 已归档/他人 `PRIVATE`/`PROJECT_SHARED` 未发布|



### 11\.2 团队工作流模板



本版本仅提供不可修改的 `system-default-code-delivery` 工作流。自定义团队工作流模板及其配置接口不在本版本范围。



### 11\.3 Task、Workspace 与执行计划



系统必须将代码实现、测试和审查分派给不同 Agent 角色。当前公开模型以用户可见的 `Task` 为顶层任务，Planner 将计划写入 `TaskStep`，每个步骤可产生多次 `TaskRun`；禁止重新引入 WorkPackage、Deliverable 或 orchestration 兼容层。



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/projects/{projectId}/tasks`|项目成员|从当前项目的 ACTIVE REQUIREMENT 群创建 Task；可创建新 Workspace 或显式复用前序 Task 的 Workspace|
|`GET`|`/projects/{projectId}/tasks`|项目成员|查询当前项目可见的 Task，支持 `groupId`、`status`、`createdBy` 过滤与游标分页|
|`GET`|`/projects/{projectId}/tasks/{taskId}`|项目成员|获取 Task、Workspace 与 repository 范围|
|`POST`|`/projects/{projectId}/tasks/{taskId}/steps`|发起人或 Project Admin|写入 Planner 生成的有依赖 TaskStep 计划|
|`GET`|`/projects/{projectId}/tasks/{taskId}/steps`|项目成员|查询 Task 的 TaskStep 计划列表|
|`POST`|`/projects/{projectId}/tasks/{taskId}/steps/{stepId}/replace-agent`|发起人或 Project Admin|仅在步骤仍为 `PENDING` 时更换 Agent|
|`POST`|`/projects/{projectId}/tasks/{taskId}/cancel`|发起人或 Project Admin|取消整个 Task（202 异步受理）：`PLANNING`/`PENDING` 同步置 `CANCELLED`，`RUNNING` 置 `CANCELLING` 由执行器在安全检查点终止；终态返回 `409 TASK_NOT_CANCELLABLE`|



**创建 Task 请求示例**：

```JSON
{
  "requirementGroupId": "group-uuid",
  "title": "实现邮箱登录",
  "requirement": "前后端都要支持，并完成测试。",
  "repositoryIds": ["project-repository-binding-uuid"],
  "baseRef": "main"
}
```



创建 Task 返回 `201 Created`。新 Task 至少绑定一个 repository，并只关联一个 Workspace；继续任务必须同时提交 `workspaceId` 与 `continuationOfTaskId`，且二者与当前 Task 属于同一 Project。新 Workspace 可包含多个 repository worktree，客户端不能直接操作 Workspace 或 Sandbox。



**重要说明**：创建请求中的 `repositoryIds` 与 baseRef 属于随之创建的 **Workspace**（worktree），不属于 Task 本身；`tasks` 表不存这两列。`repositoryIds` 每一项必须是第 6 节 ProjectRepository 响应的 `id`，即 `project_repositories.id`，不得传 `github_repositories.id` 或 GitHub provider 数字 ID。前端创建 Task 时带上它们是告诉系统用哪些项目仓库绑定、以哪个分支为基准初始化 Workspace。



**启动方式**：新 Task 模型**不再有** `startMode`（AUTO/MANUAL），也没有工作包的 `start/pause/resume` 接口；Task 创建后由 Planner 写入 TaskStep 并自动执行，取消统一走 `POST /tasks/{taskId}/cancel`。默认工作流节点定义（Planner → Developer → Tester → Reviewer）由 Agent 编排引擎（后端1）内置的 `system-default-code-delivery` 提供，前端只消费节点状态，不定义节点。`POST /tasks/{taskId}/steps`（发起人或 Project Admin）是**手动编排入口**：默认由 Planner（系统 Agent）自动写入步骤计划，也保留用户手动写入依赖、角色与验收条件的 TaskStep 的能力（该完整手动编排能力暂未实现，接口先预留）；调度器仍按步骤 `role` 分配可用 Agent。



Task 状态为 `PLANNING/PENDING/RUNNING/WAITING_DIFF_CONFIRMATION/DELIVERING/SUCCEEDED/DELIVERY_FAILED/FAILED/CANCELLING/CANCELLED`；TaskStep 状态为 `PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED`；TaskRun 状态为 `QUEUED/RUNNING/SUCCEEDED/FAILED/WAITING_INPUT/WAITING_APPROVAL/BLOCKED/CANCELLING/CANCELLED` 等受控状态。`WAITING_DIFF_CONFIRMATION` 表示最终 Diff 已生成、等待用户决定；`DELIVERY_FAILED` 表示总确认已接受，但至少一个仓库交付失败，可重试。一个 Workspace 同一时刻只能有一个有效写入者；复用 Workspace 的后续 Task 必须显式引用前序 Task，不能仅凭聊天上下文复用。



**Task 响应示例**（创建/详情/列表项均为此结构）：

```JSON
{
  "id": "task-uuid",
  "projectId": "project-uuid",
  "requirementGroupId": "group-uuid",
  "triggerMessageId": "message-uuid",
  "title": "实现邮箱登录",
  "requirement": "前后端都要支持，并完成测试。",
  "status": "RUNNING",
  "deliveryMode": "DIFF_FIRST",
  "workspaceId": "workspace-uuid",
  "workspaceStatus": "READY",
  "continuationOfTaskId": null,
  "repositoryIds": ["project-repository-binding-uuid"],
  "repositories": [
    {
      "repositoryId": "project-repository-binding-uuid",
      "workspacePath": "workspaces/workspace-uuid",
      "baseCommit": "abc123",
      "sourceBranch": "feat/login-api",
      "headCommit": null
    }
  ],
  "createdBy": "user-uuid",
  "createdAt": "2026-08-12T10:00:00Z",
  "updatedAt": "2026-08-12T10:00:00Z"
}
```



**TaskStep 响应示例**（`GET /projects/{projectId}/tasks/{taskId}/steps` 列表项）：

```JSON
{
  "id": "step-uuid",
  "taskId": "task-uuid",
  "role": "DEVELOPER",
  "agentId": "agent-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "baseRef": "main",
  "dependencies": ["step-uuid-2"],
  "testsetIds": ["testset-uuid"],
  "status": "PENDING",
  "acceptanceNotes": "登录成功、错误密码和不存在用户均需覆盖。"
}
```



**关联字段说明**（前端展示用）：



- `TaskRun.agentId` 指向实际执行该步骤的 Agent（agents 表，§11\.1）。

    

- `TaskStep.role` 声明所需工作流角色（ORCHESTRATOR/PLANNER/DEVELOPER/TESTER/REVIEWER），调度器按角色挑选可用 Agent。

    

- Agent 可装配的 Skill 通过 `PUT /projects/{projectId}/agent-skill-bindings/{agentId}` 维护。

    

- `TaskStep.testsetIds` 与质量门禁 `requiredTestsetIds` 引用 Testset（§6\.1/§10）。

    

Workspace 是 Project 内持久化的 Git 工作目录，Workspace repository 必须记录真实 base commit、source branch 和 head commit；Sandbox 仅是临时执行环境，销毁后未提交 Workspace 修改仍需保留。



---



## 12\. 受控执行、Diff 与实时事件



本节记录 TaskStep 的实际执行尝试为 `TaskRun`。TaskRun 必须关联 Task 与 TaskStep；它不是新的顶层任务，也不单独向用户产出 Diff 或 MR。用户可发起重试、读取受控日志和查看 Task 级结果，但不能直接创建、进入或操作 Workspace/Sandbox。



所有本节 `POST` 接口均要求 `Idempotency-Key`，成功受理时返回 `202 Accepted` 和资源摘要；列表接口复用第 2 节的 `cursor` 与 `limit`。服务端必须校验路径中的 `projectId`、关联资源与当前用户在同一项目中，禁止仅通过 UUID 查询资源。



### 12\.1 实时事件流

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/events`|Project Member|建立项目级 SSE 连接，接收状态和产物事件|
|该接口的 `Content-Type` 为 `text/event-stream`，不套用 JSON 成功响应。客户端可通过 `Last-Event-ID` 断线续传；服务端每 15 秒发送心跳，并至少保留 24 小时事件。续传点过期时返回 `409 EVENT_CURSOR_EXPIRED`，客户端应重新拉取相关资源。||||

**SSE 事件示例**：

```Plain Text
id: evt_01J...
event: task-run.step.progress
data: {"projectId":"project-uuid","taskId":"task-uuid","stepId":"step-uuid","taskRunId":"task-run-uuid","node":"DEVELOPER","sequence":12,"content":"正在执行测试","timestamp":"2026-08-10T12:00:00Z"}
```

**事件类型**：

- `task.updated`

- `task-step.updated`

- `task-run.updated`

- `task-run.step.progress`

- `input-required`

- `approval-required`

- `test-run.updated`

- `dry-run.updated`

- `diff.created`

- `task.artifact.created`

- `task-run.artifact.created`

- `diff-review.created`

- `task.awaiting-diff-confirmation`

- `diff-review.confirmed`

- `diff-review.rejected`

- `delivery.repository.updated`

- `delivery.failed`

- `delivery.completed`

- `task.diff-review.failed`

- `diff-review.skipped`

- `merge-request.updated`

事件仅用于刷新界面；客户端恢复连接或收到乱序事件后必须以相应的查询接口为准。受控日志不得包含 Token、密码、GitHub 安装令牌、私钥或未脱敏的环境变量。

SSE 事件 `id` 即项目内单调递增 `sequenceNo`，作为 `Last-Event-ID` 续传游标；输入与审批事件必须包含 `inputRequestId`。

**各事件 Payload 示例**：

`task.updated`（Task 状态变化）：

```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "requirementGroupId": "group-uuid",
  "status": "RUNNING",
  "workspaceId": "workspace-uuid",
  "timestamp": "2026-08-12T10:00:00Z"
}
```

`task-step.updated`（TaskStep 状态变化）：

```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "sequenceNo": 1,
  "status": "RUNNING",
  "timestamp": "2026-08-12T10:00:00Z"
}
```

`task-run.updated`（TaskRun 状态变化）：

```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "taskRunId": "task-run-uuid",
  "status": "RUNNING",
  "sequence": 0,
  "timestamp": "2026-08-12T10:00:00Z"
}
```

`input-required` / `approval-required`（人机输入/审批）：

```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "taskRunId": "task-run-uuid",
  "inputRequestId": "request-uuid",
  "kind": "INPUT",
  "status": "PENDING",
  "prompt": "请补充验收说明",
  "timestamp": "2026-08-12T10:00:00Z"
}
```

其中 `kind` 为 `INPUT`（`input-required`）或 `APPROVAL`（`approval-required`）；`inputRequestId` 即事件 `resourceId`。

`diff.created`（Diff 快照创建）：

```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "diffId": "diff-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "sourceBranch": "feat/login-api",
  "headCommit": "abc123",
  "status": "PENDING_REVIEW",
  "timestamp": "2026-08-12T10:00:00Z"
}
```

Diff 尚未产生真实提交时 `headCommit` 键省略（payload 中不出现该键）。

### 12\.2 任务运行与执行上下文

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/tasks/{taskId}/task-runs`|Project Member|查询 Task 的执行记录|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}`|Project Member|获取单次运行的状态、关联步骤和产物摘要|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/retry`|发起人或 Project Admin|为失败或已取消的运行创建一次新的 `TaskRun`|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/cancel`|发起人或 Project Admin|取消未完成运行，服务端仅在安全检查点终止|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}/logs`|Project Member|游标读取已脱敏的执行日志|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}/execution-context`|Project Member|读取 Workspace 与 Sandbox 的只读状态摘要|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests`|Project Member|查询运行期间发起的人机输入请求|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/reply`|发起人或 Project Admin|回答 `WAITING_INPUT` 请求|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/approve`|Project Admin|批准 `WAITING_APPROVAL` 请求|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/reject`|Project Admin|拒绝 `WAITING_APPROVAL` 请求|
|`retry` 只接受状态为 `FAILED`、`CANCELLED` 或 `BLOCKED` 的运行；原运行不可重置。响应中的新运行应包含 `retryOfTaskRunId`。||||
|输入请求最小响应包含 `id`、`taskRunId`、`kind`、`status`、`prompt`、可选 `options` 与 `createdAt`；回复请求为 `{"answer":{"value":"main"}}`，批准或拒绝请求为 `{"reason":"允许在受控 Sandbox 内执行测试"}`。回复或批准后服务端才可恢复 `RUNNING`；拒绝后服务端进入 `BLOCKED` 或安全取消，客户端不得直接改写运行状态。||||
|`execution-context` 仅返回 `workspaceId`、`sandboxStatus`、`repositoryId`、`baseRef`、`headRef`、`startedAt` 与 `expiresAt`，不得返回宿主机路径、容器控制入口或任何凭据。||||
|**TaskRun 响应示例**（`GET /tasks/{taskId}/task-runs` 列表项 / `GET /task-runs/{taskRunId}` 详情）：||||

```JSON
{
  "id": "task-run-uuid",
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "agentId": "agent-uuid",
  "role": "DEVELOPER",
  "status": "RUNNING",
  "retryOfTaskRunId": null,
  "artifactSummary": {"diffs": {"count": 0, "byStatus": {}}},
  "startedAt": "2026-08-12T10:00:00Z",
  "finishedAt": null,
  "durationMs": 10234,
  "createdAt": "2026-08-12T10:00:00Z",
  "updatedAt": "2026-08-12T10:00:00Z"
}
```

`GET /tasks/{taskId}/task-runs` 列表项只含摘要字段（`id`/`projectId`/`taskId`/`taskStepId`/`agentId`/`role`/`status`/`retryOfTaskRunId`/`createdAt`/`updatedAt`），不含执行时序与产物；详情（`GET /task-runs/{taskRunId}`）才含 `startedAt`/`finishedAt`/`durationMs`/`artifactSummary`。`durationMs` 由 `finishedAt-startedAt` 派生，任一端为空时为 `null`（未开始运行勿用 0 兜底）。

TaskRun 执行步骤查询方式：

- **实时进度**：通过 SSE `task-run.step.progress` 事件（§12\.1，含 `node`/`sequence`/`content`）推送。

- **历史步骤**：通过 `GET /task-runs/{taskRunId}/logs` 游标读取脱敏日志。

**日志条目（****`LogEntryResponse`****）示例**：

```JSON
{
  "id": "log-uuid",
  "sequence": 12,
  "node": "git",
  "content": "checkout base",
  "timestamp": "2026-08-12T10:00:00Z"
}
```

日志分页游标取上页最后一条的 `sequence`；`node` 为产生日志的执行节点名，单节点运行为空。如需步骤级清单回看，`TaskRun` 详情可附 `steps` 数组（节点状态 `PENDING/RUNNING/PASSED/FAILED/SKIPPED/CANCELLED`，含 `node`/`status`/`startedAt`/`finishedAt`/`durationMs`/可选 `errorCode`），由执行服务提供。

步骤响应中的节点状态为 `PENDING`、`RUNNING`、`PASSED`、`FAILED`、`SKIPPED` 或 `CANCELLED`，并至少包含 `node`、`status`、`startedAt`、`finishedAt`、`durationMs` 与可选的 `errorCode`。运行中的 Task 收到取消请求时，服务端仅在安全检查点停止；不可中断步骤结束前状态保持 `RUNNING` 或 `CANCELLING`。

### 12\.3 Diff 与审查意见

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/diffs`|项目成员|项目级 Diff 列表，支持 `taskId` 过滤与游标分页|
|`GET`|`/projects/{projectId}/diffs/{diffId}`|项目成员|查询 Diff 的变更统计和关联运行摘要|
|`GET`|`/projects/{projectId}/diffs/{diffId}/files`|项目成员|读取文件、hunk 和二进制文件摘要|
|`GET`/`POST`|`/projects/{projectId}/diffs/{diffId}/comments`|项目成员|查询或添加 Diff 审查意见|
|`POST`|`/projects/{projectId}/diffs/{diffId}/accept`|发起人或 Project Admin|接受普通单 Diff；属于 Diff Review Batch 时返回 `409 DIFF_BATCH_REVIEW_REQUIRED`|
|`POST`|`/projects/{projectId}/diffs/{diffId}/reject`|发起人或 Project Admin|拒绝普通单 Diff；属于 Diff Review Batch 时返回 `409 DIFF_BATCH_REVIEW_REQUIRED`|
|`GET`|`/projects/{projectId}/tasks/{taskId}/diff-review`|项目成员|查询 Task 级最终 Diff Review Batch|
|`GET`|`/projects/{projectId}/tasks/{taskId}/diff-review/diffs/{diffId}/patch`|项目成员|读取最终 Diff 批次中指定 Diff 的不可变 Patch|
|`POST`|`/projects/{projectId}/tasks/{taskId}/diff-review/confirm`|Task 发起人或 Project Admin|确认整个最终 Diff 批次并开始逐仓库交付|
|`POST`|`/projects/{projectId}/tasks/{taskId}/diff-review/reject`|Task 发起人或 Project Admin|拒绝整个最终 Diff 批次|
|`POST`|`/projects/{projectId}/tasks/{taskId}/diff-review/retry-delivery`|Task 发起人或 Project Admin|重试尚未成功交付的仓库|

最终 Diff 使用 Task 级 Diff Review Batch 作为审核入口。

属于批次的 Diff 不得调用单 Diff 的 accept/reject 接口。

三个批次写接口都要求携带 Idempotency\-Key。

创建 Diff 由受控执行服务完成，客户端不得伪造其关联的测试结果或文件状态。

reject 请求为 \{"reason":"请补充错误密码场景测试"\}。

行级评论应包含 path、side、line 或 hunkId、body，并绑定 Diff 快照，避免 Diff 更新后评论指向错误代码。

accept 接受 Diff 时由受控 Git 执行器基于被审查快照创建真实 Git 提交，不绕过目标分支的质量门禁，也不等同于合并。

Diff 列表项（GET /diffs，DiffListItemResponse）：

```JSON
{
  "id": "diff-uuid",
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskRunId": "task-run-uuid",
  "taskStepId": "step-uuid",
  "requirementGroupId": "group-uuid",
  "workspaceId": "workspace-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "baseCommit": "abc123",
  "sourceBranch": "feat/login-api",
  "headCommit": null,
  "status": "PENDING_REVIEW",
  "changeStats": {"files": 2, "additions": 10, "deletions": 2},
  "createdAt": "2026-08-12T10:00:00Z"
}
```

**Diff 详情**（`GET /diffs/{diffId}`，`DiffResponse`）在列表项基础上增加 `workingTreeHash`、`snapshotKey`、`reviewedBy`、`reviewReason`、`reviewedAt`、`updatedAt`。`taskRunId`/`taskStepId` 记录产出该 Diff 的运行与步骤，`requirementGroupId` 由其所属 Task 派生。

### 12\.4 Test Run 与 Dry Run

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/projects/{projectId}/test-runs`|Project Member|对指定提交或 Task 发起已启用 Testset 的受控运行|
|`GET`|`/projects/{projectId}/test-runs/{testRunId}`|Project Member|获取测试运行状态、用例摘要和产物引用|
|`POST`|`/projects/{projectId}/dry-runs`|Project Member|针对源分支和目标分支发起合并前试运行|
|`GET`|`/projects/{projectId}/dry-runs/{dryRunId}/report`|Project Member|获取试运行报告和冲突、测试摘要|

`test-runs` 请求必须提供 `repositoryId`，并且提供 `taskId` 或 `ref` 之一；`testsetIds` 必须属于该仓库且为 `ENABLED`。

`dry-runs` 请求必须提供 `repositoryId`、`sourceRef`、`targetBranch`，可选关联 `taskId`。

受保护分支所需的 Testset 由第 6\.1 节质量门禁决定，调用方不能通过传入较少的 `testsetIds` 跳过它们。

---



## 13\. MR、审查与质量状态



MR 属于仓库，Qgents 将其镜像为项目内记录。创建、同步、人工 CQ 审查和合并均通过本节受控接口触发服务端操作；客户端不持有 GitHub 凭据，也不能直接写入检查结果或绕过分支策略。



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/merge-requests`|Project Member|查询项目关联 MR，支持仓库、需求群、状态过滤|
|`POST`|`/projects/{projectId}/merge-requests`|Project Member|基于已接受并在远端核验的 Diff 创建 MR|
|`GET`|`/projects/{projectId}/merge-requests/{mergeRequestId}`|Project Member|查询 MR、关联需求群、检查与审查摘要|
|`GET`|`/projects/{projectId}/merge-requests/{mergeRequestId}/checks`|Project Member|查询门禁检查详情|
|`GET`|`/projects/{projectId}/merge-requests/{mergeRequestId}/reviews`|Project Member|查询人工与 AI 审查摘要|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/sync`|Project Member|触发从 GitHub 同步 MR 最新状态|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/cq-approvals`|Project Member（非作者）|提交一次 CQ\+1 审查|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/cq-rejections`|Project Member（非作者）|拒绝 CQ 并给出修改意见|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/merge`|Project Admin|通过质量门禁后执行合并|



**创建 MR 请求示例**：

```JSON
{
  "taskId": "task-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "targetBranch": "main",
  "title": "实现邮箱登录"
}
```



服务端从 Task 的持久化 Workspace repository 取得源分支和提交 SHA，并校验源已提交推送、分支仍存在且调用方有项目访问权；不接受客户端提交的 GitHub Token、提交 SHA 或门禁结果。



`sync`、`merge` 和两个 CQ 写操作同样需要 `Idempotency-Key`。



CQ 审查者不得是 MR 作者、Diff 创建者或代表其执行的 Agent；服务端须记录审查者、时间、所审查的 `headCommit` 和理由。新提交推送到 MR 后，旧 CQ\+1 是否失效由目标分支门禁配置决定，并反映到 `qualityGate`。



**MR 详情的最小响应示例**：

```JSON
{
  "data": {
    "id": "mr-uuid",
    "repositoryId": "project-repository-binding-uuid",
    "groupIds": ["group-uuid"],
    "provider": "GITHUB",
    "number": 42,
    "sourceBranch": "feature/login-api",
    "targetBranch": "main",
    "status": "OPEN",
    "headCommit": "abc123",
    "qualityGate": {"status": "PENDING", "requiredChecks": ["TESTSET", "AI_REVIEW", "DRY_RUN", "CQ_PLUS_ONE"]}
  }
}
```



正式合并前的业务规则为：关联 Diff/提交通过指定 Testset，AI 审查与 dry\-run 通过，至少获得一次有效人工 `CQ+1`，且由 `PROJECT_ADMIN` 按仓库策略确认合并。是否满足这些规则由 `qualityGate.status` 表示。



`merge` 在条件不满足时返回 `409 QUALITY_GATE_NOT_PASSED`；接口不存在跳过门禁、伪造检查结果或以手工结果覆盖自动检查的能力。



---



## 14\. 后续接口边界



以下增强能力暂不提供公开接口，客户端不得假定其路径或字段：



- 自定义团队工作流模板及其节点配置；

    

- 用户直接操作 Workspace、Docker Engine、Sandbox 生命周期、文件读取/写入；

    

- Git 分支创建、推送和 Patch 应用的直接操作；

    

- 绕过质量门禁或手工改写 Testset、AI Review、Dry Run 结果；

    

- WebSocket、离线同步与移动端推送（SSE 见第 12\.1 节）。

    

这些能力接入时必须复用本文件的项目、需求群、仓库、Testset、Memory、Skill、Task/TaskStep 和质量门禁资源模型，并遵循“不得由单一 Agent 独自完成完整交付”的产品约束。



## 15\. v1\.3\.0 更新：任务级总 Diff 确认与多仓库交付



### 15\.1 Task 状态流转更新



- PLANNING \-\> PENDING \-\> RUNNING \-\> WAITING\_DIFF\_CONFIRMATION \-\> DELIVERING \-\> SUCCEEDED / DELIVERY\_FAILED / FAILED

    

- 新增字段 deliveryMode：支持 DIFF\_FIRST 或 MR\_FIRST（当前默认且仅实现 DIFF\_FIRST）。

    

### 15\.2 交付模式



- 任务完成 Review 之后，如果处于 DIFF\_FIRST，会自动创建 DiffReviewBatch。

    

- 用户可以查看所有的 Repository Diff。

    

- 用户只进行一次总 Diff 确认。后端随后逐仓库执行 Commit、Push 与 PR 创建；跨仓库不构成分布式事务，允许部分成功并支持重试。

    

### 15\.3 总 Diff 查询与审核接口



#### 15\.3\.1 获取任务级最终 Diff



**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review**



- **响应**: `200 OK`，返回 `DiffReviewBatchResponse`，字段和空批次行为见 §15\.6。

    

#### 15\.3\.2 确认总 Diff



**POST /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/confirm**



- **Headers**: 必须携带 Idempotency\-Key。

    

- **响应**: `200 OK`，返回确认后的 `DiffReviewBatchResponse`。仅任务发起者或 Project Admin 可确认；确认前会重新校验全部仓库的 HEAD 与 Diff hash。校验通过后进入 `DELIVERING`，再逐仓库交付。

    

#### 15\.3\.3 拒绝总 Diff



**POST /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/reject**



- **Headers**: 必须携带 Idempotency\-Key。

    

- **请求体**: `{"reason":"请补充错误密码场景测试"}`；`reason` 必填，最多 4000 个字符。

    

- **响应**: `200 OK`，返回 `reviewStatus=REJECTED` 的 `DiffReviewBatchResponse`。拒绝后不进行 Commit、Push 或 PR 创建。

    

#### 15\.3\.4 重试失败的交付



**POST /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/retry\-delivery**



- **Headers**: 必须携带 Idempotency\-Key。

    

- **响应**: `200 OK`，返回重试后的 `DiffReviewBatchResponse`。仅接受已确认且交付未完成的批次；已为 `MR_CREATED` 的仓库不会重复交付。

    

#### 15\.3\.5 读取单仓库原始 Patch



**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/diffs/\{diffId\}/patch**



- **权限**: 项目成员。

    

- **响应**: `200 OK`，返回 `{"diffId":"...","repositoryId":"...","patch":"..."}`。调用方只能读取当前任务最终 Diff 批次内的 Patch。

    

### 15\.4 SSE 事件补充



以下事件的 Payload 均为脱敏摘要，格式如：



`projectId`、`taskId` 和事件对应资源 ID 是所有新增事件的最小定位字段；具体 payload 见 §15\.6。



- diff\-review\.created: 总 Diff 快照生成完毕

    

- task\.awaiting\-diff\-confirmation: 任务等待用户确认 Diff

    

- diff\-review\.confirmed: 用户确认了总 Diff

    

- diff\-review\.rejected: 用户拒绝了总 Diff

    

- delivery\.repository\.updated: 某仓库已完成交付，状态为 `MR_CREATED`

    

- delivery\.failed: 交付失败

    

- delivery\.completed: 所有仓库交付完成

    

### 15\.5 单仓库 Diff 接口调整



- 如果单 Diff 属于某个 Diff Review Batch，那么调用原有的 `POST /diffs/{diffId}/accept` 或 `POST /diffs/{diffId}/reject` 将返回 `409 DIFF_BATCH_REVIEW_REQUIRED`，要求用户走总 Diff 确认接口。

    

### 15\.6 前端对接契约



#### 15\.6\.1 执行产物时间线



|方法|路径|权限|响应|
|---|---|---|---|
|`GET`|`/api/v1/projects/{projectId}/tasks/{taskId}/artifacts`|项目成员|`200 OK`，按 `sequenceNo` 升序返回 Artifact 数组|



Artifact 响应结构：



\{



"id": "artifact\-uuid",



"taskId": "task\-uuid",



"taskRunId": "task\-run\-uuid",



"taskStepId": "step\-uuid",



"sequenceNo": 2,



"artifactType": "CODING",



"summary": \{"title": "完成登录接口实现"\},



"createdAt": "2026\-08\-14T10:00:00Z"



\}



`artifactType` 取值为 `PLAN`、`CODING`、`TESTING`、`REVIEWING`。`PLAN` 只关联 `taskId`，因此 `taskRunId` 和 `taskStepId` 为 `null`；其余类型同时关联 Task、TaskRun 与 TaskStep。`summary` 是已脱敏的展示摘要，不包含命令原文、凭据、环境变量或宿主机绝对路径。



#### 15\.6\.2 最终 Diff 批次



`GET /diff-review` 在最终 Diff 尚未生成时返回 `404 DIFF_REVIEW_NOT_FOUND`，不是空数组或 `204`。批次状态枚举如下：



|字段|取值|前端含义|
|---|---|---|
|`reviewStatus`|`PENDING_CONFIRMATION`、`ACCEPTED`、`REJECTED`|是否仍可确认或拒绝|
|`deliveryStatus`|`NOT_STARTED`、`DELIVERING`、`DELIVERED`、`PARTIALLY_DELIVERED`、`FAILED`|总体交付结果|
|单仓库持久化状态|`NOT_STARTED`、`COMMITTED`、`MR_CREATED`、`FAILED`|仅用于后端交付与重试；当前未包含在 `DiffListItemResponse` 中|



当前 `DiffReviewBatchResponse`：



\{



"id": "review\-batch\-uuid",



"taskId": "task\-uuid",



"reviewStatus": "PENDING\_CONFIRMATION",



"deliveryStatus": "NOT\_STARTED",



"aggregateHash": "sha256\-\.\.\.",



"reviewReason": null,



"diffs": \[



\{



"id": "diff\-uuid",



"projectId": "project\-uuid",



"taskId": "task\-uuid",



"taskRunId": "task\-run\-uuid",



"taskStepId": "step\-uuid",



"requirementGroupId": null,



"workspaceId": "workspace\-uuid",



"repositoryId": "project\-repository\-binding\-uuid",



"baseCommit": "abc123",



"sourceBranch": "feat/login\-api",



"headCommit": "def456",



"status": "PENDING\_REVIEW",



"changeStats": \{"files": 2, "additions": 10, "deletions": 2\},



"createdAt": "2026\-08\-14T10:00:00Z"



\}



\]



\}



批次内 Diff 与普通单 Diff 的关联由服务端 `reviewBatchId` 持久化字段表达。该字段当前不在公开 `DiffResponse` 或 `DiffListItemResponse` 中；前端应以 Task 级 `diff-review` 接口作为最终 Diff 审核入口。



`repositoryDeliveries[]` 已纳入公开响应，返回每个仓库的交付状态、失败原因和真实 MR 摘要；批次为 `PARTIALLY_DELIVERED` 时，前端应以该数组恢复成功与失败仓库。



#### 15\.6\.3 确认、拒绝与重试



三个写接口都由全局幂等过滤器要求 `Idempotency-Key`。缺失时返回 `400 IDEMPOTENCY_KEY_REQUIRED`；同一键但请求体不同返回 `409 IDEMPOTENCY_KEY_REUSED`；同一键与相同请求会回放首次成功响应。



|接口|成功响应|主要业务错误|
|---|---|---|
|`POST .../confirm`|`200` \+ `DiffReviewBatchResponse`|`403 DIFF_REVIEW_FORBIDDEN`、`404 DIFF_REVIEW_NOT_FOUND`、`409 DIFF_REVIEW_NOT_DECIDABLE`、`409 DIFF_SNAPSHOT_STALE`|
|`POST .../reject`|`200` \+ `DiffReviewBatchResponse`|`400 DIFF_REJECT_REASON_REQUIRED`、`403 DIFF_REVIEW_FORBIDDEN`、`409 DIFF_REVIEW_NOT_DECIDABLE`|
|`POST .../retry-delivery`|`200` \+ `DiffReviewBatchResponse`|`403 DIFF_REVIEW_FORBIDDEN`、`409 DIFF_DELIVERY_NOT_RETRYABLE`|



确认成功仅表示用户已接受整个快照；后端仍会逐仓库交付。前端必须根据返回的总体 `deliveryStatus` 和后续 SSE 刷新 Task 与批次，不得将 `ACCEPTED` 当作“所有 PR 已创建”。



#### 15\.6\.4 新增 SSE 事件与 payload

SSE 事件仍使用 §12\.1 的事件信封；以下为 `data` 中的业务 payload 必填字段。事件到达后可重新请求对应的 Task、Artifact 或 Diff Review 资源。

|事件|payload 必填字段|推荐刷新资源|
|---|---|---|
|`task.artifact.created`|`taskId`、`artifactId`、`sequenceNo`、`artifactType`|Task Artifact 时间线|
|`task-run.artifact.created`|以上字段，加 `taskRunId`、`taskStepId`|Task Artifact 时间线、TaskRun|
|`diff.created`|`projectId`、`taskId`、`diffId`、`repositoryId`、`status`|Task Diff Review|
|`diff-review.created`|`projectId`、`taskId`、`reviewBatchId`、`reviewStatus`、`aggregateHash`|Task、Task Diff Review|
|`task.awaiting-diff-confirmation`|`projectId`、`taskId`、`reviewBatchId`|Task、Task Diff Review|
|`diff-review.confirmed`|`projectId`、`taskId`、`reviewBatchId`|Task、Task Diff Review|
|`diff-review.rejected`|`projectId`、`taskId`、`reviewBatchId`|Task、Task Diff Review|
|`delivery.repository.updated`|`projectId`、`taskId`、`diffId`、`deliveryStatus`|Task Diff Review、MR 列表|
|`delivery.completed`|`projectId`、`taskId`、`reviewBatchId`、`deliveryStatus`|Task、Task Diff Review、MR 列表|
|`delivery.failed`|`projectId`、`taskId`、`reviewBatchId`、`deliveryStatus`|Task、Task Diff Review、MR 列表|
|`task.diff-review.failed`|`projectId`、`taskId`、`reviewBatchId`、`reason`|Task、Task Diff Review、错误提示|
|`diff-review.skipped`|`projectId`、`taskId`、`reason`|Task\<br\>|
|其中：|||

- `task.diff-review.failed` 事件的 `reason` 固定为 `DIFF_SNAPSHOT_STALE`，表示用户确认时工作区已发生变化，当前 Diff 快照失效，前端应刷新 Task 和 Diff Review，并停止当前批次的确认操作。

- `diff-review.skipped` 事件的 `reason` 固定为 `FINAL_DIFF_EMPTY`，表示任务执行成功但没有检测到代码变更，前端无需展示 Diff 审核面板；此时查询 Diff Review 返回 `404 DIFF_REVIEW_NOT_FOUND` 属于正常业务结果。



## 16\. v1\.4\.0 更新：任务中心与任务详情展示字段



基线 v1\.3\.0。本小节补充任务中心/任务详情/执行流程/总 Diff 交付摘要的展示契约，



使前端一次拿到卡片所需摘要，避免逐条 N\+1 查询。所有摘要 DTO 均为只读展示，不携带敏感信息。



### 16\.1 任务列表（增强）



**GET /api/v1/projects/\{projectId\}/tasks**



新增查询参数（原有参数沿用）：



|参数|类型|说明|
|---|---|---|
|`groupId`|UUID|按需求群筛选（可选）|
|`status`|string|按任务状态筛选（可选）|
|`createdBy`|UUID|按发起人筛选（可选）|
|`repositoryId`|UUID|按项目仓库绑定 ID 筛选（SQL 层 IN 子查询，避免只过滤当前页漏数据）|
|`cursor`|string|游标分页（上一页最后一项 `id`）|
|`limit`|int|每页条数，默认 20，最大 100|



响应改为分页结构 `{ data, page, requestId }`，`data` 为 `TaskListItemResponse[]`：

```JSON
{
  "id": "task-uuid",
  "displayCode": "T-1024",
  "projectId": "project-uuid",
  "title": "登录接口实现",
  "requirementSummary": "实现账号密码登录并签发 JWT",
  "status": "RUNNING",
  "priority": null,
  "deliveryMode": "DIFF_FIRST",
  "requirementGroup": { "id": "group-uuid", "name": "登录功能", "status": "ACTIVE" },
  "createdByUser": { "id": "user-uuid", "displayName": "陈同学", "avatarUrl": null },
  "repositories": [
    { "repositoryId": "binding-uuid", "name": "auth-service", "fullName": "qgents/auth-service",
      "provider": "GITHUB", "defaultBranch": "main", "baseRef": "main", "baseCommit": "abc123",
      "sourceBranch": "feat/login-api", "headCommit": null }
  ],
  "executionSummary": { "totalSteps": 4, "pendingSteps": 1, "runningSteps": 1, "waitingSteps": 0,
    "blockedSteps": 0, "succeededSteps": 2, "failedSteps": 0, "currentStage": "DEVELOPER",
    "currentStageTitle": "后端接口开发", "requiresUserAction": false },
  "attention": null,
  "createdAt": "2026-08-14T10:00:00Z",
  "updatedAt": "2026-08-14T10:42:00Z"
}
```



字段约定：



- `displayCode`：项目内唯一、创建后不可变的展示编号（`T-<序号>`）；所有 API 关联仍使用 `id`。

    

- `priority`：**后端明确不提供**，恒为 `null`，前端应移除该展示。

    

- `requirementSummary`：服务端截断的纯文本摘要（≤200 字符，不含 HTML）。

    

- `executionSummary`：由 TaskStep 真实状态聚合；`waitingSteps`/`blockedSteps` 取每步骤最新 TaskRun 状态

    

（WAITING\_INPUT/WAITING\_APPROVAL→等待，BLOCKED→阻塞）；`currentStage` 使用正式 TaskStep role；



`requiresUserAction = attention != null`。不返回伪造的进度百分比。



- `attention`：待处理事项，无则 `null`。`kind` 枚举：

    

`INPUT_REQUIRED/APPROVAL_REQUIRED/BLOCKED/EXECUTION_FAILED/DIFF_CONFIRMATION_REQUIRED/DELIVERY_FAILED`。



- `repositories[].repositoryId` 恒为 `project_repositories.id`；`baseRef` 为 Workspace 初始化基准分支

    

（当前以项目默认分支表示）；`headCommit` 未提交前为 `null`。



### 16\.2 任务详情



**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}** 返回 `TaskDetailResponse`（在列表项基础上补充）：



- `requirement`：完整需求文本。

    

- `acceptanceCriteria[]`：Task 级验收标准 `{ id, title, description, status }`，status 枚举

    

`PENDING/SATISFIED/UNSATISFIED/NOT_APPLICABLE`；验收状态由后端结果或检查资源决定。



**当前尚无验收标准生产者，返回空数组（契约先行，不伪造）。**



- `workspace`：`{ id, status, repositories[] }`，Workspace 生命周期状态 \+ 仓库摘要。

    

- `capabilities`：当前用户对当前任务的操作能力（后端派生，前端不猜测）：

    

`canCancel`、`canReplacePendingStepAgent`、`canConfirmDiffReview`、`canRejectDiffReview`、`canRetryDelivery`，



各能力可携带 `xxxDisabledReason` 错误码（如 `TASK_NOT_CANCELLABLE`、`DIFF_REVIEW_NOT_DECIDABLE`）。



- `artifactSummary`：`{ total, byType }`，按产物类型（PLAN/CODING/TESTING/REVIEWING）计数。

    

- `diffReviewSummary`：`{ available, reviewStatus, deliveryStatus, repositoryCount, filesChanged, additions, deletions }`；

    

`available=false` 表示无总 Diff 批次。



- `sourceMessage`：`{ id, sender: UserSummary, textExcerpt, createdAt }`，由 `triggerMessageId` 派生；

    

仅返回脱敏文本摘要，附件走现有消息/附件权限接口。



- `triggerMessageId`：来源消息 ID，无则 `null`。

    

### 16\.3 任务步骤列表（新增）



**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/steps** 返回 `TaskStepListItemResponse[]`：

```JSON
{
  "id": "step-uuid", "taskId": "task-uuid", "sequenceNo": 2, "title": "后端接口开发",
  "description": "实现登录接口、JWT 签发和错误处理", "role": "DEVELOPER",
  "agent": { "id": "agent-uuid", "name": "Backend Developer Agent", "role": "DEVELOPER",
    "avatarUrl": null, "status": "ACTIVE" },
  "repository": { "repositoryId": "binding-uuid", "name": "auth-service", "sourceBranch": "feat/login-api" },
  "dependencies": ["step-uuid-1"], "status": "RUNNING",
  "acceptanceNotes": "覆盖成功、错误密码和用户不存在场景",
  "latestRun": { "id": "run-uuid", "status": "RUNNING", "startedAt": "…", "finishedAt": null, "durationMs": null },
  "runCount": 1,
  "startedAt": "2026-08-14T10:16:00Z", "finishedAt": null,
  "createdAt": "2026-08-14T10:05:00Z", "updatedAt": "2026-08-14T10:20:00Z"
}
```



- `description` 为步骤指令的脱敏展示；`acceptanceNotes` 为步骤验收条件。

    

- `latestRun` 为最新一次 TaskRun 摘要；`runCount` 为该步骤累计执行次数。

    

- `startedAt/finishedAt` 由该步骤所有 TaskRun 的最早开始/最晚结束时间派生，无运行时为 `null`。

    

- **结构化 checkpoints 后端明确不提供**（执行器未持久化步骤内部检查项），前端只展示 `acceptanceNotes`。

    

### 16\.4 任务运行列表（增强）



**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/task\-runs** 返回 `TaskRunListItemResponse[]`（较旧摘要新增）：



- `taskStepTitle`：所属步骤标题。

    

- `agent`：执行 Agent 摘要（未分配为 `null`）。

    

- `statusSummary`：脱敏状态摘要（如“等待用户补充输入”），不返回日志原文/Prompt/Token/环境变量。

    

- `statusReason`：等待/阻塞/失败原因 `{ code, title, summary, retryable, occurredAt }`，无等待或失败时为 `null`。

    

    - `code` 枚举：`INPUT_REQUIRED/APPROVAL_REQUIRED/BLOCKED/EXECUTION_FAILED/CANCELLED`。

        

    - `retryable` 由后端按状态机判断（FAILED/CANCELLED/BLOCKED 可重试）。

        

- `startedAt/finishedAt/durationMs`：执行时间。

    

- `artifactSummary`：`{ total, diffCount }`，该运行自身产出的执行产物与 Diff 数量。

    

**GET /api/v1/projects/\{projectId\}/task\-runs/\{taskRunId\}**（详情）同样增加 `statusReason`。



### 16\.5 任务执行产物（增强）



**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/artifacts** 在保留 `summary` 的同时增加结构化展示字段：



- `title`：由产物类型派生的稳定标题（PLAN→“计划”/CODING→“代码编写”/TESTING→“测试”/REVIEWING→“代码审查”）。

    

- `status`：由执行结果 `outcome` 派生（SUCCEEDED→SUCCEEDED，其余→FAILED；无 outcome 时为 `null`）。

    

- `description`：产物摘要中的脱敏说明（截断 ≤200 字符），无则 `null`。

    

- `resources`：受权限保护的内部资源引用列表 `{ resourceType, resourceId, title }`；

    

当前无校验过的内部资源引用时返回空数组，不伪造。



### 16\.6 总 Diff 逐仓库交付详情（增强）



**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review** 响应新增 `repositoryDeliveries[]`：

```JSON
{
  "repositoryId": "binding-uuid",
  "repositoryName": "auth-service",
  "diffId": "diff-uuid",
  "deliveryStatus": "MR_CREATED",
  "failureCode": null,
  "failureReason": null,
  "mergeRequest": { "id": "mr-uuid", "number": 128, "title": "feat: implement login API",
    "status": "OPEN", "webUrl": "https://github.com/qgents/auth-service/pull/128" },
  "updatedAt": "2026-08-14T11:00:00Z"
}
```



- `deliveryStatus` 枚举：`NOT_STARTED/COMMITTED/MR_CREATED/FAILED`。

    

- `failureCode` 预留（当前为 `null`）；`failureReason` 为脱敏失败原因，成功时为 `null`。

    

- `mergeRequest` 仅在 `MR_CREATED` 且存在真实 MR 记录时返回；`webUrl` 由 GitHub 仓库镜像与真实 PR 编号构造，

    

不可可靠构造时为 `null`。MR 完整审查与合并仍由 MR 模块负责，此处仅展示摘要与入口。



- 批次 `deliveryStatus` 为 `PARTIALLY_DELIVERED` 时，通过 `repositoryDeliveries` 指出成功与失败仓库。

    

### 16\.7 明确不提供项



- `discussionSummary`（无“消息→Task”归属关系，仅提供来源消息 `sourceMessage`）。

    

- 决策记录、执行时 Skill/Memory 版本快照、TaskStep checkpoints、人工 Reviewer 分配（无真实数据源）。

    

- 进度百分比（禁止伪造）；`priority`（产品暂无优先级业务）。

    

## 17\. v1\.6\.0 补充：消息 senderName 与 Testset 列表



### 17\.1 消息列表新增 senderName



`GET /projects/{projectId}/groups/{groupId}/messages` 返回的 `MessageResponse` 新增 `senderName` 字段：



- 用户消息：`displayName`（账号注销等异常场景返回稳定占位“已注销用户”，不返回空字符串）；

    

- Agent 消息：`Agent.name`；

    

- SYSTEM 消息：`null`。

    

后端在消息列表接口一次性批量加载发送者名称，不引入逐条消息 N\+1。



### 17\.2 Testset 列表接口已实现



`GET /projects/{projectId}/testsets`（可选 `repositoryId` 过滤，项目成员）已实现，返回 `TestsetResponse[]`：

```JSON
{
  "id": "testset-uuid",
  "name": "后端单元测试",
  "repositoryId": "project-repository-binding-uuid",
  "status": "ENABLED",
  "enabled": true,
  "definition": { "command": "./mvnw test", "timeoutSeconds": 900, "passRule": { "type": "EXIT_CODE", "expected": 0 } },
  "createdBy": "user-uuid",
  "createdAt": "2026-08-14T10:00:00Z",
  "updatedAt": "2026-08-14T10:00:00Z"
}
```



创建/修改/启停/删除仍由 Testset 管理功能后续提供（契约 §10 已声明）。



---



## 18\.v1\.6\.0 补充： 附件直传与下载接口（请求/响应契约补充）



前端图片/文件链路需要：直传 URL → 上传 → 得到最终展示 URL → 填入 `content.url`。本小节给出三个接口的完整请求/响应。



### 18\.1 创建直传凭证 `POST /projects/{projectId}/attachments`（201）



**请求体**

```JSON
{
  "fileName": "login.png",      // 必填，≤512
  "contentType": "image/png",   // 可空，≤255
  "sizeBytes": 204800           // 必填，>0，≤ app.attachment-max-size-bytes（默认 50MB）
}
```



**响应体**

```JSON
{
  "attachmentId": "uuid",
  "uploadUrl": "https://qgents.oss-cn-guangzhou.aliyuncs.com/...?...签名...",  // 预签名 PUT 地址，直接把文件字节 PUT 到此
  "method": "PUT",
  "expiresAt": "2026-08-15T10:15:00Z",      // 上传凭证过期（默认 900 秒）
  "headers": {}                              // 当前为空（预签名 PUT 不要求固定请求头）
}
```



### 18\.2 确认上传完成 `POST /projects/{projectId}/attachments/{attachmentId}/confirm`（200）

```JSON
{ "attachmentId": "uuid", "status": "READY" }
```



服务端校验对象已真实存在于 OSS 后置 READY。



### 18\.3 获取临时下载地址 `GET /projects/{projectId}/attachments/{attachmentId}/download-url`（200）

```JSON
{ "attachmentId": "uuid", "downloadUrl": "https://...预签名 GET...", "expiresAt": "2026-08-15T10:15:00Z" }
```



`downloadUrl` 默认 900 秒过期。



### 18\.4 展示 URL（content\.url）说明



- `uploadUrl` 是**预签名 PUT 地址，不是展示 URL**。

    

- 对象键（objectKey）= `projects/{projectId}/attachments/{attachmentId}`。

    

- 展示 URL 二选一：

    

    1. **桶公共读**：`https://{bucket}.{endpoint}/{objectKey}`（稳定，适合聊天图片/文件长期展示）；

        

    2. **临时地址**：调 §18\.3 `download-url`（15 分钟过期，不适合长期引用）。

        

- **建议**：聊天图片/文件引用用**公共读稳定地址**；若保持私有桶，需要后端补充长期展示地址机制（待定，见 §19）。

    

### 18\.5 稳定展示地址（已实现：鉴权下载代理）



`GET /projects/{projectId}/attachments/{attachmentId}/content`



- 鉴权后流式返回附件内容（项目成员 \+ 附件归属校验），**长期稳定、不过期**；

    

- 图片（`image/*`）走 `Content-Disposition: inline`（浏览器直接渲染），其余走 `attachment`（可下载）；

    

- **`content.url`**\*\* 直接填本路径\*\*：`/projects/{projectId}/attachments/{attachmentId}/content`（前端自行拼绝对地址）；

    

- 相比 §18\.3 download\-url（15 分钟过期）与公共读（无鉴权），本方式保持项目隔离、不依赖桶 ACL。

    

---



## 19\. v1\.7\.0 补充：团队邀请（收件人视角）与团队最近动态



落地前端清单的「我的团队邀请」与「团队首页最近动态」。团队最近动态基于项目 `events` 表聚合，**只覆盖最近 24 小时**（events 保留期）。



### 19\.1 接口总览



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/team-invitations`|登录用户|我收到的待处理团队邀请（分页）|
|`POST`|`/team-invitations/{reference}/accept`|登录用户|接受团队邀请（`reference`=邀请 id 或明文 token）|
|`GET`|`/teams/{teamId}/activities`|团队成员|团队最近动态（分页，覆盖最近 24 小时）|



### 19\.2 我的团队邀请 `GET /team-invitations`



**请求参数**：`cursor`（分页游标，首次不传）、`limit`（默认 30，范围 1\.\.100）。



**响应（****`ReceivedInvitationResponse`****）**：



|字段|类型|说明|
|---|---|---|
|`id`|string|邀请记录 id（UUIDv7），**接受时用此值**|
|`teamId`|string|邀请来源团队|
|`teamName`|string|团队名（展示用）|
|`role`|string|恒为 `TEAM_MEMBER`（邀请创建仅支持该角色）|
|`inviterDisplayName`|string|邀请人显示名|
|`status`|string|`PENDING` / `EXPIRED`（PENDING 但已过期的按 EXPIRED 展示）|
|`expiresAt`|string|ISO8601 UTC 过期时间|
|`createdAt`|string|ISO8601 UTC|



**响应示例**：

```JSON
{
  "data": [
    {
      "id": "01975b3e-...-0000000000a1",
      "teamId": "01975b3e-...",
      "teamName": "Qgents 平台组",
      "role": "TEAM_MEMBER",
      "inviterDisplayName": "林66",
      "status": "PENDING",
      "expiresAt": "2026-08-16T10:00:00Z",
      "createdAt": "2026-08-15T10:00:00Z"
    }
  ],
  "page": { "nextCursor": "", "hasMore": false },
  "requestId": "req_..."
}
```



**不返回明文 token**：数据库仅存邀请令牌的 SHA\-256 哈希，安全底线禁止回明文；接受走 19\.3 的按 id 分支。

错误：`404 NOT_FOUND`（当前用户不存在或无邮箱）、`400 INVALID_CURSOR` / `400 INVALID_PAGE_LIMIT`。



### 19\.3 接受邀请 `POST /team-invitations/{reference}/accept`



路径变量 `reference` 兼容**邀请 id（UUIDv7）或明文 token**，后端按「能否被解析为 UUID」区分：明文 token 为 base64url、无连字符，永远不会被解析成 UUID，两者天然互斥，存量 token 流程完全兼容。



- **幂等**：携带 `Idempotency-Key`，重复调用返回首次结果；已接受且已是成员时幂等返回成员视图。

- **安全**：无论按 id 还是 token，均校验当前用户邮箱与被邀请邮箱一致，防越权。

    

**响应（****`TeamMemberResponse`****）**：

```JSON
{
  "data": {
    "userId": "user-uuid",
    "role": "TEAM_MEMBER",
    "displayName": "王五",
    "email": "wang@example.com"
  },
  "requestId": "req_..."
}
```



错误：`404 NOT_FOUND`（reference 非法/邀请不存在/邮箱不匹配）、`409 INVITATION_EXPIRED`（已过期，后端先置 EXPIRED 再返回）、`409 INVITATION_NOT_PENDING`（已接受或已撤销）。



### 19\.4 团队最近动态 `GET /teams/{teamId}/activities`



**请求参数**：



|参数|类型|必填|说明|
|---|---|---|---|
|`type`|string|否|动态类型过滤，逗号分隔、前缀匹配（如 `TASK,MR`；`MR` 命中 `MR_CREATED`\+`MR_MERGED`）；不传=全部 6 类|
|`cursor`|string|否|分页游标，首次不传|
|`limit`|integer|否|每页条数，默认 20，最大 50|



**动态类型**（本期产出，基于项目事件聚合）：



|type|含义|title|target|actor|
|---|---|---|---|---|
|`TASK_COMPLETED`|任务完成|`任务「{title}」已完成`|`{TASK, taskId, 任务标题}`|任务创建人|
|`TASK_FAILED`|任务失败|`任务「{title}」已失败`|`{TASK, taskId, 任务标题}`|任务创建人|
|`DIFF_CREATED`|Diff 待验收|`任务「{title}」的 Diff 待验收`|`{DIFF, diffId, 任务标题}`|任务创建人|
|`MR_CREATED`|MR 创建|`{MR标题} MR #{n} 已创建`|`{MR, mergeRequestId, "#{n} {MR标题}"}`|null|
|`MR_MERGED`|MR 已合并|`{MR标题} MR #{n} 已合并`|`{MR, mergeRequestId, "#{n} {MR标题}"}`|null|
|`TEST_RUN_FAILED`|测试运行失败|`测试运行失败`|有 taskId→`{TASK, taskId, 任务标题}`；无→`{PROJECT, projectId, 项目名}`|null|



**响应（****`ActivityResponse`****）**：



|字段|类型|说明|
|---|---|---|
|`id`|string|动态条目 id（=事件 id）|
|`type`|string|上表 6 类之一|
|`title`|string|已生成的展示文案（前端当文本展示，勿当机器字段解析）|
|`summary`|string?|本期恒为 null|
|`actor`|object?|`{id, displayName, avatar}`；无可靠来源时为 null，`avatar` 恒为 null|
|`target`|object|`{type, id, title}`；type ∈ `TASK/DIFF/MR/PROJECT`|
|`link`|string?|恒为 null（前端按 `target.type+id` 自行拼前端路由）|
|`createdAt`|string|ISO8601 UTC，按此倒序|



**响应示例**：

```JSON
{
  "data": [
    {
      "id": "01975b3e-...",
      "type": "TASK_COMPLETED",
      "title": "任务「实现登录功能」已完成",
      "summary": null,
      "actor": { "id": "user-uuid", "displayName": "林66", "avatar": null },
      "target": { "type": "TASK", "id": "task-uuid", "title": "实现登录功能" },
      "link": null,
      "createdAt": "2026-08-15T10:00:00Z"
    }
  ],
  "page": { "nextCursor": "...", "hasMore": false },
  "requestId": "req_..."
}
```



**边界**：

- 只覆盖最近 24 小时（events 保留期），超窗数据会消失。

- `MESSAGE` / `GROUP_CREATED` / `MEMBER_JOINED` / `TASK_CREATED` 无事件发布源，本期不产出，`type` 传它们返回空 data。

- 错误：`404 NOT_FOUND`（团队不存在）、`403 FORBIDDEN`（非团队成员）、`400 INVALID_CURSOR` / `400 INVALID_PAGE_LIMIT`。



## 20\. v1\.8\.0 更新：DeliveryCenter 聚合接口与 Agent 展示摘要



> 基线 v1\.7\.0。本小节落地前端成员 B 的 DeliveryCenter 聚合、Agent 展示摘要需求，并冻结 6 项契约歧义（N01–N06）与权限映射。所有新接口均为只读展示，写操作继续复用对应正式资源接口。
> 
> 



### 20\.1 DeliveryCenter 聚合列表 `GET /api/v1/projects/{projectId}/delivery-items`



**查询参数**：



|参数|类型|必填|说明|
|---|---|---|---|
|`groupId`|UUID|否|CODE 按 Task 的需求群来源匹配；<br>MEMORY 按来源消息所属需求群匹配；<br>SKILL 当前无来源数据，不匹配 groupId。|
|`type`|string|否|资源类型：`CODE` / `MEMORY` / `SKILL`；省略返回全部三类|
|`status`|string|否|按展示状态 `displayStatus` 筛选（枚举见 §20\.3）|
|`repositoryId`|UUID|否|按项目仓库绑定 ID 筛选（仅 CODE 匹配）|
|`createdBy`|UUID|否|按创建者筛选|
|`cursor`|string|否|游标分页（上一页 `nextCursor`）|
|`limit`|int|否|每页条数，默认 30，最大 100|



**响应**：统一 cursor envelope `{ data, page: { nextCursor, hasMore }, requestId }`。



**DeliveryItem union**：以 `resourceType` 为 discriminator，`CODE` / `MEMORY` / `SKILL` 三类各自携带专属字段，不把三类字段堆叠为公共可选字段。公共字段：



|字段|类型|说明|
|---|---|---|
|`id` / `resourceId`|string|资源 ID：CODE=批次 ID、MEMORY=memoryId、SKILL=skillId|
|`projectId`|string|项目 ID|
|`resourceType`|string|`CODE` / `MEMORY` / `SKILL`|
|`title`|string|展示标题|
|`summary`|string?|脱敏摘要（≤200 字符）|
|`version`|string?|当前恒为 null|
|`displayStatus`|string|后端统一派生的展示状态（前端不维护映射）|
|`resourceStatus`|string|真实资源状态（reviewStatus / Memory\.status / Skill\.status）|
|`requirementGroup`|object?|`{id, name}`；无群来源时 null|
|`source`|object|`{taskId, taskDisplayCode, taskTitle, taskRunId, taskStepId, messageId, artifactId}`；无对应关联时字段为 null|
|`creator` / `submitter` / `reviewer`|object?|`UserSummary{id, displayName, avatarUrl}`；|
|`reviewReason`|string?|驳回/拒绝原因|
|`createdAt` / `submittedAt` / `reviewedAt` / `updatedAt`|string?|ISO8601 UTC；|
|`capabilities`|object|服务端派生的操作能力（§20\.3）|
|`openTarget`|object|显式打开目标（§20\.3）|



**CODE 专属字段**：`repositories[]`（`{repositoryId, name, branch}`）、`diffReviewId`、`reviewStatus`、`deliveryStatus`、`filesChanged`、`additions`、`deletions`、`repositoryDeliveries[]`（`{repositoryId, repositoryName, deliveryStatus, failureCode, failureReason, mergeRequest, updatedAt}`）、`mergeRequest`（`{id, number, title, status, webUrl}`，无 MR 时 null）。



**MEMORY 专属字段**：`category`、`tags[]`、`visibility`（当前统一返回 `PROJECT_SHARED`）、`sources[]`、`contentExcerpt`。



**SKILL 专属字段**：`tags[]`、`visibility`（`PRIVATE`/`PROJECT_SHARED`）、`capabilitySummary`（当前恒为 null）、`contentExcerpt`。



**约束**：集合无数据返回 `[]` 不返回 `null`；真实不存在的关联返回 `null`，不得用空字符串或 Mock ID 代替；聚合列表不返回完整 Memory/Skill 内容、Prompt、Token、凭据、环境变量或代码 Patch。



### 20\.2 DeliveryCenter 聚合统计 `GET /api/v1/projects/{projectId}/delivery-summary`



**查询参数**（与 `delivery-items` 一致，用户切换筛选后统计同步变化）：



|参数|类型|必填|说明|
|---|---|---|---|
|`groupId`|UUID|否|按需求群筛选（CODE 按 Task 群来源、MEMORY 按来源消息群匹配；SKILL 无来源不匹配）|
|`type`|string|否|资源类型：`CODE` / `MEMORY` / `SKILL`；省略统计全部三类|
|`status`|string|否|按展示状态 `displayStatus` 筛选|
|`repositoryId`|UUID|否|按项目仓库绑定 ID 筛选（仅 CODE 匹配）|
|`createdBy`|UUID|否|按创建者筛选|



**响应**：



```JSON
{
  "data": {
    "total": 12,
    "countsByType": { "CODE": 4, "MEMORY": 5, "SKILL": 3 },
    "countsByStatus": { "DRAFT": 2, "PENDING_REVIEW": 2, "PROCESSING": 1, "ACCEPTED": 3,
      "REJECTED": 0, "DELIVERED": 3, "FAILED": 1, "ARCHIVED": 0 },
    "pendingForCurrentUser": 2,
    "repositorySummaries": [ { "repositoryId": "...", "repositoryName": "...", "total": 2,
      "accepted": 1, "pending": 1, "failed": 0, "deliveryStatus": "MR_CREATED",
      "mergeRequest": { "id": "...", "number": 128, "title": "...", "status": "OPEN", "webUrl": "..." } } ],
    "requirementGroupSummaries": [ { "requirementGroupId": "...", "name": "...", "total": 3, "pending": 1 } ],
    "updatedAt": "2026-08-15T08:00:00Z"
  },
  "requestId": "req-uuid"
}
```



**规则**：

- 统计针对**完整筛选数据集**计算，不由当前分页推导；

- `countsByType` 恒含 `CODE/MEMORY/SKILL` 三 key（值为 0 也返回）；`countsByStatus` 恒含 8 个正式大写枚举 key（`DRAFT/PENDING_REVIEW/PROCESSING/ACCEPTED/REJECTED/DELIVERED/FAILED/ARCHIVED`）；

- **统计 key 统一使用正式大写枚举**，不使用 `code`/`memory`/`pendingReview` 等小写或 camelCase key；

- `pendingForCurrentUser` 由后端按「当前用户 \+ capabilities」计算；

- **不返回 ****`pendingItems[]`**（N06 结论），前端只消费数量；

- `repositorySummaries` / `requirementGroupSummaries` 无数据时返回 `[]`。

    

### 20\.3 Delivery DTO 语义冻结（N04/N05）



**openTarget 四态**（`resourceId` 不再被多义解释为 Diff 或批次）：



```TypeScript
type DeliveryOpenTarget =
  | { kind: "TASK_DIFF_REVIEW"; taskId: string; diffReviewBatchId: string }
  | { kind: "DIFF"; taskId: string; diffId: string }
  | { kind: "MEMORY"; memoryId: string }
  | { kind: "SKILL"; skillId: string };
```



**displayStatus 映射（后端派生）**：



|resourceType|真实状态|displayStatus|
|---|---|---|
|CODE|reviewStatus=PENDING\_CONFIRMATION|`PROCESSING`|
|CODE|reviewStatus=ACCEPTED 且 deliveryStatus=DELIVERED|`DELIVERED`|
|CODE|reviewStatus=ACCEPTED 且 deliveryStatus=PARTIALLY\_DELIVERED/FAILED|`FAILED`|
|CODE|reviewStatus=ACCEPTED 且其他交付状态|`PROCESSING`|
|CODE|reviewStatus=REJECTED|`REJECTED`|
|MEMORY|status=DRAFT / PENDING\_REVIEW / APPROVED / REJECTED / ARCHIVED|`DRAFT` / `PENDING_REVIEW` / `ACCEPTED` / `REJECTED` / `ARCHIVED`|
|SKILL|status=DRAFT / PENDING\_REVIEW / PUBLISHED / REJECTED / ARCHIVED|`DRAFT` / `PENDING_REVIEW` / `ACCEPTED` / `REJECTED` / `ARCHIVED`|



**capabilities（后端派生）**：`canSubmitReview` / `canApprove` / `canReject` / `canArchive` / `canRetryDelivery` / `canOpenResource` \+ 对应的 `disabledReasons`（稳定错误码，可操作时为 null）。规则与正式资源接口一致：

- CODE：approve/reject 仅任务发起人或 Project Admin 且批次 `PENDING_CONFIRMATION`；retry\-delivery 仅接受后交付未完成（`PARTIALLY_DELIVERED`/`FAILED`）；

- MEMORY/SKILL：submit 仅创建者或 Admin 且 `DRAFT`/`REJECTED`；approve/reject 仅 Admin 且 `PENDING_REVIEW`；archive 仅 Admin 且 `APPROVED`（MEMORY）/`PUBLISHED`（SKILL）。

    

**写操作复用**：聚合页不新建写接口——CODE 走 `POST /tasks/{taskId}/diff-review/confirm | reject | retry-delivery`（需 `Idempotency-Key`）；MEMORY/SKILL 走各自 `submit-review | approve | reject | archive`。



**Memory/Skill 提交审核信息**（成员B 最终契约 §三）：



- 普通项目成员可创建 Memory/Skill 并提交审核；Project Admin/TEAM\_OWNER 批准或拒绝；通过后成为项目共享资源。

- 进入过审核流程的聚合项必须返回 `submitter`（`UserSummary`）与 `submittedAt`（ISO8601 UTC）：

    - 从未提交审核的 `DRAFT`：允许为 `null`；

    - `PENDING_REVIEW`：`submitter`、`submittedAt` 必须非空；

    - `APPROVED`/`PUBLISHED`/`REJECTED`/`ARCHIVED`：保留**最后一次**提交审核的申请人和时间；

    - `creator` 与 `submitter` 语义不同，不可相互替代（创建者本人提交时两者相同，但仍同时返回）；

    - `reviewer`、`reviewedAt` 在审批完成后按真实审核记录返回。

- 数据来源：`memories`/`skills` 表新增 `submitted_by`、`submitted_at` 列（迁移 `V20260815_02__memory_skill_submission_fields.sql`），`submit-review` 时写入。

    

**Memory/Skill 来源关系**（成员B 最终契约 §四）：



- Memory 由需求群消息产生（`memory_message_sources`）：`source.messageId` 返回真实来源消息 ID；存在消息来源时同时返回 `requirementGroup {id, name}`（由来源消息的群派生）；`sources[]` 返回全部来源消息引用 `{groupId, messageId}`；

- Skill 当前无来源数据源：`source` 各字段与 `requirementGroup` 返回 `null`；

- `groupId` 筛选匹配**所有**具有该需求群来源的资源：CODE 按 Task 群来源匹配、MEMORY 按来源消息群匹配；SKILL 无来源不匹配；

- 没有来源关系时返回 `null`，不得填充 Mock ID 或展示文案。

    

**CODE openTarget 固定**（成员B 最终契约 §五）：



- CODE 是 Task 级多仓库总 Diff 聚合项，列表项固定返回 `openTarget = { kind: "TASK_DIFF_REVIEW", taskId, diffReviewBatchId }`；

- 固定 `resourceId = diffReviewBatchId`、`diffReviewId = diffReviewBatchId`；

- `diffId` 只表示单个仓库 Diff，**不得与批次 ID 混用**；当前聚合列表不返回 `openTarget.kind = "DIFF"`；

- 单 Diff 入口由 Task 级 DiffReview 详情中的仓库明细提供；`DIFF` openTarget 类型保留给其他页面/未来能力。



### 20\.4 Delivery 相关 SSE 冻结

新增事件（沿用 §12\.1 项目级单连接 / Bearer / `Last-Event-ID` / `EVENT_CURSOR_EXPIRED`；前端收到事件只失效 Query，不写入实体缓存）：

|事件|覆盖场景|
|---|---|
|`memory.submit-review` / `memory.approved` / `memory.rejected` / `memory.archived`|MEMORY 审批流转|
|`skill.submit-review` / `skill.published` / `skill.rejected` / `skill.archived`|SKILL 审批流转|
|统一 payload 基座（CODE 事件保持 §15\.6\.4 现有 payload 不动）：||

```TypeScript
interface DeliveryEventPayload {
  projectId: string;
  resourceType: "CODE" | "MEMORY" | "SKILL";
  resourceId: string;          // memoryId / skillId / diffReviewBatchId
  taskId?: string;
  diffId?: string;
  diffReviewBatchId?: string;
  repositoryId?: string;
  eventVersion: number;        // 首版为 1
  updatedAt: string;           // RFC 3339 UTC
}
```

`merge-request.updated`：完整 payload 见 §6\.10（`projectId, mergeRequestId, repositoryId, number, status, headCommit, providerUpdatedAt, qualityGateStatus, timestamp`，不含 `webUrl`；MR 链接由 MR 列表、详情或 Diff/MR 交付卡提供）；`diff-review.skipped`：reason 固定为 `FINAL_DIFF_EMPTY`（§15\.6\.4）。

### 20\.5 Agent 分配列表与运行时摘要



**`GET /api/v1/projects/{projectId}/agents/{agentId}/assignments`**（B04）



|参数|类型|必填|说明|
|---|---|---|---|
|`type`|string|否|`REQUIREMENT_GROUP` / `WORKFLOW`；省略返回全部|
|`cursor` / `limit`|string/int|否|统一 cursor envelope，默认 30 最大 100|



响应项：`{ type, resourceId, resourceName, status: "ACTIVE"|"INACTIVE" }`。`REQUIREMENT_GROUP` 数据源为 `group_agents`（Agent 参与的需求群，按项目隔离）；`WORKFLOW` 当前无数据源（团队工作流模板非本版本范围，§14），返回空列表不伪造。



**`GET /api/v1/projects/{projectId}/agents/{agentId}/runtime`**（B06）



```JSON
{
  "data": {
    "status": "IDLE",
    "activeRunCount": 0,
    "concurrencyLimit": null,
    "assignmentUsage": {
      "requirementGroups": { "assignedCount": 2, "assignableCount": 5 },
      "workflows": { "assignedCount": 0, "assignableCount": 0 }
    },
    "skillAccessScope": "PROJECT",
    "memoryAccessScope": "PROJECT"
  },
  "requestId": "req-uuid"
}
```



说明：`status` 由 `activeRunCount > 0` 派生；`concurrencyLimit` 当前无配置恒为 null；`skillAccessScope`/`memoryAccessScope` 当前均为项目维度隔离，返回 `PROJECT`。不返回 Prompt、工具凭据或 Memory 完整内容。



### 20\.6 项目级按 Agent 查询 TaskRun（B05）



**`GET /api/v1/projects/{projectId}/task-runs`**



|参数|类型|必填|说明|
|---|---|---|---|
|`agentId`|UUID|是|执行 Agent ID|
|`status`|string|否|TaskRun 状态过滤|
|`cursor` / `limit`|string/int|否|统一 cursor envelope，默认 20 最大 100|



列表项在 §12\.2 `TaskRunListItemResponse` 基础上补充：`taskDisplayCode`、`taskTitle`、`taskStepRole`、`requirementGroup`（`{id, name, status}`）、`repository`（`RepositorySummary`，取任务首个 worktree 对应仓库，无则 null）。



### 20\.7 Task `attention` 扩展（B07）



`attention` 增加正式关联字段（无对应关联返回 null，前端不按标题/数组位置/Mock ID 推断跳转）：



```TypeScript
interface TaskAttention {
  kind: string;
  title: string;
  summary: string | null;
  taskRunId: string | null;
  inputRequestId: string | null;
  diffReviewBatchId: string | null;   // DIFF_CONFIRMATION_REQUIRED/DELIVERY_FAILED 时非 null
  repositoryId: string | null;        // DELIVERY_FAILED 时为首个失败仓库绑定 ID
  createdAt: string | null;
}
```



### 20\.8 N01–N06 契约歧义冻结结论



|编号|待确认项|结论|
|---|---|---|
|N01|TaskStep 列表响应|统一为 cursor envelope `{data, page:{nextCursor, hasMore}, requestId}`（已实现，单页返回全部）|
|N02|TaskRun `artifactSummary`|正式 shape 为 `{total, diffCount}`；旧 `{diffs:{count,byStatus}}` 废弃，不保留兼容|
|N03|DiffReview nullable|批次不存在时查询返回 `404 DIFF_REVIEW_NOT_FOUND`；批次内 `diffId` 恒非空；`requirementGroupId` 由 Task 派生（防御性允许 null）；`reviewReason` 仅 REJECTED 时非 null|
|N04|Delivery 资源 ID|采纳显式 `openTarget`（§20\.3）；`resourceId` = 批次 ID / memoryId / skillId，与 `diffId` 严格区分|
|N05|`displayStatus` 归属|后端统一派生并返回（映射见 §20\.3），前端不维护映射|
|N06|待处理统计|只返回 `pendingForCurrentUser` 数量，不返回 `pendingItems[]`|



### 20\.9 权限与状态规则确认



|产品术语|后端结论|
|---|---|
|"Owner 批准 Memory/Skill"|= `PROJECT_ADMIN`（`requireProjectAdmin`）|
|`TEAM_OWNER` 是否自动继承项目审批权|是：`ProjectAccessService` 对 canonical Team Owner 做项目内 Admin 兜底（`isOwnerOrAdmin` / `requireProjectAdmin` 已覆盖）|
|普通项目成员|只能创建/提交审核，不能 approve/reject/archive；可见性遵循 PRIVATE / 已发布可见规则|
|Memory 审批后共享状态|`APPROVED`（项目共享，无独立 visibility 字段，聚合项统一返回 `PROJECT_SHARED`）|
|Skill 审批后正式状态|`PUBLISHED`（前端按 `PUBLISHED` 消费；旧 `APPROVED` 不作为 Skill 状态）|
|前端如何判断操作权|不解析成员角色，统一消费后端返回的 `capabilities`|



### 20\.10 P2：DeliveryCenter 导出（延期）



`GET /delivery-items/export`（CSV \+ `Content-Disposition`）不阻塞首轮联调，待 P0/P1 稳定后实现；导出范围仍限列表摘要，不含完整内容/Prompt/凭据/Patch。



## 21\. v1\.9\.0 补充：Diff / MR / Testset 页面缺口确认



> 依据前端 `docs/temp/diff-mr-testset-backend-gaps.md`，后端基于现状逐项确认。以下「已符合」项为冻结契约；「需补」项待开发排期后落地（不阻塞首轮联调）；「本轮不做」项前端按空态处理。
> 
> 



### 21\.1 五个问题的直接结论



|编号|问题|结论|
|---|---|---|
|Q1|`GET /merge-requests/{id}/checks` 能否按 `{status, requiredChecks, items[]}` 冻结？|语义一致、**形状不同**：现状返回扁平数组 `MergeRequestCheckResponse[]`（`id/type/status/attemptNo/testsetId/commitSha/source/startedAt/completedAt`）。`type` 枚举冻结为 `TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE`，`status` 冻结为 `PENDING/PASSED/FAILED`；`qualityGate.requiredChecks` 只含四项。**待定**：后端包装为 `{status, requiredChecks, items[]}`（推荐），或前端适配扁平数组|
|Q2|MR 能否直接返回 `diffId`？|现状**无**。数据可推导（MR 的 taskId \+ projectRepositoryId \+ sourceBranch ↔ Diff 同字段 \+ reviewBatchId→taskId）。**需补（推荐）**：MR 详情返回该仓库该任务已 `ACCEPTED` 的 Diff 的 `diffId`，无则 `null`；备选：`GET /diffs` 增加 `repositoryId` \+ `sourceBranch` 过滤|
|Q3|Testset 列表是扁平字段还是 `definition`？|**扁平字段（§10 形状）已实现**：`TestsetResponse` 顶层含 `command/timeoutSeconds/passRule/acceptanceNotes`；`status` 只用 `ENABLED/DISABLED`（无 `enabled` 布尔）；enable/disable/delete 已实现。**缺 ****`scopeTags`**（创建请求有、响应未返回，需补）|
|Q4|`cases[]`、`reportUrl`、`pdfUrl` 本轮给不给？|**本轮不给**：`TestRunResponse` 详情为 `id/projectId/repositoryId/ref/testsetIds/status/summary/createdBy/createdAt`（无 caseSummary/cases/reportUrl/pdfUrl/sandboxId/startedAt/finishedAt）；`DryRunReportResponse` 为 `id/status/createdAt`。执行器未收集逐用例结果、报告产物未接存储。前端用例详情/报告 Tab 保持空态|
|Q5|Test Run / Dry Run 历史列表本轮做不做？|**本轮不做**（§1 已声明）：无 `GET /test-runs`、`GET /dry-runs` 列表接口；前端继续本机 localStorage，联调不当作缺数据。后续补列表接口属 P1\+|



### 21\.2 Diff / MR / Testset 字段现状（冻结）



**Diff 评审页**：



|字段/能力|现状|结论|
|---|---|---|
|`GET /diffs/{diffId}/files`|返回 `DiffFileResponse[]`：`id/sequence/path/changeType/additions/deletions/binary`，**无 hunk/line 结构**|需补：解析 patch 为 `hunks[].lines[]`（Git 领域），或冻结「前端用 patch 接口自行解析」——待定|
|文件变更类型|字段名 `changeType`（前端称 `status`）|枚举冻结为 `ADDED/MODIFIED/DELETED`；字段名待统一|
|`GET/POST /diffs/{diffId}/comments`|`DiffCommentResponse`：`id/diffId/path/side/line/hunkId/commitSha/body/authorUserId/createdAt`|`createdAt` 已符合；**`authorName`**** 需补**（join users）或前端用 `authorUserId` 查用户|
|Diff 详情 `targetBranch` / 仓库名|无（仅 `sourceBranch`；无 repositoryName）|建议：`targetBranch` 明确使用仓库默认分支；仓库名用 `binding.displayName` 或前端自行拼|
|评论已解决 / 回复串 / 文件下载|无|本轮不做（书面确认）|



**MR 列表 / 详情**：



|字段|现状|结论|
|---|---|---|
|`title`|列表、详情均有 ✓|已符合|
|`qualityGate`|列表、详情均有（`{status, requiredChecks}`）✓|已符合|
|`status`|`OPEN/MERGED/CLOSED` ✓|已符合（「进行中」即 OPEN 文案）|
|`webUrl`|无|需补：GitHub repo \+ `providerNumber` 构造，构造不出给 `null`|
|`description`|无（未镜像 GitHub body）|返回 `null`/`""`|
|`groupIds`|列表无|需补（按群标识时）|
|`diffId`|无|需补（Q2 推荐方案）|



**Testset / TestRun / DryRun / SSE**：



|项|现状|结论|
|---|---|---|
|`GET /testsets` 过滤|`repositoryId` ✓；`status` 前端已传|`status` 过滤若未支持则需补（实现时确认）|
|`POST /test-runs` / `POST /dry-runs` 立即响应|含 `id` ✓|前端可接着 GET 详情/report|
|SSE `test-run.updated`|payload 含 `testRunId` \+ projectId/repositoryId/taskId/ref/status ✓|已符合|
|SSE `dry-run.updated`|payload 含 `dryRunId` \+ projectId/repositoryId/taskId/headCommit/targetBranch/status ✓|已符合|
|历史列表 / 用例详情 / 报告 URL|无（Q4/Q5）|本轮不做|



### 21\.3 待补工作清单（后续实现，非当前冻结契约）



1. Diff files：补 hunk/line 结构化，或冻结「前端自行解析 raw patch」；

2. Diff / MR comments：响应补 `authorName`（join users）；

3. MR：详情补 `diffId`（推荐）、列表/详情补 `webUrl`、详情补 `description`（允许 null）、列表补 `groupIds`；

4. MR checks：包装为 `{status, requiredChecks, items[]}`（或前端适配扁平数组）；

5. Testset：响应补 `scopeTags`；

6. （P1\+）test\-run / dry\-run 历史列表、逐用例结果、报告产物 URL（前端按空态处理，不阻塞联调）。

    

### 22\.4 Agent 单卡详情（前端联调，已实现）



`GET /teams/{teamId}/agents/{agentId}`（原仅有列表）新增单 Agent 详情，支持**可选 ****`projectId`**** 参数**：



```HTTP
GET /api/v1/teams/{teamId}/agents/{agentId}?projectId={projectId}
```



- `projectId` 传了：校验该 Agent 属于此项目的 Team（`agents.team_id == projects.team_id`）且调用者为项目成员；不传：仅团队可见性校验

- 响应为 `AgentResponse`（`id/name/avatar/role/capabilities/prompt/visibility/status/createdBy`），PRIVATE Agent 仅创建者可见（`prompt` 仅创建者返回）

- 错误：`404 TEAM_RESOURCE_NOT_FOUND` / `404 PROJECT_NOT_FOUND` / `404 AGENT_NOT_FOUND`

    

### 22\.5 创建项目时绑定仓库（前端额外清单 §四，已实现）



`POST /teams/{teamId}/projects` 请求体新增可选字段：



|字段|类型|说明|
|---|---|---|
|`repositoryIds`|string\[\] \(UUID\)|GitHub 授权仓库 id 列表（**`github_repositories.id`**，授权仓本地 UUID，非绑定记录 id）|



创建项目时后端逐个校验并绑定（等价于创建后逐个调 `POST /projects/{projectId}/repositories`）：



- 校验：仓库属于该团队 `ACTIVE` 安装、`authorizationStatus=AUTHORIZED`、未归档、`defaultBranch` 非空；

- 重复绑定同一仓库返回 `409 PROJECT_REPOSITORY_ALREADY_BOUND`（整体失败回滚，项目不创建）；

- 不传或传空：按原行为创建项目，之后单独绑定。

    

```JSON
{
  "name": "Qgents Web",
  "description": "Web client",
  "memberIds": ["user-uuid"],
  "repositoryIds": ["repository-local-uuid-1", "repository-local-uuid-2"]
}
```



