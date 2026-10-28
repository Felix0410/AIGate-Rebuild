package com.felix.aigatecore.governance;

import com.aigate.contracts.RequestIds;
import java.util.List;
import org.springframework.stereotype.Service;

/** P6 Core内部治理候选判定；没有HTTP映射，不颁发P7派发许可或修改账务。 */
@Service
public class GovernanceEvaluator {
    private final GovernanceDecisionTransactions transactions;
    private final GovernanceScanner scanner;
    private final GovernanceSettings settings;

    /**
     * 编排短快照、无事务CPU扫描和最终短锁复核。
     *
     * @param transactions 治理快照与最终提交
     * @param scanner 无DB扫描器
     * @param settings 独立审计摘要
     */
    public GovernanceEvaluator(GovernanceDecisionTransactions transactions, GovernanceScanner scanner,
        GovernanceSettings settings) {
        this.transactions = transactions;
        this.scanner = scanner;
        this.settings = settings;
    }

    /**
     * 只能由Core可信调用链构造；verifiedSourceClass绝不能从外部JSON/Headers复制。
     *
     * @param employee 经过Core验证的员工
     * @param agent 经过Core验证的Agent
     * @param model 逻辑模型
     * @param deployment 实际部署
     * @param verifiedSourceClass Core可信来源标签，null默认INTERNAL；PUBLIC不能自行降低下界
     * @param traceId 安全诊断ID
     * @param phase PREQUEUE/PREDISPATCH/OUTPUT_REVIEW
     * @param ordinal 同一阶段的有界正整数序号
     */
    public record Context(long employee, long agent, long model, long deployment, String verifiedSourceClass,
        String traceId, String phase, int ordinal) {
    }

    /**
     * 治理层结果，不是完整出站许可；仍需P3目录及P7身份/资金/唯一Permit条件。
     *
     * @param decision 安全决定
     * @param reasonCode 安全原因码
     * @param dataClass 有效分类
     * @param policyRevisionId 实际审计策略，无发布策略时为空
     * @param ruleCodes 只含内置RuleCode
     * @param outputReviewRequired 是否必须缓冲审核输出
     */
    public record Verdict(String decision, String reasonCode, String dataClass, Long policyRevisionId,
        List<String> ruleCodes, boolean outputReviewRequired) {
    }

    /** 不携带原始Prompt的Core内部准备结果；构造器仅供本治理组件使用。 */
    public static final class Prepared {
        private final Context context;
        private final GovernanceDecisionTransactions.Snapshot snapshot;
        private final GovernanceScanner.Scan scan;
        private final byte[] digest;

        private Prepared(Context context, GovernanceDecisionTransactions.Snapshot snapshot,
            GovernanceScanner.Scan scan, byte[] digest) {
            this.context = context;
            this.snapshot = snapshot;
            this.scan = scan;
            this.digest = digest;
        }

        Context context() {
            return context;
        }
        GovernanceDecisionTransactions.Snapshot snapshot() {
            return snapshot;
        }
        GovernanceScanner.Scan scan() {
            return scan;
        }
        byte[] digest() {
            return digest;
        }
    }

    /**
     * 在资金事务之外捕获并扫描；准备结果不得直接当作派发授权。
     *
     * @param context Core可信主体
     * @param canonicalMessages 受控消息JSON
     * @return 无原始文本的准备结果
     */
    public Prepared prepare(Context context, byte[] canonicalMessages) {
        if (!List.of("PREQUEUE", "PREDISPATCH").contains(context.phase())) {
            throw com.felix.aigatecore.web.ApiException.invalid();
        }
        var snapshot = transactions.snapshot();
        var scan = snapshot.compiled() == null
            ? new GovernanceScanner.Scan(RuleRegistry.DataClass.INTERNAL, false, List.of(), List.of(), false,
                snapshot.failure())
            : scanner.input(snapshot.compiled(), canonicalMessages, context.verifiedSourceClass());
        return new Prepared(safe(context), snapshot, scan, settings.digest(canonicalMessages == null
            ? new byte[0]
            : canonicalMessages));
    }

    private Context safe(Context context) {
        if (context.employee() <= 0 || context.agent() <= 0 || context.model() <= 0 || context.deployment() <= 0
            || context.ordinal() < 1 || context.ordinal() > 1_000_000) {
            throw com.felix.aigatecore.web.ApiException.invalid();
        }
        return new Context(context.employee(), context.agent(), context.model(), context.deployment(),
            context.verifiedSourceClass(), RequestIds.safe(context.traceId()), context.phase(), context.ordinal());
    }

    /**
     * 每个阶段重新捕获当前已发布策略；Scanner在事务外执行。
     *
     * @param context Core可信主体
     * @param canonicalMessages 封闭messages/stream JSON，不持久化正文
     * @return 经过最终当前指针/授权复核的治理结果
     */
    public Verdict evaluate(Context context, byte[] canonicalMessages) {
        return finalizePrepared(prepare(context, canonicalMessages));
    }

    /**
     * 短事务最终复核；P7未来可在其同库资金事务中调用，不在资金锁内再次扫描。
     *
     * @param prepared 由本组件创建的准备结果
     * @return 最终治理结果
     */
    public Verdict finalizePrepared(Prepared prepared) {
        return transactions.complete(prepared);
    }

    /**
     * 仅对已有PREDISPATCH缓冲审核合同的输出检查；拒绝也不释放或伪造费用。
     *
     * @param context 原可信主体和同一traceId
     * @param output 实际缓冲文本字节
     * @return 是否允许回送输出，不包含输出正文
     */
    public Verdict reviewOutput(Context context, byte[] output) {
        Context safe = safe(new Context(context.employee(), context.agent(), context.model(), context.deployment(),
            context.verifiedSourceClass(), context.traceId(), "OUTPUT_REVIEW", context.ordinal()));
        var snapshot = transactions.snapshot();
        String failure = snapshot.compiled() == null ? snapshot.failure() : scanner.output(snapshot.compiled(), output);
        return transactions.completeOutput(safe, snapshot, failure);
    }
}
