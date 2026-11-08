package com.anbar.wms.importing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.anbar.wms.importing.service.CsvReader.Csv;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Test
    void parsesHeaderAndRowsAcrossLineEndings() {
        Csv csv = CsvReader.parse("a,b,c\r\n1,2,3\n4,5,6");

        assertThat(csv.header()).containsExactly("a", "b", "c");
        assertThat(csv.rows()).containsExactly(List.of("1", "2", "3"), List.of("4", "5", "6"));
    }

    @Test
    void handlesQuotedFieldsWithCommasAndEscapedQuotes() {
        Csv csv = CsvReader.parse("name,note\n\"Doe, John\",\"say \"\"hi\"\"\"");

        assertThat(csv.rows()).containsExactly(List.of("Doe, John", "say \"hi\""));
    }

    @Test
    void skipsBlankLinesAndKeepsEmptyTrailingFields() {
        Csv csv = CsvReader.parse("a,b,c\n\n1,,\n");

        assertThat(csv.rows()).containsExactly(List.of("1", "", ""));
    }

    @Test
    void emptyInputYieldsNoHeaderAndNoRows() {
        Csv csv = CsvReader.parse("");

        assertThat(csv.header()).isEmpty();
        assertThat(csv.rows()).isEmpty();
    }
}
