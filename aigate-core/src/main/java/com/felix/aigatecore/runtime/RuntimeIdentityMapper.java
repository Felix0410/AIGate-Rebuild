package com.felix.aigatecore.runtime;

import com.aigate.contracts.dto.AgentDTO;
import com.aigate.contracts.dto.EmployeeDTO;
import com.aigate.contracts.dto.KeyMetadataDTO;
import com.aigate.contracts.dto.AgentEmployeePermissionDTO;
import java.util.Map;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent、运行凭证和员工授权的数据访问接口。
 *
 * @author AIGate
 */
@Mapper
public interface RuntimeIdentityMapper {

    /**
     * 按主键读取 Agent。
     *
     * @param id 目标主键
     * @return 查询结果；单项查询不存在时为 null
     */
    AgentDTO selectAgent(long id);

    /**
     * 锁定 Agent 所属行，串行化其凭证变更。
     *
     * @param id 目标主键
     * @return 查询结果；单项查询不存在时为 null
     */
    AgentDTO lockAgent(long id);

    /**
     * 锁定员工行，串行化其运行凭证变更。
     *
     * @param id 目标主键
     * @return 查询结果；单项查询不存在时为 null
     */
    EmployeeDTO lockEmployee(long id);

    /**
     * 新增 Agent 并回填主键。
     *
     * @param values 服务层构造的查询或写入参数
     * @return 影响行数
     */
    int insertAgent(Map<String, Object> values);

    /**
     * 按期望版本更新 Agent。
     *
     * @param values 服务层构造的查询或写入参数
     * @return 影响行数
     */
    int updateAgent(Map<String, Object> values);

    /**
     * 按条件稳定分页查询 Agent。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 按主键升序排列的当前页记录
     */
    List<AgentDTO> listAgents(Map<String, Object> query);

    /**
     * 按列表条件统计 Agent 总数。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 匹配条件的记录数
     */
    long countAgents(Map<String, Object> query);

    /**
     * 读取签发者与幂等键对应的签发记录。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 查询结果；单项查询不存在时为 null
     */
    RuntimeKeyDO selectReceipt(Map<String, Object> query);

    /**
     * 在已锁定 Owner 后锁定旧凭证。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 查询结果；单项查询不存在时为 null
     */
    RuntimeKeyDO lockKey(Map<String, Object> query);

    /**
     * 按独立 Pepper 版本和摘要查询运行凭证。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 查询结果；单项查询不存在时为 null
     */
    RuntimeKeyDO selectByDigest(Map<String, Object> query);

    /**
     * 统计指定时刻当前可认证的运行凭证。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 匹配条件的记录数
     */
    int countEffectiveKeys(Map<String, Object> query);

    /**
     * 保存摘要和签发信息，不保存密钥明文。
     *
     * @param values 服务层构造的查询或写入参数
     * @return 影响行数
     */
    int insertKey(Map<String, Object> values);

    /**
     * 更新旧凭证宽限期，不延长已有窗口。
     *
     * @param values 服务层构造的查询或写入参数
     * @return 影响行数
     */
    int supersedeKey(Map<String, Object> values);

    /**
     * 仅吊销尚未吊销的运行凭证。
     *
     * @param values 服务层构造的查询或写入参数
     * @return 影响行数
     */
    int revokeKey(Map<String, Object> values);

    /**
     * 查询不含摘要和密钥的凭证分页。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 按主键升序排列的当前页记录
     */
    List<KeyMetadataDTO> listKeys(Map<String, Object> query);

    /**
     * 统计 Owner 的全部历史凭证数量。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 匹配条件的记录数
     */
    long countKeys(Map<String, Object> query);

    /**
     * 读取员工与 Agent 的唯一授权关系。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 查询结果；单项查询不存在时为 null
     */
    AgentEmployeePermissionDTO selectPermission(Map<String, Object> query);

    /**
     * 新增授权或恢复已有关系。
     *
     * @param values 服务层构造的查询或写入参数
     * @return 影响行数
     */
    int grantPermission(Map<String, Object> values);

    /**
     * 撤销有效授权，递增版本。
     *
     * @param values 服务层构造的查询或写入参数
     * @return 影响行数
     */
    int revokePermission(Map<String, Object> values);

    /**
     * 分页查询 Agent 的员工授权。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 按主键升序排列的当前页记录
     */
    List<AgentEmployeePermissionDTO> listPermissions(Map<String, Object> query);

    /**
     * 按列表条件统计授权总数。
     *
     * @param query 服务层构造的查询或写入参数
     * @return 匹配条件的记录数
     */
    long countPermissions(Map<String, Object> query);
}
