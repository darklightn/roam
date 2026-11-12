# 分层与包结构调研：Agent 项目能否像传统后端一样分层？

> 状态：调研笔记（2026-11-08）；阶段 1–6 已于 2026-11-09 实施完成，最终决策见 `docs/adr/0002-package-layering.md`。
> 输入：三条主张——Service 一个目录、Agent 一个目录、POJO/record 等结构对象一个目录。
> 关联：`GLOSSARY.md`、`docs/adr/0001-runtime-seams.md`、`docs/adr/0002-package-layering.md`。
> 说明：第 0–4 节保留搬迁前的现状分析与目标方案；实际实施记录见 5.1，最终结构与依赖规则见 ADR 0002。

## 0. 结论

1. **当前不是“没有分层”，而是“分了三层，但每层是杂货铺”。** `api` 只有 1 个 530 行的控制器，却内嵌了 14 个 DTO；`domain` 把 44 个类型平铺在一起；`service` 把 Agent、应用服务、策略、基础设施、横切组件、配置混在同一个命名空间里。
2. **“Service 一个目录”方向正确，但要先给 `service` 瘦身。** Agent、领域策略、审计/指标/告警、`InMemoryStore`、Spring 配置都不属于“用例服务”。否则只是把杂货铺换个门牌。
3. **“Agent 一个目录”强烈同意，但要避免“假 Agent”。** 当前 `WorkflowService` 就是 `MasterAgent`，名字与 `GLOSSARY.md` 不一致；`BookingAgent` 只有 35 行，只是 `BookingService` 的薄包装。搬进 `agent/` 不会自动让它变成真正的 Agent。目录先立住，契约和工具边界后续再深化。
4. **“所有 POJO/record 一个目录”只同意一半。** `record` 是 Java 语法，不是架构角色。领域模型、Agent/应用契约、HTTP DTO 的变化原因不同，混在一个目录会重演当前 `domain` 的问题。建议拆成三类：`domain/*`、`agent/contract`（或 `service/dto`）、`api/dto`。
5. **推荐目标：`api / agent / service / domain / infrastructure / shared` 六个顶层包。** 顶层按依赖方向分层；`domain` 内按聚合分；`api` 内放 DTO；`agent` 内放编排契约。先做行为不变的搬迁，再用 ArchUnit 固化规则。
6. **搬迁风险比想象中低。** 当前 `domain` 和 `service` 的依赖图都没有互相引用对（见 1.4），所以按聚合拆分不会立刻产生环。真正的风险是“搬完目录就以为架构变好了”，而 `WorkflowService`、`BookingService`、`ApiController` 这三个大类还在。

## 1. 现状证据

### 1.1 数量与角色

| 包 | 类型数 | 实际承载的角色 | 主要问题 |
| --- | ---: | --- | --- |
| `api` | 1 个类 + 14 个内嵌 record | HTTP 入口、请求/响应 DTO、异常映射、视图组装 | 控制器 530 行；DTO 与控制器绑死；部分接口直接返回领域对象 |
| `domain` | 44 | 7 个可变实体、22 个 record、15 个 enum；包含实体、值对象、命令、结果、审计记录 | 角色混装，但**是纯 Java**：0 个 Spring import、0 个对其他 `com.roam` 包的 import |
| `service` | 24 | 2 个 Agent、5 个用例服务、3 个 HITL 处理器、1 个校验器、1 个策略、6 个横切组件、4 个技术设施、1 个异常、1 个上下文 record | 角色混装；同包内互相引用不需要 import，耦合不可见 |
| `supplier` | 4 | 出站适配器：接口、途牛 fixture 实现、fixture 脚本、Spring 配置 | 边界已经存在，但端口接口和适配器没有分到“内/外”两侧 |
| `test` | 11 | 行为验收测试 | 平铺在 `com.roam`，未镜像生产包结构 |

`domain` 的 7 个可变类：

- `TripRequest`：行程单聚合，带版本、审批记录和状态机。
- `BookingOrder`：预订订单聚合，带本地状态迁移。
- `TravelPlan`：规划方案聚合，带选中选项和版本。
- `HitlReview`：人工审核记录。
- `WorkflowInstance`：工作流实例，带 intent 状态。
- `IntentRecord`：单个 intent 的执行记录。
- `TripRequestVersion`：行程单版本快照。

`service` 的角色可以进一步拆成：

| 角色 | 类型 |
| --- | --- |
| Agent | `WorkflowService`（实为 MasterAgent）、`BookingAgent` |
| 用例服务 | `TripRequestService`、`PlanningService`、`BookingService`、`HitlService`、`RecoveryService` |
| HITL 策略 | `ReviewDecisionHandler`、`TripRequestReviewHandler`、`BudgetExceptionReviewHandler` |
| 应用校验 | `IntentPackageValidator` |
| 策略 | `BudgetPolicy` |
| 横切 | `AuditService`、`MetricsService`、`AlertService`、`TraceService`、`AccessControl`、`Redaction` |
| 技术设施 | `InMemoryStore`、`Ids`、`CallbackSignatures`、`WorkflowConfiguration` |
| 异常 | `RoamException` |
| Agent 契约 | `TaskContext` |

