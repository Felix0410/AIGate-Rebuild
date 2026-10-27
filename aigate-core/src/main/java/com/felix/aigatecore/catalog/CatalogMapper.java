package com.felix.aigatecore.catalog;

import com.aigate.contracts.dto.AgentModelPermissionDTO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

/**
 * Catalog七表的数据访问，只使用预绑定值和固定SQL，不提供任意动态表名。
 *
 * @author AIGate
 */
@Mapper
public interface CatalogMapper {

    /**
     * 读取provider内部记录。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    ProviderDO selectProvider(long id);

    /**
     * 锁定provider行，锁持续到当前业务事务结束。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    ProviderDO lockProvider(long id);

    /**
     * 读取logical_model内部记录。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    LogicalModelDO selectModel(long id);

    /**
     * 锁定logical_model行，锁持续到当前业务事务结束。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    LogicalModelDO lockModel(long id);

    /**
     * 读取model_deployment内部记录。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    DeploymentDO selectDeployment(long id);

    /**
     * 锁定model_deployment行，锁持续到当前业务事务结束。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    DeploymentDO lockDeployment(long id);

    /**
     * 读取provider_credential内部记录。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    ProviderCredentialDO selectCredential(long id);

    /**
     * 锁定provider_credential行，锁持续到当前业务事务结束。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    ProviderCredentialDO lockCredential(long id);

    /**
     * 按不可变模型编码读取身份。
     *
     * @param code 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    LogicalModelDO selectModelByCode(String code);

    /**
     * 创建供应商并回填主键。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int insertProvider(Map<String, Object> values);

    /**
     * 按期望版本更新供应商可变字段。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int updateProvider(Map<String, Object> values);

    /**
     * 创建不可变模型身份并回填主键。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int insertModel(Map<String, Object> values);

    /**
     * 只更新名称和状态，不允许更换真实模型身份。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int updateModel(Map<String, Object> values);

    /**
     * 创建DRAFT部署并回填自增主键。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int insertDeployment(Map<String, Object> values);

    /**
     * 同一创建事务内回填适配JSON的服务端部署ID，初始版本仍为0。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int bindDeploymentIdentity(Map<String, Object> values);

    /**
     * 按期望版本更新状态和路由元数据。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int updateDeployment(Map<String, Object> values);

    /**
     * 按主键升序分页查询provider。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    List<ProviderDO> listProviders(Map<String, Object> query);

    /**
     * 使用与列表相同条件统计provider。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    long countProviders(Map<String, Object> query);

    /**
     * 按主键升序分页查询logical_model。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    List<LogicalModelDO> listModels(Map<String, Object> query);

    /**
     * 使用与列表相同条件统计logical_model。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    long countModels(Map<String, Object> query);

    /**
     * 按主键升序分页查询model_deployment。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    List<DeploymentDO> listDeployments(Map<String, Object> query);

    /**
     * 使用与列表相同条件统计model_deployment。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    long countDeployments(Map<String, Object> query);

    /**
     * 按ID顺序锁定策略变更涉及的ACTIVE部署。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    List<DeploymentDO> lockActiveProviderDeployments(long id);

    /**
     * 读取当前ACTIVE模型部署，仅用于后续逐项就绪检查。
     *
     * @param id 目标主键或编码
     * @return 当前查询结果；单项不存在时为空
     */
    List<DeploymentDO> activeModelDeployments(long id);

    /**
     * 按所属对象及修订读取价格。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    CatalogPriceDO selectPrice(Map<String, Object> query);

    /**
     * 按当前指针读取价格并供服务层复核归属。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    CatalogPriceDO selectPriceById(Map<String, Object> query);

    /**
     * 按revision升序分页查询不可变价格历史。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    List<CatalogPriceDO> listPrices(Map<String, Object> query);

    /**
     * 统计所属对象价格历史。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    long countPrices(Map<String, Object> query);

    /**
     * 创建价格草稿，不接受客户端生效时间。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int insertPrice(Map<String, Object> values);

    /**
     * 仅DRAFT可发布，历史PUBLISHED金额始终不修改。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int publishPrice(Map<String, Object> values);

    /**
     * 积分价格发布时以父版本CAS切换指针。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int pointPricePointer(Map<String, Object> values);

    /**
     * 采购价格发布时以部署版本CAS切换指针。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int purchasePricePointer(Map<String, Object> values);

    /**
     * 按签发者和幂等键锁定永久凭证收据。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    ProviderCredentialDO selectReceipt(Map<String, Object> query);

    /**
     * 仅保存AES密文、IV、版本及HMAC收据，回填主键。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int insertCredential(Map<String, Object> values);

    /**
     * 只选择新当前凭证，不自动激活部署。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int credentialPointer(Map<String, Object> values);

    /**
     * 首次吊销才改变状态，保留签发与收据历史。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int revokeCredential(Map<String, Object> values);

    /**
     * 吊销当前凭证时清空指针并禁用部署，不能回退旧凭证。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int clearCredentialPointer(Map<String, Object> values);

    /**
     * 分页查询内部凭证记录，服务层仅投影安全元数据。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    List<ProviderCredentialDO> listCredentials(Map<String, Object> query);

    /**
     * 统计指定部署的凭证历史。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    long countCredentials(Map<String, Object> query);

    /**
     * 读取唯一Agent-Model关系。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    AgentModelPermissionDTO selectPermission(Map<String, Object> query);

    /**
     * 创建或恢复原授权行，恢复时递增版本。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int grantPermission(Map<String, Object> values);

    /**
     * 只撤销当前GRANTED关系，重复撤销不写审计。
     *
     * @param values 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    int revokePermission(Map<String, Object> values);

    /**
     * 按关系主键稳定分页查询授权历史。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 当前查询结果；单项不存在时为空
     */
    List<AgentModelPermissionDTO> listPermissions(Map<String, Object> query);

    /**
     * 按列表过滤条件统计授权关系。
     *
     * @param query 服务层校验后构造的查询或写入参数
     * @return 匹配记录数或影响行数
     */
    long countPermissions(Map<String, Object> query);
}
