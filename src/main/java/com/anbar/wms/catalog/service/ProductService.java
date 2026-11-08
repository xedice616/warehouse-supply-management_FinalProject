package com.anbar.wms.catalog.service;

import com.anbar.wms.catalog.dto.CatalogResponses.ProductResponse;
import com.anbar.wms.catalog.dto.ProductRequests;
import com.anbar.wms.catalog.dto.ProductSearchResponses.ProductSummary;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.ProductDetails;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.catalog.mapper.CatalogMapper;
import com.anbar.wms.catalog.repository.CatalogSpecifications;
import com.anbar.wms.catalog.repository.ProductRepository;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.DuplicateResourceException;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.ResourceInUseException;
import com.anbar.wms.common.exception.StockOnHandException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.repository.SearchPatterns;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final String RESOURCE = "Product";
    private static final Map<String, String> UNIQUE_FIELDS = Map.of("uq_product_sku", "sku", "uq_product_barcode", "barcode");
    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("sku", "sku", "name", "name", "createdAt", "createdAt", "purchasePrice", "purchasePrice"),
            Sort.by("sku"));
    private static final SortWhitelist SEARCH_SORT = SortWhitelist.of(
            Map.of("sku", "sku", "name", "name"), Sort.by("sku"));

    private final ProductRepository products;
    private final CategoryService categories;
    private final UnitOfMeasureService units;
    private final ProductStockInfo stockInfo;
    private final CatalogMapper mapper;

    public ProductService(ProductRepository products, CategoryService categories, UnitOfMeasureService units,
                          ProductStockInfo stockInfo, CatalogMapper mapper) {
        this.products = products;
        this.categories = categories;
        this.units = units;
        this.stockInfo = stockInfo;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(String search, Long categoryId, Boolean active, Pageable pageable) {
        return PageResponse.from(
                products.findAll(CatalogSpecifications.products(search, categoryId, active), SORT.apply(pageable)),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(long id) {
        return mapper.toResponse(require(id));
    }

    /** Combined product search (all filters optional). Returns lightweight rows; sortable by sku, name. */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> search(String sku, String barcode, String name, Long categoryId,
                                               Boolean active, Pageable pageable) {
        String normalizedSku = SearchPatterns.isBlank(sku) ? null : Product.normalizeSku(sku);
        String trimmedBarcode = SearchPatterns.isBlank(barcode) ? null : barcode.trim();
        String namePattern = SearchPatterns.isBlank(name) ? null : SearchPatterns.lowerPrefix(name);
        return PageResponse.from(products.search(normalizedSku, trimmedBarcode, namePattern, categoryId, active,
                SEARCH_SORT.apply(pageable)),
                v -> new ProductSummary(v.id(), v.sku(), v.name(), v.barcode(), v.categoryId(), v.categoryName(),
                        v.unitCode(), v.active()));
    }

    @Transactional(readOnly = true)
    public ProductResponse getBySku(String sku) {
        return products.findBySku(Product.normalizeSku(sku))
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, "sku=" + sku));
    }

    @Transactional(readOnly = true)
    public ProductResponse getByBarcode(String barcode) {
        return products.findByBarcode(barcode.trim())
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, "barcode=" + barcode));
    }

    /** Non-throwing SKU lookup for callers (e.g. CSV import) that report a missing product as data, not a 404. */
    @Transactional(readOnly = true)
    public Optional<ProductResponse> lookupBySku(String sku) {
        return products.findBySku(Product.normalizeSku(sku)).map(mapper::toResponse);
    }

    @Transactional
    public ProductResponse create(ProductRequests.Create request) {
        String sku = Product.normalizeSku(request.sku());
        if (products.existsBySku(sku)) {
            throw new DuplicateResourceException("sku");
        }
        ProductDetails details = details(request.name(), request.description(), request.barcode(),
                categories.requireActive(request.categoryId()), units.requireActive(request.unitId()),
                request.batchTracked(), request.expiryTracked(),
                request.minStockQuantity(), request.purchasePrice(), request.sellingPrice());
        return save(new Product(sku, details));
    }

    @Transactional
    public ProductResponse update(long id, ProductRequests.Update request) {
        Product product = require(id);
        product.verifyVersion(request.version());
        ProductDetails details = details(request.name(), request.description(), request.barcode(),
                resolveCategory(product, request.categoryId()), resolveUnit(product, request.unitId()),
                request.batchTracked(), request.expiryTracked(),
                request.minStockQuantity(), request.purchasePrice(), request.sellingPrice());
        if (product.changesStockSemantics(details) && stockInfo.productHasStockHistory(id)) {
            throw new BusinessException(ErrorCode.STOCK_HISTORY_EXISTS,
                    "Unit and batch/expiry tracking cannot change once the product has stock movements");
        }
        product.update(details);
        return save(product);
    }

    @Transactional
    public ProductResponse setActive(long id, boolean active) {
        Product product = require(id);
        if (active) {
            product.activate();
        } else {
            if (stockInfo.productHasStockOnHand(id)) {
                throw new StockOnHandException(RESOURCE, "A product with stock on hand cannot be deactivated; move or write off the stock first");
            }
            product.deactivate();
        }
        return save(product);
    }

    /** Physical deletion succeeds only for products nothing refers to; the database's foreign keys decide. */
    @Transactional
    public void delete(long id) {
        products.delete(require(id));
        try {
            products.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResourceInUseException(RESOURCE);
        }
    }

    /** For new stock operations: the product must exist and be active. */
    @Transactional(readOnly = true)
    public Product requireActive(long id) {
        Product product = require(id);
        if (!product.isActive()) {
            throw new InactiveResourceException(RESOURCE, id, product.getSku());
        }
        return product;
    }

    @Transactional(readOnly = true)
    public Product require(long id) {
        return products.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private ProductCategory resolveCategory(Product product, long categoryId) {
        return product.getCategory().getId().equals(categoryId)
                ? product.getCategory()
                : categories.requireActive(categoryId);
    }

    private UnitOfMeasure resolveUnit(Product product, long unitId) {
        return product.getUnitOfMeasure().getId().equals(unitId)
                ? product.getUnitOfMeasure()
                : units.requireActive(unitId);
    }

    private static ProductDetails details(String name, String description, String barcode, ProductCategory category,
                                          UnitOfMeasure unit, Boolean batchTracked, Boolean expiryTracked,
                                          BigDecimal minStockQuantity, BigDecimal purchasePrice,
                                          BigDecimal sellingPrice) {
        if (!unit.accepts(minStockQuantity)) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY_SCALE,
                    "minStockQuantity allows at most %d decimal places for unit %s"
                            .formatted(unit.getDecimalScale(), unit.getCode()),
                    Map.of("field", "minStockQuantity", "decimalScale", unit.getDecimalScale()));
        }
        return new ProductDetails(name, blankToNull(description), blankToNull(barcode), category, unit,
                Boolean.TRUE.equals(batchTracked), Boolean.TRUE.equals(expiryTracked),
                minStockQuantity, purchasePrice, sellingPrice);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ProductResponse save(Product product) {
        try {
            return mapper.toResponse(products.saveAndFlush(product));
        } catch (DataIntegrityViolationException ex) {
            throw ConstraintViolations.duplicate(ex, UNIQUE_FIELDS).orElseThrow(() -> ex);
        }
    }
}
