package me.lendev.flow.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import me.lendev.flow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

class DocumentNamesTest {

    @Test
    void firstAndLaterVersionsUseTheDateAndSequence() {
        var date = LocalDate.of(2026, 11, 12);
        assertThat(DocumentNames.versionNo(date, 1)).isEqualTo("20261112");
        assertThat(DocumentNames.versionNo(date, 2)).isEqualTo("20261112V2");
        assertThat(DocumentNames.versionNo(date.plusDays(1), 1)).isEqualTo("20261113");
    }

    @Test
    void insertsVersionBeforeTheFinalExtensionAndHandlesExtensionlessNames() {
        assertThat(DocumentNames.downloadName("合同.pdf", "20261112V2")).isEqualTo("合同_20261112V2.pdf");
        assertThat(DocumentNames.downloadName("archive.tar.gz", "20261112")).isEqualTo("archive.tar_20261112.gz");
        assertThat(DocumentNames.downloadName("README", "20261112")).isEqualTo("README_20261112");
    }

    @Test
    void stripsClientPathAndRejectsControlCharacters() {
        assertThat(DocumentNames.originalName("C:\\fakepath\\合同.pdf")).isEqualTo("合同.pdf");
        assertThatThrownBy(() -> DocumentNames.originalName("a\r\nb.pdf")).isInstanceOf(BadRequestException.class);
    }
}
