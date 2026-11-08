package com.anbar.wms.catalog.service;

import com.anbar.wms.catalog.dto.CatalogResponses.CategoryResponse;
import com.anbar.wms.catalog.dto.CategoryRequests;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.mapper.CatalogMapper;
import com.anbar.wms.catalog.repository.CatalogSpecifications;
import com.anbar.wms.catalog.repository.ProductCategoryRepository;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.ResourceInUseException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

    private static final String RESOURCE = "Category";
    private static final String SIBLING_NAME_CONSTRAINT = "uq_product_category_sibling_name";
    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("name", "name", "createdAt", "createdAt"), Sort.by("name").and(Sort.by("id")));

    private final ProductCategoryRepository categories;
    private final CatalogMapper mapper;

    public CategoryService(ProductCategoryRepository categories, CatalogMapper mapper) {
        this.categories = categories;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<CategoryResponse> list(String search, Long parentId, Boolean active, Pageable pageable) {
        return PageResponse.from(
                categories.findAll(CatalogSpecifications.categories(search, parentId, active), SORT.apply(pageable)),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(long id) {
        return mapper.toResponse(require(id));
    }

    @Transactional
    public CategoryResponse create(CategoryRequests.Create request) {
        ProductCategory parent = request.parentId() == null ? null : requireActive(request.parentId());
        return save(new ProductCategory(request.name(), parent));
    }

    @Transactional
    public CategoryResponse update(long id, CategoryRequests.Update request) {
        ProductCategory category = require(id);
        category.verifyVersion(request.version());
        ProductCategory parent = resolveNewParent(category, request.parentId());
        category.update(request.name(), parent);
        return save(category);
    }

    @Transactional
    public CategoryResponse setActive(long id, boolean active) {
        ProductCategory category = require(id);
        if (active) {
            category.activate();
        } else {
            category.deactivate();
        }
        return save(category);
    }

    @Transactional
    public void delete(long id) {
        categories.delete(require(id));
        try {
            categories.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResourceInUseException(RESOURCE);
        }
    }

    ProductCategory requireActive(long id) {
        ProductCategory category = require(id);
        if (!category.isActive()) {
            throw new InactiveResourceException(RESOURCE, id, "'" + category.getName() + "'");
        }
        return category;
    }

    ProductCategory require(long id) {
        return categories.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private ProductCategory resolveNewParent(ProductCategory category, Long parentId) {
        if (parentId == null) {
            return null;
        }
        ProductCategory current = category.getParent();
        if (current != null && current.getId().equals(parentId)) {
            return current;
        }
        ProductCategory parent = requireActive(parentId);
        if (parent.equals(category) || parent.isDescendantOf(category)) {
            throw new BusinessException(ErrorCode.INVALID_CATEGORY_HIERARCHY);
        }
        return parent;
    }

    private CategoryResponse save(ProductCategory category) {
        try {
            return mapper.toResponse(categories.saveAndFlush(category));
        } catch (DataIntegrityViolationException ex) {
            throw ConstraintViolations.duplicate(ex, Map.of(SIBLING_NAME_CONSTRAINT, "name")).orElseThrow(() -> ex);
        }
    }
}
