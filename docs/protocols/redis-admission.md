# Redis共享队列、Pool Epoch与Lua原子协议

> 规范等级：P0 详细协议设计候选；按对应Phase首次实现并执行实际测试。本页不是运行日志。

## 1. 目标与适用阶段

P7仅NORMAL，P9新增LOW，P10获批准EMERGENCY 4:2:1公平授位、不抢占运行任务。P7 CapacityPool由Core创建、绑定与激活，Pool默认DISABLED，resourceIdentityCode唯一安全资源身份，真实审批证据必要。Lua不能直接读取INFO runId；Gateway和Core各自受信客户端独立获取主节点runId并在Lua Guard检验，不确定fail-closed。TTL不是物理GPU停机证据；P7 activation不依赖P12 probes。

## 2. 参与者与执行流程

具体参与者遵守[架构边界](../03-architecture.md)、[内部RPC](../api/internal-rpc.md)以及Core唯一Permit/事务约束，客户端不提供权威费用、治理结果或真实ProviderSecret。

## 3. 规范字段、状态与失败恢复合同
**问题与技术选择**：Redis只负责容量/排队/RPM等**短期流量事实**，不负责授权资金、真实GPU是否已停止或真实请求原文；MySQL Core独占资金许可。P1可使用Redis基础设施，**P7才启用实际Admission**；P9合法开启LOW，P10合法开启EMERGENCY。只选择单一Redis实例/HA endpoint上的**同slot原子Lua**，不引入分布式锁框架、MQ排队或第二个Gateway数据库。这样减少多JVM重复许可，但增加Redis运行代次与异常重启栅栏，必须显式设计。

### 3.1 固定数据结构与Key/TTL

所有同Pool写Key统一使用Hash Tag `{pool:<poolId>}`（逻辑形如 `aigate:{pool:41}:wait:NORMAL`），不可把跨slot keys作为一个Lua脚本入参；`poolId` 是 Core `capacity_pool_policy.id`，不是客户端提供的字符串。只保存 opaque Invocation ID、Gateway instance ownerId、随机ticket/leaseToken**不可逆摘要**、安全状态、时间戳和有限数字，**禁止存messages、employee/agent/management/Provider原Key、供应商密文、财务金额、真实Model回复**。

| 名称（同一hash tag） | Redis类型 | 内容/用途 |
|---|---|---|
| `guard` | HASH | `admissionEpoch`（Core DB代次）、`redisRunId`（Redis实际运行代次）、`state=OPEN\|RECOVERING\|BLOCKED`、`policyVersion`、`openedAtMs`；Guard缺失或不匹配一律拒新准入 |
| `seq` | STRING counter | 同Pool单调 WAITING序号，只由Lua INCR，64位上界前要重建受控epoch |
| `wait:NORMAL`,`wait:LOW`,`wait:EMERGENCY` | ZSET | member=opaque ticketId；score=本epoch递增seq，分档FIFO；P7只准NORMAL |
| `ticket` | HASH（受限小条目） | `ticketId→invocationId,ownerId,serviceClass,queueDeadlineMs,createdAtMs`，不含原文与Key |
| `active` | HASH | `invocationId→ownerId,leaseDigest,ticketId,epoch,admittedAtMs,runDeadlineMs`；一Invocation最多一租约 |
| `activeExpiry` | ZSET | member=invocationId、score=最迟心跳毫秒；**只用于发现可疑孤儿，不能作为物理GPU释放证据** |
| `rpm` | ZSET | member=一次唯一被授予ACTIVE的admissionId，score=Redis服务器时间毫秒；过去60s滚动窗口，数量≤rpmLimit |
| `scheduler` | HASH | `cycleCursor` 0～6、`weightsRevision`、各队列等待观测；固定周期 [E,E,E,E,N,N,L] |
| `quarantine` | HASH | `invocationId→reason,detectedAtMs`，仅安全元数据；存在未核验本地物理任务的池不能开放新ACTIVE许可 |

**时间与TTL约定**：所有准入Lua使用Redis TIME（不相信客户端时钟），Gateway传的deadline必须与Core在MySQL保存的 `invocation.queue_deadline_at`/Pool上界核对。排队deadline最多 `maxQueueMs`、入ACTIVE后运行上界 `maxRunMs`，TTL不能延长硬上界。Redis的过期键回收只释放**已经被证明可安全释放的容量计数**；未知执行状态转 `quarantine`/阻止进一步容量投放，尤其SELF_HOSTED。REDIS Key生命周期至少覆盖对应本epoch运行窗口+保守恢复窗口；不能让整个guard自动TTL到期后被某客户端重新初始化为OPEN。

