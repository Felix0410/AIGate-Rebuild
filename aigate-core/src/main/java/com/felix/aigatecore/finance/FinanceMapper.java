package com.felix.aigatecore.finance;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** P4固定SQL存储，不提供任意表名或SQL入口。 */
@Mapper
public interface FinanceMapper {
    /**
     * shareIdentityControl：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    Long shareIdentityControl(Map<String, Object> values);

    /**
     * lockEmployee：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    Long lockEmployee(Map<String, Object> values);

    /**
     * findPolicy：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Policy findPolicy(Map<String, Object> values);

    /**
     * lockPolicy：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Policy lockPolicy(Map<String, Object> values);

    /**
     * nextRevision：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    Integer nextRevision(Map<String, Object> values);

    /**
     * insertPolicy：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertPolicy(Map<String, Object> values);

    /**
     * retirePolicies：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int retirePolicies(Map<String, Object> values);

    /**
     * publishPolicy：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int publishPolicy(Map<String, Object> values);

    /**
     * activePolicy：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Policy activePolicy(Map<String, Object> values);

    /**
     * latestActivePolicy：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Policy latestActivePolicy(Map<String, Object> values);

    /**
     * listPolicies：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<FinanceRows.Policy> listPolicies(Map<String, Object> values);

    /**
     * countPolicies：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    Long countPolicies(Map<String, Object> values);

    /**
     * budget：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Budget budget(Map<String, Object> values);

    /**
     * lockBudget：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Budget lockBudget(Map<String, Object> values);

    /**
     * lockBudgetId：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Budget lockBudgetId(Map<String, Object> values);

    /**
     * insertBudget：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertBudget(Map<String, Object> values);

    /**
     * updateBudgetLimit：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int updateBudgetLimit(Map<String, Object> values);

    /**
     * partitions：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<FinanceRows.Partition> partitions(Map<String, Object> values);

    /**
     * lockPartitions：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<FinanceRows.Partition> lockPartitions(Map<String, Object> values);

    /**
     * insertPartition：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertPartition(Map<String, Object> values);

    /**
     * updatePartitionLimit：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int updatePartitionLimit(Map<String, Object> values);

    /**
     * listBudgets：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<FinanceRows.Budget> listBudgets(Map<String, Object> values);

    /**
     * countBudgets：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    Long countBudgets(Map<String, Object> values);

    /**
     * bucket：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Bucket bucket(Map<String, Object> values);

    /**
     * insertBucket：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertBucket(Map<String, Object> values);

    /**
     * quotaAccounts：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<FinanceRows.Bucket> quotaAccounts(Map<String, Object> values);

    /**
     * lockBucketId：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Bucket lockBucketId(Map<String, Object> values);

    /**
     * changeBucket：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int changeBucket(Map<String, Object> values);

    /**
     * changeBudget：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int changeBudget(Map<String, Object> values);

    /**
     * changePartition：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int changePartition(Map<String, Object> values);

    /**
     * operation：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Operation operation(Map<String, Object> values);

    /**
     * lockOperation：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Operation lockOperation(Map<String, Object> values);

    /**
     * insertOperation：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertOperation(Map<String, Object> values);

    /**
     * finishOperation：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int finishOperation(Map<String, Object> values);

    /**
     * points：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Points points(Map<String, Object> values);

    /**
     * spend：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    FinanceRows.Spend spend(Map<String, Object> values);

    /**
     * allocations：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<FinanceRows.Allocation> allocations(Map<String, Object> values);

    /**
     * insertPoints：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertPoints(Map<String, Object> values);

    /**
     * insertAllocation：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertAllocation(Map<String, Object> values);

    /**
     * insertSpend：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertSpend(Map<String, Object> values);

    /**
     * finishPoints：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int finishPoints(Map<String, Object> values);

    /**
     * finishAllocation：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int finishAllocation(Map<String, Object> values);

    /**
     * finishSpend：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int finishSpend(Map<String, Object> values);

    /**
     * insertLedger：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertLedger(Map<String, Object> values);

    /**
     * insertOutbox：固定SQL，调用方遵守账户锁序。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertOutbox(Map<String, Object> values);
}
