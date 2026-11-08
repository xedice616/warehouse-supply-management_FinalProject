package com.anbar.wms.support;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.ProductDetails;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.common.entity.Address;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persists minimal valid master data for integration tests. Every value is unique per call, so tests never
 * collide on unique constraints even when they share a database.
 */
public final class TestData {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final EntityManager em;

    public TestData(EntityManager em) {
        this.em = em;
    }

    public AppUser user() {
        int n = next();
        return persist(new AppUser("user" + n, "user" + n + "@example.test", "{bcrypt}not-a-real-hash", "Test User " + n));
    }

    public UnitOfMeasure pieces() {
        return em.createQuery("select u from UnitOfMeasure u where u.code = 'PCS'", UnitOfMeasure.class).getSingleResult();
    }

    public ProductCategory category() {
        return persist(new ProductCategory("Category " + next(), null));
    }

    public Product product() {
        return persist(new Product("SKU-" + next(), details("Product", false)));
    }

    public Product batchTrackedProduct() {
        return persist(new Product("SKU-" + next(), details("Batch product", true)));
    }

    private ProductDetails details(String name, boolean tracked) {
        return new ProductDetails(name, null, null, category(), pieces(), tracked, tracked,
                BigDecimal.ZERO, BigDecimal.ONE, null);
    }

    public Warehouse warehouse() {
        return persist(new Warehouse("WH-" + next(), "Warehouse", new Address("1 Main St", "Baku", "AZ1000", "AZ")));
    }

    public Supplier supplier() {
        int n = next();
        return persist(new Supplier("SUP-" + n, "Supplier " + n, "TAX" + n));
    }

    public StockBatch batch(Product product) {
        return persist(new StockBatch(product, "LOT-" + next(), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), null));
    }

    public <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    private static int next() {
        return SEQUENCE.incrementAndGet();
    }
}
