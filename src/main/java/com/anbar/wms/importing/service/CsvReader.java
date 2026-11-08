package com.anbar.wms.importing.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 CSV parser: comma-separated, optional double-quoted fields with escaped quotes ({@code ""}),
 * and CR, LF or CRLF line breaks. Enough for the fixed import format; no external dependency is pulled in for it.
 */
final class CsvReader {

    private CsvReader() {
    }

    record Csv(List<String> header, List<List<String>> rows) {
    }

    /** Parses the text into a header row and the data rows that follow. Blank lines are skipped. */
    static Csv parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && !fieldStarted) {
                inQuotes = true;
                fieldStarted = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                addRecord(records, current);
                current = new ArrayList<>();
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        current.add(field.toString());
        addRecord(records, current);

        if (records.isEmpty()) {
            return new Csv(List.of(), List.of());
        }
        return new Csv(records.getFirst(), records.subList(1, records.size()));
    }

    private static void addRecord(List<List<String>> records, List<String> record) {
        if (record.size() == 1 && record.getFirst().isBlank()) {
            return;
        }
        records.add(record);
    }
}