**对V7规范DDL的唯一修改**：`capacity_pool_policy` 加 `admission_epoch BIGINT NOT NULL DEFAULT 0` 且参与非负CHECK（已直接修改D36的V7 CREATE TABLE；没有新增表，Core仍54表）。真正开放或重建池时Core持行锁将 epoch 递增，Redis Guard在受控管理动作中绑定新epoch和当前Redis runId；普通Gateway无权更新Core epoch、把BLOCKED改OPEN、清除quarantine。P7既有 `capacity_pool_policy.status=ACTIVE|DISABLED` 与epoch是两轴：status允许业务配置，epoch隔离Redis失效前旧票据，不拿version当运行代次。

### 3.2 Lua 原子命令合同（不是可让执行AI自由发明语义）

| 原子命令 | 前置参数和判断 | 唯一允许的变化/结果 |
|---|---|---|
| `ENQUEUE_V1` | poolId,epoch,ownerId,invocationId,ticketId,serviceClass,absoluteDeadlineMs；Core Preflight已登记、Gateway仍持内存Prompt；guard OPEN/runId和policyVersion一致，当前类Phase已激活 | 原子dedupe (poolId,invocationId)，按允许类入有界FIFO；成功 `WAITING(ticketId,positionHint)`；池满`QUEUE_FULL`，过期`DEADLINE_EXCEEDED`，重放不能再产生第二ticket |
| `PROMOTE_V1` | poolId,epoch,ownerId,ticketId,now来自Redis TIME；Pool maxActive/maxWaiting/rpmLimit；队列最老合法ticket且owner仍能认领 | 同slot读取guard/queue/active；按服务档权重选**符合次序**的一个ticket，原子移WAITING加ACTIVE+rollingRPM、返回随机leaseToken的**摘要状态**和固定runDeadline，原始leaseToken只能由owner生成并提交摘要，非owner不能认领别人的ticket |
| `INSPECT_V1` | poolId,epoch,invocationId,ownerId,rawLeaseToken；由Core独立Redis客户端执行，不能相信Gateway的`leaseValid=true` | 原子验证Guard OPEN/runId/epoch、active真实owner与leaseDigest、runDeadline、policyVersion和无被隔离标记；返回`VALID{remainingMs}`或`INVALID/UNCERTAIN`，不自动续期、不授新租约 |
| `RENEW_V1` | ownerId+rawLeaseToken+invocationId+epoch；原ACTIVE尚未到硬runDeadline；物理任务仍存活状态由受信owner/上游核查 | 仅受原owner续同一lease的心跳期限，永不超过最初runDeadline；令牌/代次不一致拒绝；不改预算/Permit |
| `CANCEL_WAIT_V1` | owner+ticketId+epoch；未PROMOTED | 原子移除等待与ticket元信息；已ACTIVE不得误删；Gateway内存Prompt释放，仍需Core安全收据状态 |
| `RELEASE_V1` | owner+rawLeaseToken+invocationId+epoch+可信执行终止分类 | 仅当**真实上游任务结束/已证明未开始**才原子删除ACTIVE与相关索引，重复释放安全NOOP；证明不充分则`QUARANTINE`并拒新配额，绝不能靠HTTP断开释放物理GPU |
| `CLOSE_EPOCH_V1` | 仅Core受信运维控制路径和DB epoch核对 | 阻止所有新排队/Promote/Renew，记录清算/尚未知租约并由Core治理账务；Gateway业务身份无权自行重新OPEN |

这里的`PROMOTE`严格实现**授位、不是执行一次Provider**。如果Gateway在PROMOTE成功后丢响应，不能仅把PROMOTE重放当成新的lease：查询同 Invocation ACTIVE归属+令牌摘要（owner有内存rawToken）可继续未发送的逻辑请求；缺原token或owner重启丢Prompt时保守废弃/隔离，不能生成另一个可收费调用。Core的Permit仍然是唯一资金/出站授权。

### 3.3 加权公平/同Pool容量与RPM

P7只有NORMAL能进队列。P9允许已批准LOW，P10允许经真实双人审批的EMERGENCY；**三个类只有排队权重区别，不改变MaxActive/MaxWaiting/RPM、不抢占任何运行中GPU/SSE**。P10的单个Pool周期选择表固定为 `EMERGENCY,EMERGENCY,EMERGENCY,EMERGENCY,NORMAL,NORMAL,LOW`，下一个调度游标持久在Redis HASH；当前槽队列为空时向下一非空队列前进，任何当前有效等待类在有限最多7个成功授位（其它类持续非空）内会被选中至少一次。各档内按最早seq FIFO，过期/owner丢失票据跳过并清理。数字`4:2:1`表示**长期授位机会**，不是毫秒占比或已经运行任务可抢占配额。

