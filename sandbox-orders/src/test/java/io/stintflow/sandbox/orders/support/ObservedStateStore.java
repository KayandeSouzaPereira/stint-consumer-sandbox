package io.stintflow.sandbox.orders.support;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import io.stintflow.spi.InboxEntry;
import io.stintflow.spi.InstanceSnapshot;
import io.stintflow.spi.OutboxEntry;
import io.stintflow.spi.SaveOutcome;
import io.stintflow.spi.StateStore;
import io.stintflow.spi.Wait;

/**
 * A state store that reports what the engine committed, so a test waits on an observed save instead of sleeping:
 * <ul>
 *   <li>{@link #creates()}: instances created (a successful save at version 0);</li>
 *   <li>{@link #waiting()}: each snapshot saved while it waits (a listen, a task), <em>after</em> the save
 *       (firing its timer before would find no wait and do nothing).</li>
 * </ul>
 */
public final class ObservedStateStore implements StateStore {

    private final StateStore delegate;
    private final AtomicInteger creates = new AtomicInteger();
    private final BlockingQueue<InstanceSnapshot> waiting = new LinkedBlockingQueue<>();

    public ObservedStateStore(StateStore delegate) {
        this.delegate = delegate;
    }

    public int creates() {
        return creates.get();
    }

    public BlockingQueue<InstanceSnapshot> waiting() {
        return waiting;
    }

    @Override
    public CompletionStage<SaveOutcome> save(InstanceSnapshot snapshot, long expectedVersion, List<Wait> addWaits,
            List<String> consumeWaitKeys, List<OutboxEntry> addOutbox) {
        return save(snapshot, expectedVersion, addWaits, consumeWaitKeys, addOutbox, List.of());
    }

    @Override
    public CompletionStage<SaveOutcome> save(InstanceSnapshot snapshot, long expectedVersion, List<Wait> addWaits,
            List<String> consumeWaitKeys, List<OutboxEntry> addOutbox, List<InboxEntry.Key> removeInbox) {
        return delegate.save(snapshot, expectedVersion, addWaits, consumeWaitKeys, addOutbox, removeInbox)
                .thenApply(outcome -> {
                    if (outcome == SaveOutcome.OK) {
                        if (expectedVersion == 0) {
                            creates.incrementAndGet();
                        }
                        if (snapshot.status() == InstanceSnapshot.InstanceStatus.WAITING) {
                            waiting.add(snapshot);
                        }
                    }
                    return outcome;
                });
    }

    @Override
    public CompletionStage<Optional<InstanceSnapshot>> load(String instanceId) {
        return delegate.load(instanceId);
    }

    @Override
    public CompletionStage<Optional<Wait>> findWait(String waitKey) {
        return delegate.findWait(waitKey);
    }

    @Override
    public CompletionStage<Void> delete(String instanceId) {
        return delegate.delete(instanceId);
    }

    @Override
    public CompletionStage<List<OutboxEntry>> pendingOutbox(Instant createdAtOrBefore, int limit) {
        return delegate.pendingOutbox(createdAtOrBefore, limit);
    }

    @Override
    public CompletionStage<Void> removeOutbox(String eventId) {
        return delegate.removeOutbox(eventId);
    }

    @Override
    public CompletionStage<Void> putInbox(InboxEntry entry) {
        return delegate.putInbox(entry);
    }

    @Override
    public CompletionStage<List<InboxEntry>> findInbox(String waitKey) {
        return delegate.findInbox(waitKey);
    }

    @Override
    public CompletionStage<Void> removeInbox(InboxEntry.Key key) {
        return delegate.removeInbox(key);
    }
}
