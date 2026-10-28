package com.felix.aigatecore.governance;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** P6固定SQL存储；没有任意SQL或动态表名。 */
@Mapper
public interface GovernanceMapper {
    /**
     * policy：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Policy policy(Map<String, Object> values);

    /**
     * lockPolicy：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Policy lockPolicy(Map<String, Object> values);

    /**
     * sharePolicy：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Policy sharePolicy(Map<String, Object> values);

    /**
     * shareIdentity：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    Long shareIdentity(Map<String, Object> values);

    /**
     * insertPolicy：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertPolicy(Map<String, Object> values);

    /**
     * revision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Revision revision(Map<String, Object> values);

    /**
     * lockRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Revision lockRevision(Map<String, Object> values);

    /**
     * nextRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    Integer nextRevision(Map<String, Object> values);

    /**
     * insertRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertRevision(Map<String, Object> values);

    /**
     * insertRule：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertRule(Map<String, Object> values);

    /**
     * rules：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GovernanceRows.Rule> rules(Map<String, Object> values);

    /**
     * submitRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int submitRevision(Map<String, Object> values);

    /**
     * reviewRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int reviewRevision(Map<String, Object> values);

    /**
     * supersedeRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int supersedeRevision(Map<String, Object> values);

    /**
     * publishRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int publishRevision(Map<String, Object> values);

    /**
     * activatePolicy：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int activatePolicy(Map<String, Object> values);

    /**
     * nextApprovalRevision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    Integer nextApprovalRevision(Map<String, Object> values);

    /**
     * 停用同一Agent/Provider/region的旧批准，避免较低新批准回退至旧高等级。
     *
     * @param values 作用域和时刻
     * @return 影响行数
     */
    int retireApprovals(Map<String, Object> values);

    /**
     * insertApproval：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertApproval(Map<String, Object> values);

    /**
     * approval：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Approval approval(Map<String, Object> values);

    /**
     * liveApprovals：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GovernanceRows.Approval> liveApprovals(Map<String, Object> values);

    /**
     * listApprovals：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GovernanceRows.Approval> listApprovals(Map<String, Object> values);

    /**
     * countApprovals：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    Long countApprovals(Map<String, Object> values);

    /**
     * boundary：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Boundary boundary(Map<String, Object> values);

    /**
     * lockBoundary：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Boundary lockBoundary(Map<String, Object> values);

    /**
     * insertBoundary：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertBoundary(Map<String, Object> values);

    /**
     * updateBoundary：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int updateBoundary(Map<String, Object> values);

    /**
     * exception：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.ExceptionRequest exception(Map<String, Object> values);

    /**
     * lockException：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.ExceptionRequest lockException(Map<String, Object> values);

    /**
     * exceptionReceipt：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.ExceptionRequest exceptionReceipt(Map<String, Object> values);

    /**
     * insertException：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertException(Map<String, Object> values);

    /**
     * reviewException：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int reviewException(Map<String, Object> values);

    /**
     * revokeException：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int revokeException(Map<String, Object> values);

    /**
     * liveExceptions：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GovernanceRows.ExceptionRequest> liveExceptions(Map<String, Object> values);

    /**
     * insertDecision：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertDecision(Map<String, Object> values);

    /**
     * inputReview：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    GovernanceRows.Decision inputReview(Map<String, Object> values);

    /**
     * listDecisions：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GovernanceRows.Decision> listDecisions(Map<String, Object> values);

    /**
     * countDecisions：固定SQL，遵守Core治理锁序。
     *
     * @param values 已校验的绑定参数
     * @return 数据库查询或写入结果
     */
    Long countDecisions(Map<String, Object> values);
}
