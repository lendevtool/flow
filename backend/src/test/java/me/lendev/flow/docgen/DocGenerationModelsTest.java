package me.lendev.flow.docgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import me.lendev.flow.docgen.DocGenerationModels.Preview;
import me.lendev.flow.docgen.DocGenerationModels.SeedTemplate;
import org.junit.jupiter.api.Test;

class DocGenerationModelsTest {

    @Test
    void seedTemplateOwnsItsBytesAndComparesTheirContents() {
        byte[] source = {1, 2};
        var seed = new SeedTemplate("fixture", "测试模板", null, source, null, null, null);
        var equivalent = new SeedTemplate("fixture", "测试模板", null, new byte[]{1, 2}, null, null, null);
        var different = new SeedTemplate("fixture", "测试模板", null, new byte[]{1, 3}, null, null, null);

        Arrays.fill(source, (byte) 0);
        Arrays.fill(seed.file(), (byte) 0);

        assertThat(seed.file()).containsExactly(1, 2);
        assertThat(seed).isEqualTo(equivalent).hasSameHashCodeAs(equivalent).isNotEqualTo(different)
                .hasToString("SeedTemplate[code=fixture, name=测试模板, fileBytes=2]");
    }

    @Test
    void previewOwnsItsBytesAndComparesTheirContents() {
        byte[] source = {1, 2};
        var preview = new Preview("试生成.docx", source);
        var equivalent = new Preview("试生成.docx", new byte[]{1, 2});
        var different = new Preview("试生成.docx", new byte[]{1, 3});

        Arrays.fill(source, (byte) 0);
        Arrays.fill(preview.file(), (byte) 0);

        assertThat(preview.file()).containsExactly(1, 2);
        assertThat(preview).isEqualTo(equivalent).hasSameHashCodeAs(equivalent).isNotEqualTo(different)
                .hasToString("Preview[fileName=试生成.docx, fileBytes=2]");
    }
}
