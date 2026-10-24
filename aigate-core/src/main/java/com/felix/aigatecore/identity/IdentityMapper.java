package com.felix.aigatecore.identity;

import com.aigate.contracts.dto.EmployeeDTO;
import com.aigate.contracts.dto.ManagementCredentialMetadataDTO;
import com.aigate.contracts.dto.RoleAssignmentDTO;
import com.aigate.contracts.dto.TeamDTO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 身份模块的数据访问接口。SQL 必须使用参数绑定；列表和 COUNT 必须应用相同的权限过滤。
 *
 * @author AIGate
 */
@Mapper
public interface IdentityMapper {

    /**
     * 锁定固定初始化单例行；调用方必须已进入管理写事务。
     *
     * @return 按固定字段构造的内部数据参数或查询结果
     */
    Map<String, Object> lockBootstrapControl();

    /**
     * 仅在尚未初始化时写入首次管理员与完成时间。
     *
     * @param employeeId 目标员工主键
     * @return 实际受影响的数据库行数
     */
    int initializeBootstrapControl(@Param("employeeId") long employeeId);

    /**
     * 按主键读取团队的公开字段。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 匹配的持久化记录；不存在时为 null
     */
    TeamDTO selectTeamById(long id);

    /**
     * 按主键读取员工的公开字段。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 匹配的持久化记录；不存在时为 null
     */
    EmployeeDTO selectEmployeeById(long id);

    /**
     * 按不可变员工编码读取员工。
     *
     * @param code 稳定错误码或员工编码
     * @return 匹配的持久化记录；不存在时为 null
     */
    EmployeeDTO selectEmployeeByCode(String code);

    /**
     * 读取未撤销的角色关系，按数据库主键稳定排序。
     *
     * @param employeeId 目标员工主键
     * @return 当前查询范围内的有序结果
     */
    List<RoleAssignmentDTO> listRoleAssignments(long employeeId);

    /**
     * 读取当前 Team Manager 被授予且仍 ACTIVE 的团队。
     *
     * @param employeeId 目标员工主键
     * @return 当前查询范围内的有序结果
     */
    List<Long> listVisibleTeamIds(long employeeId);

    /**
     * 统计团队下仍处于 ACTIVE 状态的员工。
     *
     * @param teamId 目标团队主键
     * @return 满足当前过滤条件的记录数
     */
    int countActiveEmployeesByTeamId(long teamId);

    /**
     * 统计员工、归属团队及全局角色均有效的管理员。
     *
     * @return 满足当前过滤条件的记录数
     */
    int countActiveGlobalAdministrators();

    /**
     * 插入团队并回填生成主键；初始状态和版本由服务端固定。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int insertTeam(Map<String, Object> values);

    /**
     * 插入员工并回填生成主键。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int insertEmployee(Map<String, Object> values);

    /**
     * 使用 expectedVersion 更新团队；仍有 ACTIVE 员工时禁止禁用。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int updateTeam(Map<String, Object> values);

    /**
     * 按期望版本更新员工资料及状态，禁用前保护最后有效管理员。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int updateEmployee(Map<String, Object> values);

    /**
     * 按当前权限范围查询团队及其过滤后的总数。
     *
     * @param query 已应用白名单和权限范围的查询参数
     * @return 当前查询范围内的有序结果
     */
    List<TeamDTO> listTeams(Map<String, Object> query);

    /**
     * 使用与团队列表完全相同的权限过滤统计总数。
     *
     * @param query 已应用白名单和权限范围的查询参数
     * @return 满足当前过滤条件的记录数
     */
    long countTeams(Map<String, Object> query);

    /**
     * 按当前权限范围查询员工及其过滤后的总数。
     *
     * @param query 已应用白名单和权限范围的查询参数
     * @return 当前查询范围内的有序结果
     */
    List<EmployeeDTO> listEmployees(Map<String, Object> query);

    /**
     * 使用与员工列表完全相同的权限过滤统计总数。
     *
     * @param query 已应用白名单和权限范围的查询参数
     * @return 满足当前过滤条件的记录数
     */
    long countEmployees(Map<String, Object> query);

    /**
     * 幂等授予角色；只有真实新增或恢复关系时才追加审计。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int grantRole(Map<String, Object> values);

    /**
     * 幂等撤销角色；禁止撤销最后有效全局管理员。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int revokeRole(Map<String, Object> values);

    /**
     * 统计指定员工、角色和作用域的有效授权关系。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 满足当前过滤条件的记录数
     */
    int countActiveRoleAssignments(Map<String, Object> values);

    /**
     * 按 HMAC 摘要和 Pepper 版本查询凭证，不接收明文密钥。
     *
     * @param digest 独立 Pepper 计算得到的 HMAC-SHA256 摘要
     * @param pepperKeyId 内部 HMAC 密钥版本标识，不包含密钥材料
     * @return 匹配的持久化记录；不存在时为 null
     */
    ManagementCredentialDO selectCredentialByDigest(@Param("digest") byte[] digest,
        @Param("pepperKeyId") String pepperKeyId);

    /**
     * 按签发者及幂等键读取已提交的签发事实。
     *
     * @param type 受信操作者类型或受控字段类型
     * @param actorId 签发者或审计操作者主键；BOOTSTRAP 固定为 0
     * @param key 字段名或当前操作的受控幂等标识；凭证明文只允许在首次签发响应使用
     * @return 匹配的持久化记录；不存在时为 null
     */
    ManagementCredentialDO selectIssuedCredential(@Param("actorType") String type, @Param("actorId") long actorId,
        @Param("key") String key);

    /**
     * 读取仅供内部归属校验使用的凭证元数据。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 匹配的持久化记录；不存在时为 null
     */
    ManagementCredentialDO selectCredentialById(long id);

    /**
     * 保存管理凭证摘要及幂等事实，禁止将明文密钥加入参数。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int insertCredential(Map<String, Object> values);

    /**
     * 核对凭证归属后吊销；重复吊销不重复追加成功审计。
     *
     * @param id 资源的数据库主键，必须符合当前接口的正整数约束
     * @return 实际受影响的数据库行数
     */
    int revokeCredential(long id);

    /**
     * 仅向管理员分页返回凭证元数据，不包含明文或摘要。
     *
     * @param query 已应用白名单和权限范围的查询参数
     * @return 当前查询范围内的有序结果
     */
    List<ManagementCredentialMetadataDTO> listCredentialMetadata(Map<String, Object> query);

    /**
     * 统计目标员工的凭证元数据记录数。
     *
     * @param employeeId 目标员工主键
     * @return 满足当前过滤条件的记录数
     */
    long countCredentialsByEmployeeId(long employeeId);

    /**
     * 追加当前事务的安全审计，变更 JSON 只能保存字段名数组。
     *
     * @param values 固定白名单字段组成的 SQL 写入参数，不得包含明文密钥
     * @return 实际受影响的数据库行数
     */
    int insertAuditEvent(Map<String, Object> values);

    /**
     * 按受限查询条件返回审计元数据，时间区间最多九十天。
     *
     * @param query 已应用白名单和权限范围的查询参数
     * @return 当前查询范围内的有序结果
     */
    List<Map<String, Object>> listAuditEvents(Map<String, Object> query);

    /**
     * 使用与审计列表相同的条件统计总数。
     *
     * @param query 已应用白名单和权限范围的查询参数
     * @return 满足当前过滤条件的记录数
     */
    long countAuditEvents(Map<String, Object> query);
}
