package com.anbar.wms.purchasing.entity;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "ordered_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal orderedQuantity;

    @Column(name = "received_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal receivedQuantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "notes", length = 500)
    private String notes;

    PurchaseOrderLine(PurchaseOrder purchaseOrder, int lineNo, Product product, BigDecimal orderedQuantity,
                      BigDecimal unitPrice, String notes) {
        this.purchaseOrder = Objects.requireNonNull(purchaseOrder, "purchaseOrder");
        this.lineNo = lineNo;
        this.product = Objects.requireNonNull(product, "product");
        this.orderedQuantity = Objects.requireNonNull(orderedQuantity, "orderedQuantity");
        this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
        this.notes = notes;
        this.receivedQuantity = BigDecimal.ZERO;
    }

    public void receive(BigDecimal quantity) {
        if (quantity.compareTo(outstandingQuantity()) > 0) {
            throw new BusinessException(ErrorCode.RECEIPT_EXCEEDS_ORDER,
                    "Line %d: receiving %s would exceed the ordered quantity (%s outstanding)".formatted(
                            lineNo, quantity.stripTrailingZeros().toPlainString(),
                            outstandingQuantity().stripTrailingZeros().toPlainString()),
                    Map.of("lineNo", lineNo, "outstanding", outstandingQuantity()));
        }
        receivedQuantity = receivedQuantity.add(quantity);
    }

    public BigDecimal lineAmount() {
        return orderedQuantity.multiply(unitPrice);
    }

    public BigDecimal outstandingQuantity() {
        return orderedQuantity.subtract(receivedQuantity);
    }

    public boolean isFullyReceived() {
        return receivedQuantity.compareTo(orderedQuantity) >= 0;
    }
}
