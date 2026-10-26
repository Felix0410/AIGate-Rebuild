package com.felix.aigatecore.identity;

import java.time.Instant;

/**
 * 初始化控制单例的内部持久化状态，仅用于管理写事务的行锁协调和初始化判断。
 *
 * <p>
 * 固定主键行在数据库迁移时创建；initializedAt 为 null 表示尚未完成初始化， 不代表单例行不存在。查询必须在当前写事务中执行，行锁由事务结束时释放。
 * </p>
 *
 * @param id 初始化控制单例主键，固定为 1
 * @param initializedAt 数据库记录的 UTC 初始化完成时间；未初始化时为 null
 * @author AIGate
 */
public record BootstrapControlDO(long id, Instant initializedAt) {
}