`rpm`使用60s精确滚动ZSET在PROMOTE时入记账；即使下游失败但已经消耗一次准入也不能立即删RPM记录来放大免费重试。即使内部队列票据有多个，Active也是对Pool全局计数；Provider自己的外部429只会使健康状态降级，不授权本Pool超发。无可用配额时由限时WAITING而不是持有MySQL资金长事务排队。

### 3.4 崩溃、Redis重启/故障与真实容量隔离

- **Redis与Core如何真正核对运行代次**：Redis Lua**不能直接调用`INFO server`读取当前run_id**，因此受信Gateway和Core客户端须在执行Lua之前从其当前连接的Redis主节点独立读取实际`run_id`，连同Core DB `capacity_pool_policy.admission_epoch`作为期望值传入脚本；Lua只对`guard.redisRunId/guard.admissionEpoch`做原子字符串比对，不接受客户端声称的未知身份作为权威。**Core不相信Gateway所传runId**：Permit前由Core自己的Redis客户端获取当前主节点`run_id`并再执行`INSPECT_V1`，与DB epoch/Guard共同核验；GateWay在真实Provider发包前再自查一次。因`INFO`和Lua不是一个Redis原子动作，若两个操作之间发生主切换、网络不确定或两次观察不一致，必须当作`UNCERTAIN`拒绝新出站，进入Pool恢复栅栏。Redis冷重启即使RDB恢复旧键也会改变真实runId，旧Guard不得复用；托管Redis若无法提供受信的运行代次/故障识别能力，则该Deployment不得在默认协议下开放收费准入，需由总控另外评审一致的替代代次证明。
- **Core与Redis检查到Gateway发包之间存在时间竞态**：没有任何Redis/Mysql分布式事务可以对物理外部HTTP做完全原子保护。Gateway必须在第一次确认的Permit之后、实际发包前最后INSPECT租约；如果失效则不发包，已写Intent维持受限待核对直至可信PROVEN_NOT_SENT。若INSPECT成功后系统故障，Provider是否收到仍按UNKNOWN，不重试。不能对第三方供应商宣称协议层真正exactly-once。
- **Gateway进程死亡**：Redis ticket可能仍在，但原messages只在死亡进程有界内存，别的Gateway不得认领其同票据为新请求；未出站ticket可以按Core安全路径取消、在途已可能出站原资金留PENDING。SELF_HOSTED只有真实任务终止确认才释放PhysicalActive；**超时/Redis TTL/下游断开不是物理释放证据**。
- **Pool从BLOCKED/RECOVERING恢复OPEN**：管理员必须取得Core DB可用、核对旧epoch受信活跃本地推理任务及可能未知Provider出站，必要时等待证据/手动隔离；Core在短事务升epoch，受信初始化新Guard并在上线前自检；任何未经盘点的 SELF_HOSTED active 都必须从可用物理名额中保守预扣或维持Pool BLOCKED，不能仅因Redis计数为0就开放全部GPU。未清理的财务风险不能因重开新epoch回写RELEASED。
- **故障处理业务结果**：Redis命令超时/读写冲突/不一致 →新Admission 503 ADMISSION_BLOCKED或429 ADMISSION_QUEUE_FULL（按真实原因区分）、Provider计数0；已经出站的请求继续尝试报告结果/对账，不要求Redis活着才能入MySQL正常结算。P12将全局停入场状态加入独立MySQL运营权威表（D55），P7此前每Pool依赖上述PoolConfig + RedisGuard栅栏即可达成fail closed。

### 3.5 必须由本地AI交付的测试而非自由修改

P7实际测试`ENQUEUE`重复与并发Pool maxWaiting、FIFO顺序、RPM滚动、Core自身INSPECT拒假lease、Gateway重启后Prompt不能重跑、Redis主故障runId/Guard不匹配阻止任何新的Provider HTTP；P9测试LOW缺授权或NORMAL切LOW仅一次；P10测试7槽在持续饱和下长期4:2:1及NORMAL/LOW不饥饿，非法EMERGENCY无法插队。两Gateway并发使用同一真实Redis+FakeProvider计数，MySQL资金风险真实断言，结果/时限/工具版本报告。当前全部NOT RUN。




## 4. 验收标准与不可变条件
- 资金不负、未知不报0；所有关键真实I/O失联都不能授权第二次Provider发送。
- API字段与迁移真实列以[API设计](../06-api-design.md)、[DB目录](../05-database-design.md)为准；有冲突须提交总控修正。
- 相应Phase真实负例/故障测试详见[测试策略](../09-test-plan.md)；当前NOT RUN。
