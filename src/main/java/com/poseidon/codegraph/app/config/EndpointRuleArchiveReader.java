package com.poseidon.codegraph.app.config;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

@Component
public class EndpointRuleArchiveReader {

    static final long MAX_ARCHIVE_BYTES = 5L * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 10L * 1024 * 1024;
    static final long MAX_RULE_BYTES = 2L * 1024 * 1024;
    static final int MAX_RULE_FILES = 100;

    public List<String> read(MultipartFile archive) {
        if (archive == null || archive.isEmpty()) {
            return List.of();
        }
        String filename = archive.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("端点规则包必须是 ZIP 文件");
        }
        if (archive.getSize() > MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("端点规则包不能超过 5 MB");
        }

        Map<String, String> rules = new TreeMap<>();
        long expandedBytes = 0;
        try (BufferedInputStream archiveInput = new BufferedInputStream(archive.getInputStream())) {
            requireZipSignature(archiveInput);
            try (ZipInputStream input = new ZipInputStream(archiveInput)) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String entryName = safeEntryName(entry.getName());
                    if (entry.isDirectory() || !entryName.toLowerCase(Locale.ROOT).endsWith(".ser")) {
                        input.closeEntry();
                        continue;
                    }
                    if (rules.size() >= MAX_RULE_FILES) {
                        throw new IllegalArgumentException("端点规则包最多包含 100 个 .ser 文件");
                    }
                    byte[] content = readRule(input);
                    expandedBytes += content.length;
                    if (expandedBytes > MAX_EXPANDED_BYTES) {
                        throw new IllegalArgumentException("端点规则包解压后不能超过 10 MB");
                    }
                    String source = decodeUtf8(entryName, content).trim();
                    if (source.isEmpty()) {
                        throw new IllegalArgumentException("端点规则不能为空: " + entryName);
                    }
                    if (rules.putIfAbsent(entryName, source) != null) {
                        throw new IllegalArgumentException("端点规则包包含重复文件: " + entryName);
                    }
                    input.closeEntry();
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (ZipException exception) {
            throw new IllegalArgumentException("端点规则包不是有效的 ZIP 文件", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("端点规则包读取失败", exception);
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("端点规则包中没有找到 .ser 文件");
        }
        return new ArrayList<>(rules.values());
    }

    private void requireZipSignature(BufferedInputStream input) throws IOException {
        input.mark(4);
        int first = input.read();
        int second = input.read();
        input.reset();
        if (first != 'P' || second != 'K') {
            throw new IllegalArgumentException("端点规则包不是有效的 ZIP 文件");
        }
    }

    private byte[] readRule(ZipInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            if (output.size() > MAX_RULE_BYTES) {
                throw new IllegalArgumentException("单个 .ser 规则文件不能超过 2 MB");
            }
        }
        return output.toByteArray();
    }

    private String safeEntryName(String rawName) {
        String name = rawName == null ? "" : rawName.replace('\\', '/');
        if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("端点规则包包含非法路径");
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("端点规则包包含非法路径: " + name);
            }
        }
        return name;
    }

    private String decodeUtf8(String entryName, byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("端点规则必须使用 UTF-8 编码: " + entryName, exception);
        }
    }
}
