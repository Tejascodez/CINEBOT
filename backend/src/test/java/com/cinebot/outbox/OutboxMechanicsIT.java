package com.cinebot.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinebot.support.IntegrationTest;
import com.cinebot.support.Race;
import com.cinebot.user.Role;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The relay itself, with controllable test handlers: atomicity, retries, isolation, dead letters, leases, races. */
class OutboxMechanicsIT extends IntegrationTest {

    private static final String TYPE = "test.event";

    @Autowired
    private OutboxWriter writer;
    @Autowired
    private NamedParameterJdbcTemplate named;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private OutboxProperties properties;
    @Autowired
    private MockMvc mvc;

    private TransactionTemplate tx;
    private TestHandler steady;
    private TestHandler flaky;
    private OutboxProcessor processor;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        steady = new TestHandler("steady");
        flaky = new TestHandler("flaky");
        processor = new OutboxProcessor(List.of(steady, flaky), named, txManager, properties, Clock.systemUTC());
    }

    @Test
    void eventIsWrittenOnlyIfTheBusinessTransactionCommits() {
        tx.executeWithoutResult(s -> writer.append("test", 1, TYPE, Map.of("n", 1)));
        try {
            tx.executeWithoutResult(s -> {
                writer.append("test", 2, TYPE, Map.of("n", 2));
                throw new IllegalStateException("business failure");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }

        assertThat(jdbc.queryForList("SELECT aggregate_id FROM outbox_events", String.class)).containsExactly("1");
        assertThatThrownBy(() -> writer.append("test", 3, TYPE, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class); // no transaction = refused (MANDATORY)
    }

    @Test
    void deliversToEverySubscribedHandlerAndMarksDone() {
        UUID id = append();

        assertThat(processor.processBatch()).isEqualTo(1);

        assertThat(steady.count(id)).isEqualTo(1);
        assertThat(flaky.count(id)).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DONE");
        assertThat(processor.processBatch()).isZero();
    }

    @Test
    void failingHandlerIsRetriedWithBackoffWithoutReRunningSucceededOnes() {
        UUID id = append();
        flaky.failNext(2);

        processor.processBatch();
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(processor.processBatch()).as("not due yet: backoff").isZero();

        makeDue(id);
        processor.processBatch();
        makeDue(id);
        processor.processBatch();

        assertThat(statusOf(id)).isEqualTo("DONE");
        assertThat(steady.count(id)).as("steady ran once, despite 3 deliveries").isEqualTo(1);
        assertThat(flaky.count(id)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT handler FROM processed_events ORDER BY handler", String.class))
                .containsExactly("flaky", "steady");
    }

    @Test
    void poisonEventGoesToDeadLetterAndCanBeReplayed() throws Exception {
        UUID id = append();
        flaky.failNext(Integer.MAX_VALUE);

        for (int i = 0; i < properties.maxAttempts(); i++) {
            makeDue(id);
            processor.processBatch();
        }
        assertThat(statusOf(id)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, id))
                .contains("flaky").contains("injected failure");
        makeDue(id);
        assertThat(processor.processBatch()).as("dead events are not retried automatically").isZero();

        flaky.failNext(0);
        mvc.perform(post("/api/v1/admin/outbox/{id}/retry", id).header("Authorization", bearer(Role.ADMIN)))
                .andExpect(status().isAccepted());
        processor.processBatch();

        assertThat(statusOf(id)).isEqualTo("DONE");
        assertThat(steady.count(id)).isEqualTo(1);
    }

    @Test
    void leasedEventIsInvisibleToOtherWorkersUntilTheLeaseExpires() {
        UUID id = append();
        jdbc.update("UPDATE outbox_events SET locked_until = now() + interval '1 minute' WHERE id = ?", id);

        assertThat(processor.processBatch()).as("another worker holds the lease").isZero();

        jdbc.update("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = ?", id); // it died
        assertThat(processor.processBatch()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DONE");
    }

    @Test
    void concurrentRelaysHandleEachEventExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(append());
        }
        OutboxProcessor small = new OutboxProcessor(List.of(steady), named, txManager,
                new OutboxProperties(properties.relay(), 5, properties.lease(), properties.maxAttempts(),
                        properties.maxBackoff()), Clock.systemUTC());

        List<Callable<Integer>> workers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            workers.add(() -> {
                int total = 0;
                for (int n; (n = small.processBatch()) > 0; ) {
                    total += n;
                }
                return total;
            });
        }
        var outcomes = Race.run(workers);

        assertThat(outcomes.stream().mapToInt(Race.Outcome::value).sum()).isEqualTo(40);
        assertThat(ids).allSatisfy(id -> assertThat(steady.count(id)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'DONE'", Integer.class))
                .isEqualTo(40);
    }

    @Test
    void redeliveryAfterACrashBetweenHandlerCommitAndDoneIsHarmless() {
        UUID id = append();
        processor.processBatch();
        // crash after handlers committed but before the DONE update was written
        jdbc.update("UPDATE outbox_events SET status = 'PENDING', processed_at = NULL WHERE id = ?", id);

        processor.processBatch();

        assertThat(steady.count(id)).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("DONE");
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        assertThat(processor.backoff(1).toSeconds()).isEqualTo(2);
        assertThat(processor.backoff(3).toSeconds()).isEqualTo(8);
        assertThat(processor.backoff(20)).isEqualTo(properties.maxBackoff());
    }

    private UUID append() {
        return tx.execute(s -> writer.append("test", UUID.randomUUID(), TYPE, Map.of("x", 1)));
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE outbox_events SET available_at = now() - interval '1 second' WHERE id = ?", id);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id);
    }

    private int attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, id);
    }

    /** Counts successful handlings per event; can be told to fail the next N calls. */
    static final class TestHandler implements EventHandler {
        private final String name;
        private final Map<UUID, AtomicInteger> handled = new ConcurrentHashMap<>();
        private final AtomicInteger failuresLeft = new AtomicInteger();

        TestHandler(String name) {
            this.name = name;
        }

        void failNext(int n) {
            failuresLeft.set(n);
        }

        int count(UUID id) {
            return handled.getOrDefault(id, new AtomicInteger()).get();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> eventTypes() {
            return Set.of(TYPE);
        }

        @Override
        public void handle(OutboxMessage message) {
            if (failuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new IllegalStateException("injected failure");
            }
            handled.computeIfAbsent(message.id(), k -> new AtomicInteger()).incrementAndGet();
        }
    }
}
