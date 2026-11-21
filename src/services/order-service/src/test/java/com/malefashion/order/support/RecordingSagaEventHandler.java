package com.malefashion.order.support;

import com.malefashion.order.application.SagaEventHandler;
import com.malefashion.order.infrastructure.messaging.InboundEvent;
import com.malefashion.order.infrastructure.messaging.SagaEventPayload;
import org.slf4j.MDC;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handler ghi nhận số lần gọi + MDC để test listener/inbox mà không cần nghiệp vụ 2b.
 */
public class RecordingSagaEventHandler implements SagaEventHandler {

    private final AtomicInteger attempts = new AtomicInteger();
    private final List<Handled> handled = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;
    private volatile Runnable beforeHandle;

    @Override
    public void handle(InboundEvent event) {
        attempts.incrementAndGet();
        Runnable assertion = beforeHandle;
        if (assertion != null) {
            assertion.run();
        }
        RuntimeException error = failure;
        if (error != null) {
            throw error;
        }
        handled.add(new Handled(
                event.eventId(),
                event.orderId(),
                event.eventType(),
                event.payload(),
                MDC.get("traceId"),
                MDC.get("orderId"),
                MDC.get("eventId")));
    }

    public void reset() {
        attempts.set(0);
        handled.clear();
        failure = null;
        beforeHandle = null;
    }

    public void failWith(RuntimeException error) {
        this.failure = error;
    }

    public void beforeHandle(Runnable action) {
        this.beforeHandle = action;
    }

    public int attempts() {
        return attempts.get();
    }

    public List<Handled> handled() {
        return List.copyOf(handled);
    }

    public Optional<Handled> lastHandled() {
        return handled.isEmpty() ? Optional.empty() : Optional.of(handled.getLast());
    }

    public record Handled(
            UUID eventId,
            UUID orderId,
            String eventType,
            SagaEventPayload payload,
            String mdcTraceId,
            String mdcOrderId,
            String mdcEventId
    ) {
    }
}
