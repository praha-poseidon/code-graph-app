package com.poseidon.codegraph.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EndpointRuleArchiveReaderTest {

    private final EndpointRuleArchiveReader reader = new EndpointRuleArchiveReader();

    @Test
    void readsOnlySerFilesInStableEntryNameOrder() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("z-last.ser", "rule z".getBytes(StandardCharsets.UTF_8));
        entries.put("notes.txt", "ignored".getBytes(StandardCharsets.UTF_8));
        entries.put("rules/a-first.ser", "  rule a  \n".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(archive(entries))).isEqualTo(List.of("rule a", "rule z"));
    }

    @Test
    void rejectsTraversalAndInvalidUtf8() throws Exception {
        assertThatThrownBy(() -> reader.read(archive(Map.of(
            "../escape.ser", "rule".getBytes(StandardCharsets.UTF_8)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("非法路径");

        assertThatThrownBy(() -> reader.read(archive(Map.of(
            "invalid.ser", new byte[]{(byte) 0xC3, (byte) 0x28}))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("UTF-8");
    }

    @Test
    void rejectsContentThatIsNotActuallyZip() {
        MockMultipartFile archive = new MockMultipartFile(
            "endpointRules", "rules.zip", "application/zip", "not-a-zip".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> reader.read(archive))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("端点规则包不是有效的 ZIP 文件");
    }

    private MockMultipartFile archive(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("endpointRules", "rules.zip", "application/zip", bytes.toByteArray());
    }
}
