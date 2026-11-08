package com.anbar.wms.reporting.model;

public final class ReportParams {

    private ReportParams() {
    }

    /** How inventory valuation rows are grouped. */
    public enum ValuationGrouping { PRODUCT, WAREHOUSE, CATEGORY, TOTAL }

    /** Bucket size for the inbound/outbound period breakdown; the SQL unit is the {@code date_trunc} field. */
    public enum Granularity {
        DAY("day"), WEEK("week"), MONTH("month");

        private final String sqlUnit;

        Granularity(String sqlUnit) {
            this.sqlUnit = sqlUnit;
        }

        public String sqlUnit() {
            return sqlUnit;
        }
    }
}