### 1.2 三个具体症状

**症状一：`service` 是杂货铺，且包内耦合不可见。**

24 个类型同处 `com.roam.service`，互相引用不需要 import。看代码时无法从 import 判断“谁依赖谁”，也无法用编译器或包边界阻止 `TripRequestService` 直接调用 `BookingService` 这种跨用例依赖。包名只说明了“这些是服务”，没有说明它们各自扮演什么角色。

**症状二：`domain` 是杂货铺。**

`domain` 里同时有：

- 有状态、有行为的聚合：`TripRequest`、`BookingOrder`、`TravelPlan`；
- 值对象：`Money`、`TripStop`、`Quote`、`Actor`；
- 输入命令：`IntentPackage`、`IntentRequest`、`CallbackRequest`；
- 输出结果：`BookingResult`、`WorkflowResult`、`SubmissionResult`、`IntentResultView`；
- 审计/观测记录：`AuditEvent`、`BookingAttempt`、`CallbackObservation`；
- 15 个枚举。

这不是“领域模型”一个词能解释的。它们的变化原因不同：实体因业务规则变化，HTTP DTO 因外部契约变化，Agent 结果因编排协议变化。

**症状三：API 与领域模型直接耦合。**

`ApiController` 既返回领域 record（如 `WorkflowResult`、`BookingResult`、`AuditEvent`），又定义了 14 个内嵌视图 record（`TripRequestView`、`OrderView`、`PlanResponse` 等）。同一层内有两种风格，边界不一致：

- 有的接口返回领域对象，领域字段一改，HTTP JSON 就跟着变；
- 有的接口做了视图映射，但视图类藏在控制器里，其他适配器无法复用；
- 控制器同时负责参数解析、角色校验、DTO 映射、业务调用，530 行。

### 1.3 现有优点（搬迁时不要弄丢）

1. **`domain` 是纯的。** 当前 `domain` 没有任何 Spring、JDBC、HTTP 或 `com.roam.service/supplier/api` 的 import。这是很好的起点，应该用 ArchUnit 固化，而不是在搬目录时破坏。
2. **领域依赖图无环。** 扫描 44 个 domain 类型，没有发现互相引用对。按聚合拆分是安全的。
3. **服务依赖图也无环。** 扫描 24 个 service 类型，同样没有互相引用对。可以先机械搬迁，再逐步解耦。
4. **供应商边界已经存在。** `SupplierAdapter` + `TuniuFixtureAdapter` 已经把途牛 fixture 隔离在适配器后面（见 ADR 0001）。
5. **测试是行为级验收。** 测试通过 `AcceptanceTestBase` 直接注入服务、驱动工作流，而不是断言内部实现。搬迁包名只需要改 import，不需要重写测试逻辑。

### 1.4 依赖图扫描结果

用一个简单的 token 引用扫描（把同包内的类型名互相匹配）得到：

| 范围 | 类型数 | 有出边的类型数 | 互相引用对 | 结论 |
| --- | ---: | ---: | ---: | --- |
| `domain` | 44 | 24 | 0 | 可按聚合拆分，不会立刻产生环 |
| `service` | 24 | 19 | 0 | 可先搬迁；后续再处理 `InMemoryStore` 直接泄漏 |

这不能替代 ArchUnit，但足以说明：**当前按目录拆分的主要阻力不是循环依赖，而是命名和角色归位。**

## 2. 分层思想调研

### 2.1 传统分层：解决“依赖方向”，不自动解决“内聚”

