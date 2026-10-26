package com.felix.aigatecore.runtime;

import java.time.Instant;

/**
 * 两类运行凭证共用的内部查询结果，不包含摘要或完整密钥。
 *
 * @param id 凭证主键
 * @param ownerId Agent 或员工主键
 * @param expiresAt 自然到期时间
 * @param validFrom 开始生效时间
 * @param supersededAt 首次被替代时间
 * @param graceExpiresAt 宽限期截止时间
 * @param revokedAt 吊销时间
 * @param parentId 轮换来源凭证主键，普通签发时为 null
 * @author AIGate
 */
public record RuntimeKeyDO(long id, long ownerId, Instant expiresAt, Instant validFrom, Instant supersededAt,
    Instant graceExpiresAt, Instant revokedAt, Long parentId) {

    /**
     * 判断指定时刻是否可认证，到期边界不再接受旧密钥。
     *
     * @param now UTC 检查时刻
     * @return 未吊销且处于生效及宽限窗口内时为 true
     */
    public boolean authenticatableAt(Instant now) {
        return revokedAt == null && !now.isBefore(validFrom) && now.isBefore(expiresAt)
            && (graceExpiresAt == null || now.isBefore(graceExpiresAt));
    }
}
