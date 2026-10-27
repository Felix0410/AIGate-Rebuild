package com.felix.aigatecore.finance;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/** TeamPool、Grant与永久发行收据；不提供动态表名或任意SQL。 */
@Mapper
public interface GrantMapper {
    /**
     * pool：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    GrantRows.Pool pool(Map<String, Object> values);

    /**
     * lockPool：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    GrantRows.Pool lockPool(Map<String, Object> values);

    /**
     * overlap：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    Long overlap(Map<String, Object> values);

    /**
     * currentPool：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GrantRows.Pool> currentPool(Map<String, Object> values);

    /**
     * insertPool：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertPool(Map<String, Object> values);

    /**
     * updatePool：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int updatePool(Map<String, Object> values);

    /**
     * issuePool：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int issuePool(Map<String, Object> values);

    /**
     * receipt：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    GrantRows.Receipt receipt(Map<String, Object> values);

    /**
     * insertGrant：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertGrant(Map<String, Object> values);

    /**
     * grant：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    GrantRows.Grant grant(Map<String, Object> values);

    /**
     * lockGrant：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    GrantRows.Grant lockGrant(Map<String, Object> values);

    /**
     * insertReceipt：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertReceipt(Map<String, Object> values);

    /**
     * revokeGrant：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int revokeGrant(Map<String, Object> values);

    /**
     * eligibleGrants：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GrantRows.Grant> eligibleGrants(Map<String, Object> values);

    /**
     * changeGrant：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int changeGrant(Map<String, Object> values);

    /**
     * listGrants：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    List<GrantRows.Grant> listGrants(Map<String, Object> values);

    /**
     * countGrants：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    Long countGrants(Map<String, Object> values);

    /**
     * insertIssueLedger：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertIssueLedger(Map<String, Object> values);

    /**
     * insertIssueOutbox：固定SQL，与Core账务事务共同使用。
     *
     * @param values 已验证的绑定参数
     * @return 数据库查询或写入结果
     */
    int insertIssueOutbox(Map<String, Object> values);
}
