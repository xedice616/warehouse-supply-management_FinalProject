package com.anbar.wms.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.anbar.wms.common.exception.InsufficientStockException;
import com.anbar.wms.inventory.dto.StockMovementRequests;
import com.anbar.wms.inventory.service.StockMovementService;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.security.AuthenticatedUserResolver;
import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real concurrency against PostgreSQL: many threads, each in its own transaction, released at the same moment.
 * Every assertion also re-checks that balances equal the ledger and that nothing went negative.
 */
class StockConcurrencyIT extends AbstractIntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    private StockMovementService movements;

    @Autowired
    private AuthenticatedUserResolver users;

    @Autowired
    private JdbcTemplate jdbc;

    private AuthenticatedUser admin;
    private long product;
    private long north;
    private long south;

    @BeforeEach
    void setUp() throws Exception {
        ApiFixtures api = new ApiFixtures(mockMvc, adminToken());
        long pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        product = api.product(unique("race"), "Contended", api.category(unique("Race")), pieces, "0");
        north = api.warehouse(unique("N").replace("-", ""));
        south = api.warehouse(unique("S").replace("-", ""));
        admin = users.resolve(jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class,
                ADMIN_USERNAME)).orElseThrow();
    }

    @Test
    void concurrentWithdrawalsNeverConsumeMoreThanIsOnHand() throws Exception {
        movements.inbound(inbound(north, "10"), admin);

        List<Outcome> outcomes = runConcurrently(30, i -> () -> movements.outbound(
                new StockMovementRequests.Outbound(product, north, BigDecimal.ONE, null, null, null), admin));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(10);
        assertThat(outcomes.stream().filter(o -> o.error() instanceof InsufficientStockException)).hasSize(20);
        assertThat(balance(north)).isZero();
        assertConsistent();
    }

    @Test
    void oppositeTransfersNeitherDeadlockNorLoseStock() throws Exception {
        movements.inbound(inbound(north, "50"), admin);
        movements.inbound(inbound(south, "50"), admin);

        List<Outcome> outcomes = runConcurrently(40, i -> () -> movements.transfer(new StockMovementRequests.Transfer(
                product, i % 2 == 0 ? north : south, i % 2 == 0 ? south : north, new BigDecimal("2"), null, null, null), admin));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(balance(north).add(balance(south))).isEqualByComparingTo("100");
        assertThat(balance(north)).isEqualByComparingTo("50");
        assertConsistent();
    }

    @Test
    void transfersAndWithdrawalsCompetingForTheSameStock() throws Exception {
        movements.inbound(inbound(north, "12"), admin);

        List<Outcome> outcomes = runConcurrently(30, i -> i % 2 == 0
                ? () -> movements.outbound(new StockMovementRequests.Outbound(product, north, BigDecimal.ONE, null, null, null), admin)
                : () -> movements.transfer(new StockMovementRequests.Transfer(product, north, south, BigDecimal.ONE, null, null, null), admin));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(12);
        assertThat(balance(north)).isZero();
        assertConsistent();
    }

    private StockMovementRequests.Inbound inbound(long warehouse, String quantity) {
        return new StockMovementRequests.Inbound(product, warehouse, new BigDecimal(quantity),
                null, null, null, null, null, null, null);
    }

    private List<Outcome> runConcurrently(int tasks, java.util.function.IntFunction<Callable<?>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            Callable<?> work = task.apply(i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    work.call();
                    return new Outcome(null);
                } catch (Exception ex) {
                    return new Outcome(ex);
                }
            }));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).allSatisfy(o -> assertThat(o.error())
                .satisfiesAnyOf(e -> assertThat(e).isNull(), e -> assertThat(e).isInstanceOf(InsufficientStockException.class)));
        return outcomes;
    }

    private BigDecimal balance(long warehouse) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_balance WHERE warehouse_id = ? AND product_id = ?""",
                BigDecimal.class, warehouse, product);
    }

    private void assertConsistent() {
        for (long warehouse : new long[] {north, south}) {
            BigDecimal ledger = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(CASE WHEN destination_warehouse_id = ? THEN quantity ELSE 0 END), 0)
                         - COALESCE(SUM(CASE WHEN source_warehouse_id = ? THEN quantity ELSE 0 END), 0)
                    FROM stock_movement WHERE product_id = ?""", BigDecimal.class, warehouse, warehouse, product);
            assertThat(balance(warehouse)).isEqualByComparingTo(ledger).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    private record Outcome(Exception error) {
        boolean succeeded() {
            return error == null;
        }
    }
}