Martin Fowler 把常见的 Presentation / Domain / Data 分层称为逻辑分层，并强调它们是**逻辑层，不是物理部署层**；分层的最大好处是缩小注意力范围：写领域逻辑时可以基本忽略 UI，把数据源当成一组抽象函数。他还指出，为了让领域不依赖数据源，可以在领域和数据源之间引入 mapper，这就是 Hexagonal Architecture 的思路。来源：[PresentationDomainDataLayering](https://martinfowler.com/bliki/PresentationDomainDataLayering.html)。

传统 Java 后端的典型形态是：

```text
controller → service → repository/mapper → database
                ↓
          entity / PO / DTO / VO
```

**它回答的问题是“谁可以依赖谁”，不是“什么应该放在一起”。** 如果 `service` 是一个大平层，所有服务共享同一个命名空间，那么分层只约束了方向，没有约束内聚。当前项目正是这种情况：`api → service → domain` 的方向没有大问题，但 `service` 和 `domain` 内部是平的。

### 2.2 DDD / 六边形 / Clean：把领域放中间

DDD 的四层通常是：

```text
interfaces / api       入站适配器（HTTP、CLI、消息）
application            用例编排、事务边界
domain                 实体、值对象、领域服务、领域策略
infrastructure         持久化、外部 API、消息、观测
```

六边形（Ports & Adapters）进一步强调：领域在中心，外部系统通过端口接口接入，适配器实现端口。Alistair Cockburn 原文里有一个很关键的提醒：如果只是“加一层”，却没有机制检测这一层是否被违规写入业务逻辑，几年后这一层就会被杂物填满。来源：[Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)。

这正好解释了为什么“只分目录”不够：**必须有机制检测依赖方向**。

Spring Modulith 把这套思想落到了 Spring Boot：

- 一个 application module 是“一个功能单元”，包含对其他模块暴露的 provided interface、内部实现、以及它依赖其他模块的 required interface；
- `ApplicationModules.verify()` 会检查模块级无环、只能通过 API 包访问其他模块的内部类型；
- 它鼓励从简单结构开始，按需演进。来源：[Spring Modulith Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)、[Verifying Application Module Structure](https://docs.spring.io/spring-modulith/reference/verification.html)。

对本项目来说，Spring Modulith 的完整模块化可能偏重，但它的两条原则值得直接借用：**无环**、**只能通过公开接口访问内部实现**。

### 2.3 Agent 架构：Agent 是角色，不是 Service 的实现细节

Anthropic 在 *Building effective agents* 中区分了 workflow 和 agent：

- **Workflow**：LLM 和工具通过预定义代码路径编排，适合可预测的任务；
- **Agent**：LLM 动态决定自己的流程和工具使用，适合需要灵活决策的任务。

文章还强调从简单开始：先用简单提示和组合模式，只有在简单方案不够时才增加多步 agentic 系统；并给出了 orchestrator-workers、routing、parallelization、evaluator-optimizer 等模式。来源：[Building effective agents](https://www.anthropic.com/engineering/building-effective-agents)。

AgentScope 文档则把 Workflow、Multi-Agent、Concurrent Agents、Routing、Handoffs 作为独立主题，同时区分 Agent 的 State/Session、Tool、MCP、Memory 等能力。来源：[AgentScope 文档](https://doc.agentscope.io/)。

对本项目的含义：

- **Agent 值得单独一层**，因为它有独立的编排契约（`TaskContext`）、独立的拓扑（MasterAgent / 子 Agent）、未来还会接入 AgentScope 的模型调用和工具权限。
- **但不要为 2 个 Agent 造一套 Agent 框架。** 当前真正有编排行为的是 `WorkflowService`（MasterAgent），`BookingAgent` 只是薄包装。先把目录和契约立住，等 AgentScope runtime 真正接入时再加深。
- **Agent 与服务是应用层的两种角色，不是上下级。** Agent 调用服务；服务不应反向调用 Agent。这个方向要在依赖规则里写清楚。

### 2.4 按层分包，还是按功能分包？

两种常见组织方式：

| 方式 | 优点 | 缺点 | 适用 |
| --- | --- | --- | --- |
| Package by layer（controller/service/domain/infra） | 依赖方向清晰，符合传统后端心智 | 每层容易变成大平层；跨功能改动分散 | 中小项目、技术分层明显 |
| Package by feature / module（trip/plan/booking） | 改动局部化，功能内聚 | 需要模块边界、事件、跨模块契约；过早拆分成本高 | 多团队、边界稳定的中大型项目 |

对本项目（74 个生产类型、约 5.7k 行）来说，**顶层按层、层内按角色/聚合**是成本最低的组合：

- 顶层分层保证依赖方向；
- `domain` 内按聚合分包保证领域内聚；
- `service` 内按用例/角色分包，而不是按技术类型继续平铺；
- 暂时不做“每个功能一个模块”的完整模块化，等边界稳定后再考虑 Spring Modulith。

### 2.5 方案对比

| 方案 | 对本项目的适配度 | 说明 |
| --- | --- | --- |
| A. 纯传统分层：controller/service/repository/entity/dto/config/util | 中 | 熟悉，但 Agent 没有位置；entity/dto 按语法分，容易把领域行为拆散 |
| B. DDD 四层 / 六边形 | 高 | 边界清晰、可替换；但完整端口/适配器改造对 MVP 偏重 |
| C. Package by feature / modular monolith | 中高 | 内聚最好；当前单上下文、类型不多，过早拆模块收益有限 |
| **D. 分层优先 + 层内按角色/聚合分包（推荐）** | **最高** | 保留传统后端心智，给 Agent 一等位置；迁移成本低，可逐步加深 |

## 3. 三条主张逐条评估

### 3.1 “Service 一个目录”：同意，但要先瘦身

同意保留 `service` 作为用例服务目录。传统后端里 `service` 就是“编排用例、组合领域能力”的地方，这个心智不需要丢。

但 `service` 不应该继续装这些东西：

- **Agent**：`WorkflowService`、`BookingAgent` → `agent/`；
- **领域策略**：`BudgetPolicy` → `service/policy`（保持 `domain` 纯净）或未来的纯领域策略；
- **技术设施**：`InMemoryStore`、`Ids`、`WorkflowConfiguration` → `infrastructure/`、`shared/`；
- **横切读模型**：`AuditService`、`MetricsService`、`AlertService`、`TraceService` → `service/audit`、`service/observability`；
- **授权策略**：`AccessControl` → `service/security`；
- **HITL 策略**：`ReviewDecisionHandler` 及实现 → `service/review`。

瘦身后的 `service` 只回答一个问题：**这个用例怎么编排？** 它不再回答“这个类是 Agent、基础设施还是配置”。

### 3.2 “Agent 一个目录”：强烈同意，但要避免“假 Agent”

这是三条里最值得做的一条。理由不是“Agent 项目就要特殊”，而是：

1. `GLOSSARY.md` 已经把 `MasterAgent`、`ItineraryManageAgent`、`BookingAgent`、`PlanAgent` 定义为不同角色；
2. ADR 0001 已经明确 `MasterAgent` 协调在 `WorkflowService`、子 Agent 执行在 `BookingAgent`；
3. Agent 有独立契约（`TaskContext`）和独立拓扑（谁调用谁、依赖顺序、并行、HITL 暂停/恢复）；
4. 未来接入 AgentScope 时，模型调用、工具权限、会话/状态会集中在这一层。

但必须同时承认当前的两个问题：

**问题一：`WorkflowService` 是 MasterAgent，但名字叫 Service。**

这违反 `GLOSSARY.md` 的 ubiquitous language。建议在搬迁时直接改名为 `MasterAgent`。当前没有 XML/SpEL 按 bean 名引用，测试按类型注入，改名是行为不变的。需要同步更新 README、ADR 0001 的措辞。

**问题二：`BookingAgent` 只有 35 行，是 Middle Man。**

它只做一件事：把 `TaskContext`、`TravelPlan`、`ItineraryOption` 转给 `BookingService.execute(...)`。这本身不是错，但要知道：**把它移进 `agent/` 不会让它变“深”。** 真正的 Agent 契约和工具边界还没有建立。建议先保留它，等 AgentScope runtime 接入时再决定是加深还是合并。

**GLOSSARY 与代码的映射缺口：**

| GLOSSARY 角色 | 当前代码 | 建议 |
| --- | --- | --- |
| `MasterAgent` | `WorkflowService` | 改名并移入 `agent/` |
| `BookingAgent` | `BookingAgent` | 移入 `agent/`，保留薄包装 |
| `ItineraryManageAgent` | `TripRequestService`（承担其领域职责） | 先不硬造 Agent 类；在文档中说明映射，等边界稳定再定 |
| `PlanAgent` | `PlanningService` | 同上 |

不要为了“目录看起来像 Agent 项目”而给每个 Service 套一个 Agent 壳。这会制造四个 35 行的 Middle Man，反而降低可读性。

### 3.3 “POJO/record 一个目录”：部分同意，要按变化原因拆

先把术语说清楚。你说的 “POVO” 我理解为 POJO / PO / VO / DTO 这类结构对象。它们在传统后端里通常按用途分：

| 角色 | 例子 | 变化原因 | 建议位置 |
| --- | --- | --- | --- |
| 实体 / 聚合 | `TripRequest`、`BookingOrder`、`TravelPlan` | 业务规则、不变量、状态机 | `domain/<aggregate>/` |
| 值对象 | `Money`、`TripStop`、`Quote`、`Actor` | 领域概念 | `domain/shared/` 或所属聚合 |
| Agent / 应用契约 | `TaskContext`、`WorkflowResult`、`BookingResult`、`SubmissionResult` | 编排协议、用例结果 | `agent/contract`、`service/dto` |
| HTTP DTO / VO | `IntentPackageBody`、`TripRequestView`、`PlanResponse` | 外部 API 兼容性 | `api/dto/` |
| 持久化记录 | `AuditEvent`、`BookingAttempt`、`CallbackObservation` | 审计、状态留存 | `domain/audit`、`domain/booking` |

**关键点：`record` 是语法，不是角色。** 一个 record 可以是值对象、HTTP DTO、Agent 结果、持久化记录。按“所有 record 放一起”组织，等于按 Java 关键字分层，而不是按职责分层。

一个反例：

```text
model/
├── TripRequest.java        // 有状态机、有审批规则
├── IntentPackageBody.java  // HTTP 入参，随 API 版本变化
├── WorkflowResult.java     // Agent 编排结果
└── Money.java              // 值对象
```

这个目录会同时因为业务规则、API 兼容性、Agent 协议而变化。更糟的是，`domain` 里的实体可能直接 import `IntentPackageBody`，API 字段一改就污染领域。

推荐的拆法：

```text
domain/trip/TripRequest.java          // 业务规则
api/dto/IntentPackageBody.java        // HTTP 契约
agent/contract/WorkflowResult.java    // Agent 协议
```

**“一个目录”的合理妥协：** 如果你希望结构对象尽量集中，可以接受 `domain/model` 作为领域结构对象的统一入口，但不要把 HTTP DTO 和 Agent 契约也塞进去。领域内部再按聚合分子包；如果一个子包只有 1 个类，就先留在上层，等出现第 3 个再拆（rule of three）。

**嵌套 record 不一定要搬。** `MetricsService.MetricsSnapshot`、`TraceService.TraceChain`、`RecoveryService.RecoveryDrillReport` 这类“只作为某个模块唯一返回类型”的 record，留在原类里反而内聚。只有当它跨模块使用，或成为稳定的外部契约时，才值得提出来单独放。

## 4. 推荐目标结构

### 4.1 目录树

```text
com.roam
├── RoamApplication.java
├── api/                          # 入站适配层：HTTP
│   ├── ApiController.java
│   ├── ApiExceptionHandler.java  # 可选：从 ApiController 拆出异常映射
│   └── dto/                      # 只放 HTTP 契约
│       ├── IntentPackageBody.java
│       ├── SelectionBody.java
│       ├── DecisionBody.java
│       ├── TripRequestView.java
│       ├── OrderView.java
│       ├── PlanResponse.java
│       └── ...
├── agent/                        # Agent 编排层（应用层的一种角色）
│   ├── MasterAgent.java          # ← WorkflowService 改名
│   ├── BookingAgent.java
│   ├── TaskContext.java          # Agent 输入契约
│   └── contract/                 # Agent 输出/编排契约
│       ├── WorkflowResult.java
│       ├── WorkflowPreparation.java
│       └── IntentResultView.java
├── service/                      # 应用服务层：用例编排
│   ├── TripRequestService.java
│   ├── PlanningService.java
│   ├── BookingService.java
│   ├── HitlService.java
│   ├── RecoveryService.java
│   ├── IntentPackageValidator.java
│   ├── dto/                      # 应用层结果（跨适配器复用）
│   │   ├── BookingResult.java
│   │   └── SubmissionResult.java
│   ├── review/                   # HITL 决策策略
│   │   ├── ReviewDecisionHandler.java
│   │   ├── TripRequestReviewHandler.java
│   │   └── BudgetExceptionReviewHandler.java
│   ├── audit/                    # 审计
│   │   └── AuditService.java
│   ├── observability/            # 指标/告警/追踪读模型
│   │   ├── MetricsService.java
│   │   ├── AlertService.java
│   │   ├── TraceService.java
│   │   └── TravelAlert.java
│   ├── security/
│   │   └── AccessControl.java
│   └── policy/
│       └── BudgetPolicy.java
├── domain/                       # 纯领域模型：无 Spring、无 IO
│   ├── trip/                     # TripRequest 聚合
│   ├── plan/                     # TravelPlan 聚合
│   ├── booking/                  # BookingOrder 聚合
│   ├── review/                   # HitlReview / 审批决策
│   ├── workflow/                 # WorkflowInstance / Intent
│   ├── audit/                    # AuditEvent
│   ├── shared/                   # Actor、Role、Money、FailureReason...
│   └── port/                     # SupplierAdapter 等出站端口接口
├── infrastructure/               # 出站适配器与技术设施
│   ├── persistence/
│   │   └── InMemoryStore.java
│   ├── supplier/
│   │   ├── TuniuFixtureAdapter.java
│   │   ├── FixtureScript.java
│   │   ├── SupplierConfiguration.java
│   │   └── CallbackSignatures.java
│   └── config/
│       └── WorkflowConfiguration.java
└── shared/                       # 无业务依赖的通用类型
    ├── Ids.java
    ├── Redaction.java
    └── RoamException.java
```

**为什么 `service` 和 `agent` 是平级，而不是 `agent` 在 `service` 之上：** 两者都是应用层角色。Agent 负责编排和决策，Service 负责用例执行。规则是 `agent → service` 可以，`service → agent` 不可以。这样既保留了“Agent 是一个独立角色”，又不会把它误当成业务服务的上层。

### 4.2 现有类型迁移映射

| 现有类型 / 组 | 目标位置 | 说明 |
| --- | --- | --- |
| `ApiController` + 14 个内嵌 record | `api/ApiController` + `api/dto/*` | 先提 DTO；控制器拆分放后续 |
| `WorkflowService` | `agent/MasterAgent` | 改名 + 移动；行为不变 |
| `BookingAgent` | `agent/BookingAgent` | 先保留薄包装 |
| `TaskContext` | `agent/TaskContext` | Agent 输入契约 |
| `WorkflowPreparation` | `agent/contract/WorkflowPreparation` | 从 `WorkflowService` 内嵌 record 提出 |
| `WorkflowResult`、`IntentResultView` | `agent/contract/*` | 只被 `MasterAgent` 和 API 使用 |
| `TripRequestService`、`PlanningService`、`BookingService`、`HitlService`、`RecoveryService` | `service/*` | 用例服务 |
| `IntentPackageValidator` | `service/IntentPackageValidator`（或 `service/validation/`） | 应用层前置校验 |
| `ReviewDecisionHandler` + 2 个实现 | `service/review/*` | HITL 策略 |
| `AuditService` | `service/audit/*` | 审计应用服务 |
| `Redaction` | `shared/*` | 纯脱敏工具；API、审计、供应商回调都使用，放 shared 避免 infra→service 反向依赖 |
| `MetricsService`、`AlertService`、`TraceService` | `service/observability/*` | 读模型/观测，不放到 infrastructure 以免 API 直接依赖 infra |
| `AccessControl` | `service/security/*` | 应用授权策略 |
| `BudgetPolicy` | `service/policy/*` | 暂不放进 `domain`，避免 domain 引入 Spring |
| `InMemoryStore` | `infrastructure/persistence/InMemoryStore` | 持久化 seam 的适配器 |
| `SupplierAdapter` | `domain/port/SupplierAdapter` | 端口接口归“内” |
| `TuniuFixtureAdapter`、`FixtureScript`、`SupplierConfiguration`、`CallbackSignatures` | `infrastructure/supplier/*` | 适配器与技术细节 |
| `WorkflowConfiguration` | `infrastructure/config/*` | Spring 装配 |
| `Ids`、`Redaction`、`RoamException` | `shared/*` | 无业务依赖的通用类型/工具 |
| `BookingResult`、`SubmissionResult` | `service/dto/*` | 用例结果；Service 和 Agent 都可使用 |
| `TravelAlert` | `service/observability/*` | 告警读模型 |
| `AuditEvent` | `domain/audit/*` | 审计业务记录 |
| `domain` 的 trip 组 | `domain/trip/*` | `TripRequest`、`TripRequestVersion`、`TripRequestData`、`TripStop`、`TripRequestStatus`、`ApprovalRecord`、`Suggestion`、`EmployeeProfile` |
| `domain` 的 plan 组 | `domain/plan/*` | `TravelPlan`、`PlanView`、`PlanViewType`、`ItineraryOption`、`Quote`、`QuoteStatus` |
| `domain` 的 booking 组 | `domain/booking/*` | `BookingOrder`、`BookingOrderState`、`BookingAttempt`、`AttemptOutcome`、`OperationType`、`CallbackRequest`、`CallbackObservation`、`CallbackStatus` |
| `domain` 的 review 组 | `domain/review/*` | `HitlReview`、`ReviewStatus`、`ReviewType`、`Decision` |
| `domain` 的 workflow 组 | `domain/workflow/*` | `WorkflowInstance`、`WorkflowStatus`、`IntentPackage`、`IntentRequest`、`IntentRecord`、`IntentStatus`、`IntentType` |
| `domain` 的 shared 组 | `domain/shared/*` | `Actor`、`Role`、`Money`、`FailureReason`、`FailureType` |

这张表里没有 `PO`。当前 `InMemoryStore` 直接持有领域实体，没有独立的持久化对象；等真正接 MySQL 时再决定是否引入 PO，不要为了凑“PO/VO/DTO”而提前造一层。

### 4.3 依赖规则

| 层 | 允许依赖 | 禁止依赖 | 说明 |
| --- | --- | --- | --- |
| `api` | `agent`、`service`、`domain`、`shared` | `infrastructure` | 入站适配器不直接碰持久化/供应商 |
| `agent` | `service`、`domain`、`infrastructure`、`shared` | `api` | Agent 可以调用用例和适配器；不反向依赖入口 |
| `service` | `domain`、`infrastructure`、`shared` | `agent`、`api` | Service 不调用 Agent；不依赖 HTTP |
| `domain` | `shared` | `api`、`agent`、`service`、`infrastructure` | 纯领域；无 Spring、无 IO |
| `infrastructure` | `domain`、`shared` | `api`、`agent`、`service` | 适配器实现端口；不被上层反向依赖 |
| `shared` | 无 | 所有业务包 | 通用类型 |

补充规则：

1. **无环。** 任意两个顶层包之间不能形成循环。
2. **`domain` 不出现 Spring/JDBC/HTTP import。** 这是当前已经做到的，必须保持。
3. **`api` 只通过应用层访问基础设施。** 如果 API 需要审计、指标、告警，调用 `service/audit`、`service/observability`，不要直接 import `infrastructure`。
4. **`service` 不调用 `agent`。** Agent 是编排者，Service 是被编排者。
5. **`infrastructure` 不反向依赖 `service`。** 如果 `InMemoryStore` 需要业务规则，说明规则放错了位置。

**ArchUnit 守卫（已落地在 `src/test/java/com/roam/architecture/ArchitectureTest.java`）：**

```java
@AnalyzeClasses(packages = "com.roam")
class ArchitectureTest {

    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("com.roam.api..")
            .layer("Agent").definedBy("com.roam.agent..")
            .layer("Service").definedBy("com.roam.service..")
            .layer("Domain").definedBy("com.roam.domain..")
            .layer("Infrastructure").definedBy("com.roam.infrastructure..")
            .layer("Shared").definedBy("com.roam.shared..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Agent").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Api", "Agent")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Api", "Agent", "Service", "Infrastructure")
            .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Agent", "Service")
            .whereLayer("Shared").mayOnlyBeAccessedByLayers(
                    "Api", "Agent", "Service", "Domain", "Infrastructure");

    @ArchTest
    static final ArchRule domainIsPure = noClasses()
            .that().resideInAPackage("com.roam.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "com.roam.api..", "com.roam.agent..",
                    "com.roam.service..", "com.roam.infrastructure..");

    @ArchTest
    static final ArchRule noCycles = slices()
            .matching("com.roam.(*)..")
            .should().beFreeOfCycles();
}
```

这张规则表是“目标态”。当前 `MasterAgent`（`WorkflowService`）直接使用 `InMemoryStore`、`AuditService`、`AccessControl`，搬迁后如果 `AuditService`/`AccessControl` 在 `service`、`InMemoryStore` 在 `infrastructure`，规则仍然允许；但更理想的做法是把工作流状态的读写收进 `service` 层的仓库/状态服务，Agent 只依赖用例接口。这个深化可以放在搬迁之后，不要和目录搬迁混在一个提交里。

## 5. 迁移方案

原则：**先搬迁，后深化；每个阶段都能编译、能跑测试、能单独回滚。** 不要在一个提交里同时改目录、改类名、改行为。

| 阶段 | 动作 | 验证 |
| --- | --- | --- |
| 0 | 基线：`./mvnw test` 全绿；建分支 `refactor/package-layering` | 记录测试数量 |
| 1 | 叶子设施先走：`supplier/*` → `infrastructure/supplier/*`；`InMemoryStore` → `infrastructure/persistence`；`Ids`、`RoamException` → `shared`；`WorkflowConfiguration` → `infrastructure/config` | `./mvnw -q test` |
| 2 | `service` 瘦身：审计/观测/授权/策略/校验/HITL 策略各自归位 | `./mvnw -q test` |
| 3 | `agent` 立起来：`BookingAgent`、`TaskContext` → `agent`；`WorkflowService` → `agent/MasterAgent` 并改名；`WorkflowPreparation`/`WorkflowResult`/`IntentResultView` → `agent/contract` | `./mvnw -q test`；README/ADR 措辞同步 |
| 4 | `domain` 按聚合拆分：`trip/plan/booking/review/workflow/audit/shared/port` | `./mvnw -q test`；检查无环 |
| 5 | `api` 提 DTO：14 个内嵌 record → `api/dto`；可选拆 `ApiController` | `./mvnw -q test`；HTTP JSON 不变 |
| 6 | 测试包镜像生产包（可选）；扩展 ArchUnit 规则；CI 跑架构测试 | `./mvnw -q test` |
| 7 | 写 ADR 0002，更新 README、ADR 0001、`docs/agents/domain.md` 的目录说明 | 文档 review |

### 5.1 阶段 1–7 实施记录（2026-11-09）

**阶段 1：叶子设施**

- `supplier/*` → `infrastructure/supplier/*`。
- `InMemoryStore` → `infrastructure/persistence`。
- `Ids`、`RoamException` → `shared`。
- `WorkflowConfiguration` → `infrastructure/config`。
- 搬迁时发现 `InMemoryStore.auditSink()` 是包私有的，只允许同包的 `AuditService` 访问。跨包后把它改成公开的原子 `appendAudit(Function<List<AuditEvent>, AuditEvent>)`：调用方仍不能拿到可变列表，哈希链计算仍留在 `AuditService`。

**阶段 2：service 瘦身**

- `AuditService` → `service/audit`。
- `MetricsService`、`AlertService`、`TraceService` → `service/observability`。
- `AccessControl` → `service/security`；`BudgetPolicy` → `service/policy`。
- `ReviewDecisionHandler` 及两个实现 → `service/review`。
- `Redaction` → `shared`；`CallbackSignatures` → `infrastructure/supplier`。
- `Redaction.sha256` 从包私有改为 public，供 `AuditService` 和 `CallbackSignatures` 跨包使用。

**阶段 3：agent 立起来**

- `WorkflowService` → `agent/MasterAgent`，类名同步改名。
- `BookingAgent`、`TaskContext` → `agent`。
- `WorkflowResult`、`IntentResultView`、`WorkflowPreparation` → `agent/contract`；`WorkflowPreparation` 从 `MasterAgent` 的内嵌 record 提为独立类型。
- API 与测试改为注入 `MasterAgent`（字段名 `masterAgent`），不再引用 `WorkflowService`。

**阶段 4：domain 拆分**

- `domain` 按聚合拆成 `trip/plan/booking/review/workflow/audit/shared/port`。
- `BookingResult`、`SubmissionResult` → `service/dto`；`TravelAlert` → `service/observability`。
- `SupplierAdapter` → `domain/port`，途牛适配器仍留在 `infrastructure/supplier`。

**阶段 5：api/dto**

- `ApiController` 的 14 个内嵌 record 提到 `api/dto`。
- `WorkflowServiceTakeover` 改名为 `WorkflowTakeoverView`；record component 名未变，HTTP JSON 不变。

**阶段 6：ArchUnit 收紧**

- 增加六层 `layeredArchitecture` 规则、`domain` 子包无环、`api/dto` 与 `service/dto` 依赖限制。
- 最终 11 条架构规则。

**阶段 7：文档**

- 新增 `docs/adr/0002-package-layering.md`。
- 更新 `README.md`、`docs/adr/0001-runtime-seams.md`、`docs/agents/domain.md`。

每个阶段后都执行 `./mvnw test`：63 个测试全绿，ArchUnit 规则全绿。

搬迁操作建议：

- 用 IDE 的 Move/Refactor（IntelliJ 会自动改 package 和 import），不要手写 `sed` 批量替换；这个仓库不到 100 个生产类型，IDE 足够。
- 每个阶段一个 commit，提交信息用 `refactor(package): ...`，不混行为变更。
- 改名 `WorkflowService` → `MasterAgent` 时，注意 Spring 默认 bean 名会从 `workflowService` 变成 `masterAgent`。当前没有 `@Qualifier`、XML、SpEL 按名字引用；测试按类型注入，所以安全。改完搜索一遍 `workflowService` 字符串即可。
- 移动 `ApiController` 的内嵌 record 不会改变 JSON 字段名，因为 record component 名不变；但要把 `WorkflowServiceTakeover` 这类名字顺便改成 `WorkflowTakeoverView` 之类的视图名，并同步测试中的 JSON 断言。
- 每阶段失败最多重试 5 次；如果同一阶段连续失败，回滚该 commit，不要把问题带进下一阶段。

## 6. 风险与反模式

1. **“目录搬家 = 架构改善”的幻觉。** 搬完后 `WorkflowService`（422 行）还是 422 行，`BookingService`（623 行）还是 623 行，`ApiController`（530 行）还是 530 行。目录只解决“东西放哪”，不解决“模块太浅/太大”。搬迁后应单独开任务做行为不变的拆分（例如把 `BookingService` 的取消/回调/售后拆开，把 `MasterAgent` 的编排与工作流状态读写分开）。
2. **过度分层。** 不要为 1 个类建一个包。规则：同一角色/聚合至少有 3 个类型，或已经出现跨模块使用，才值得单独建子包。`service/security/AccessControl` 只有一个类，可以接受，因为它是一个明确的角色；但不要复制这种模式到所有地方。
3. **共享内核膨胀。** `domain/shared` 很容易变成第二个 `domain`。只放真正跨聚合的值对象（`Money`、`Actor`、`FailureReason`），不要把某个聚合专属的类型塞进去。
4. **端口和适配器只做一半。** 把 `SupplierAdapter` 放进 `domain/port`、把 `TuniuFixtureAdapter` 放进 `infrastructure/supplier` 是好的一步；但如果 `PlanningService`/`BookingService` 仍然直接依赖 fixture 的具体行为，端口就没有真正起作用。当前它们依赖的是 `SupplierAdapter` 接口，已经不错；后续保持。
5. **`InMemoryStore` 的泄漏。** 它有 11 个左右的服务直接调用 `store.trips()`、`store.orders()` 等。搬到 `infrastructure/persistence` 只是位置变了，没有解决“服务直接操作存储 map”的问题。真正的深化是引入 repository 端口；这应该是 ADR 0002 之后的独立任务，不要塞进目录搬迁。
6. **API 破坏。** 提 DTO 时保持 record component 名和顺序不变；如果有重命名，先加兼容字段或同步测试。`ApiSurfaceTest` 是验收网，先跑它。
7. **测试包搬迁。** 测试目前平铺在 `com.roam`，不影响运行；可以先不动。等生产包稳定后，再把测试镜像到 `com.roam.api`、`com.roam.service` 等，避免一次性大 diff。
8. **Agent 命名通胀。** 不要因为目录叫 `agent`，就把 `TripRequestService`、`PlanningService` 都改名成 `ItineraryManageAgent`、`PlanAgent`。除非它们真的具备 Agent 的契约、工具和决策边界，否则只是给 Service 戴帽子。

## 7. 决策结果（ADR 0002，2026-11-09）

| 问题 | 决策 |
| --- | --- |
| 顶层叫 `service` 还是 `application`？ | `service`，保留传统后端心智。 |
| 审计/观测放 `service` 还是 `infrastructure`？ | `service/audit`、`service/observability`，避免 `api` 直接依赖 `infrastructure`。 |
| `SupplierAdapter` 是否移到 `domain/port`？ | 是；适配器仍留在 `infrastructure/supplier`。 |
| `WorkflowService` 先改名还是先拆分？ | 先改名搬迁为 `agent/MasterAgent`，拆分留作后续。 |
| 是否引入 ArchUnit？ | 是；已加入 11 条规则，并接入 `./mvnw test`。 |
| `ApiController` 是否拆成多个控制器？ | 先提 `api/dto`；控制器拆分留作后续。 |

## 8. 参考来源

- Martin Fowler, *PresentationDomainDataLayering*：<https://martinfowler.com/bliki/PresentationDomainDataLayering.html>
- Alistair Cockburn, *Hexagonal Architecture (Ports & Adapters)*：<https://alistair.cockburn.us/hexagonal-architecture/>
- Spring Modulith, *Fundamentals*：<https://docs.spring.io/spring-modulith/reference/fundamentals.html>
- Spring Modulith, *Verifying Application Module Structure*：<https://docs.spring.io/spring-modulith/reference/verification.html>
- Anthropic, *Building effective agents*：<https://www.anthropic.com/engineering/building-effective-agents>
- AgentScope 文档：<https://doc.agentscope.io/>
- 本仓库 `GLOSSARY.md`：MasterAgent、ItineraryManageAgent、Agent、Intent 等术语。
- 本仓库 `docs/adr/0001-runtime-seams.md`：MasterAgent 在 `WorkflowService`、BookingAgent、InMemoryStore seam、SupplierAdapter。
