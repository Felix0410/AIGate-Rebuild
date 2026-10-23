# 数据库与迁移设计

## 1. 数据库划分与权威

- **core_db（Core独占）：55张表**，按Core V1～V11逐Phase部署；核心真实资金/身份/Permit/安全策略和Outbox都在同一MySQL实例的事务边界内。
- **finops_db（FinOps独占）：7张表**，仅P11引入，不能跨库外键或让报表服务写Core账务。
- 完整项目共62张表，不是P1建62表；P1仅Core V1六表。
- MySQL 8.4、Flyway、独立迁移账号和应用DML最小权限。两个数据库有各自schema history，不因都存在V1而冲突。

## 2. 版本和文件

| 版本 | 首次Phase | 目标表数 | 设计SQL |
|---|---|---:|---|
| Core V1 | P1 | 6 | [V01](./database/core-v01.md) |
| Core V2 | P2 | 4 | [V02](./database/core-v02.md) |
| Core V3 | P3 | 7 | [V03](./database/core-v03.md) |
| Core V4 | P4 | 10 | [V04](./database/core-v04.md) |
| Core V5 | P5 | 3 | [V05](./database/core-v05.md) |
| Core V6 | P6 | 7 | [V06](./database/core-v06.md) |
| Core V7 | P7 | 5 | [V07](./database/core-v07.md) |
| Core V8 | P8 | 2 | [V08](./database/core-v08.md) |
| Core V9 | P9 | 5 | [V09](./database/core-v09.md) |
| Core V10 | P10 | 5 | [V10](./database/core-v10.md) |
| Core V11 | P12 | 1 | [V11](./database/core-v11.md) |
| FinOps V1 | P11 | 7 | [FinOps V01](./database/finops-v01.md) |

对应规范 SQL 由本地AI在开发时写入Flyway迁移文件；本目录提供设计DDL基准，不是已应用的迁移文件。V8/V9/V10/V11包含针对旧表的ALTER，不允许重新编辑已运行版本。

## 3. 数据类型/约束纪律

| 类型 | 要求 |
|---|---|
| ID与FK | BIGINT UNSIGNED；API Long安全边界，外键类型一致 |
| POINTS | DECIMAL(20,6)，整数和预算只按受控BigDecimal换算 |
| 采购货币 | DECIMAL(20,8)，currency按原ISO4217，跨币种独立账户 |
| 核心审计 | 操作Actor、原因码、时间、真实对象/版本，绝不保存Key/Prompt |
| 幂等 | 唯一物理索引或明确expectedVersion CAS（需说明重放返回409）；不能靠进程内Map |
| 外键 | 真实同库FK；Core/FinOps之间禁止跨库FK |
| 时间 | 数据库存UTC DATETIME(6)，业务周期按公司ZoneId计算 |
| 更正 | Append-only Ledger/Outbox/Fact revision，不能覆盖历史已发布事实 |

## 4. 迁移核验要求

逐版本空库迁移与已应用版本升级均要检查CHECK/FK/INDEX、审核列是否真实可落库、失败事务/非事务DDL边界、Flyway checksum、应用账号没有DDL权限。当前仅静态审查了62个不同建表名、FK/列类型/索引名，**没有真MySQL8.4/Flyway运行证据**。