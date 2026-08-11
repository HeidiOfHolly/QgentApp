---
name: design
description: 当用户需要设计界面、创建或调整 UI 布局、页面风格时使用。仿照 Qgents Web 产品原型风格设计。
---

# 界面设计任务

## 你的任务

当用户需要设计界面时，仿照 `D:\sundries\study\SummerCamp\末期\Qgents-Web-Prototype-v2\qgents-prototype-v2` 中的图片风格进行设计。

## 参考原型

参考目录中共有 18 张高保真页面图，按页面类型查找对应参考图：

| 页面场景 | 参考图 |
|---|---|
| 登录 / 注册 | `01-login-register.png` |
| 个人中心 / 切换器 | `02-personal-center-switcher.png` |
| 团队 / 项目中心 | `03-team-project-hub.png`、`04-create-project-permissions.png` |
| 项目概览 | `05-project-overview.png` |
| 群聊 / 消息 | `06-branch-group-chat.png` |
| 任务列表 / 详情 | `07-task-center.png`、`08-task-detail-custom-workflow.png` |
| 工作流编辑器 | `09-workflow-editor.png` |
| 资源 / 管理列表 | `10-agent-resource-pool.png`、`11-branch-management.png`、`12-project-testset.png` |
| 代码审查 / Diff | `13-diff-cr-comments.png`、`14-mr-dryrun-cq.png` |
| 成员 / 权限 / 设置 | `15-project-members-permissions.png`、`18-project-settings-quality-gates.png` |
| 通用 / 资产 | `16-shared-skill.png`、`17-shared-memory.png`、`00-overview-contact-sheet.png`(总览) |

## 核心元素

- 主色：`#00183A`
- 风格：现代风、简约

## 设计规范

### 颜色
- 主色(primary)：`#00183A`，用于按钮、强调元素、选中态
- 深色背景页面：大面积深海军蓝铺底，文字用白/浅灰
- 浅色内容区：背景偏白/浅灰，文字用深色，主色仅用于点缀强调
- 成功/错误/警告：使用克制的中性色系，避免高饱和撞色

### 排版与布局
- 大量留白，元素间距适当，避免内容拥挤
- 标题层级清晰：主标题加粗、副标题浅色小号
- 卡片化内容区，圆角适中(8-16dp)，弱阴影或无阴影
- 表单/列表行高充足，触控区域舒适

### 组件
- 按钮：主按钮用主色实底，次要操作用文字/描边按钮
- 输入框：圆角描边样式，聚焦时主色描边
- 状态：用文字标签或小圆点表示，不滥用图标和花哨动效

### 落地指南
- XML 布局优先使用 Material 组件(MaterialButton、TextInputLayout、MaterialCardView)

## 流程

1. 确定目标页面类型，在参考原型中定位对应参考图
2. 需要时打开参考图确认布局、配色与层级
3. 按本规范设计，再落地到当前项目的 XML/主题/资源
