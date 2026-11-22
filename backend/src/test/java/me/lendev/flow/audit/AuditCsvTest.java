package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import me.lendev.flow.audit.query.AuditCsv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditCsvTest {

    @ParameterizedTest
    @ValueSource(strings = {"＝合计", " ＋备注", "－备注", "＠运营", "\t缩进", "\r换行", "\n换行", " \t缩进"})
    void prefixesTextStartingWithSpreadsheetSensitiveCharacters(String value) {
        String csv = new String(AuditCsv.bytes(List.of(Map.of("actorName", value)), Locale.SIMPLIFIED_CHINESE), StandardCharsets.UTF_8);

        assertTrue(csv.contains(",\"'" + value + "\","));
    }

    @Test
    void quotesSeparatorsAndBlocksSpreadsheetFormulasIncludingWhitespacePrefixes() {
        for (String value : List.of("=1+1", " +SUM(A1:A2)", "-cmd", "@SUM(A1)", "\t=2")) {
            String csv = new String(AuditCsv.bytes(List.of(Map.of("actorName", value)), Locale.SIMPLIFIED_CHINESE), StandardCharsets.UTF_8);
            assertTrue(csv.startsWith("\ufeff操作时间(UTC),操作者"));
            assertTrue(csv.contains("\"'"), value);
        }
        String csv = new String(AuditCsv.bytes(List.of(Map.of("summary", "a,\"b\"\n下一行")), Locale.ENGLISH), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\ufeffOperation time (UTC),Actor"));
        assertTrue(csv.contains("\"a,\"\"b\"\"\n下一行\""));
    }
}
