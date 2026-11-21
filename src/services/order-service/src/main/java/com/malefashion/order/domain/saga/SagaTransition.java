package com.malefashion.order.domain.saga;

import java.util.Arrays;
import java.util.List;

/**
 * Kết quả có kiểu của một lần xử lý event: outcome + danh sách hành động cần làm.
 */
public record SagaTransition(SagaOutcome outcome, List<SagaAction> actions) {

    public SagaTransition {
        actions = List.copyOf(actions);
    }

    public static SagaTransition applied(SagaAction... actions) {
        return new SagaTransition(SagaOutcome.APPLIED, Arrays.asList(actions));
    }

    public static SagaTransition applied(List<SagaAction> actions) {
        return new SagaTransition(SagaOutcome.APPLIED, actions);
    }

    public static SagaTransition ignoredDuplicate() {
        return new SagaTransition(SagaOutcome.IGNORED_DUPLICATE, List.of());
    }

    public static SagaTransition notApplicable() {
        return new SagaTransition(SagaOutcome.IGNORED_NOT_APPLICABLE, List.of());
    }

    public boolean isApplied() {
        return outcome == SagaOutcome.APPLIED;
    }
}
