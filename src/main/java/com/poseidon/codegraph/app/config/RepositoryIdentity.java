package com.poseidon.codegraph.app.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Stable platform identity, separate from the repository's display name and transport. */
public record RepositoryIdentity(String projectId, String repositoryKey, String canonicalRepository) {
    /**
     * Stable, fixed-length identity for a canonical repository. 26 Base32
     * characters carry 130 bits; the database unique key remains the final
     * collision guard.
     */
    public static String projectId(String canonicalRepository) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalRepository.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        StringBuilder encoded = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte value : digest) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5 && encoded.length() < 26) {
                bits -= 5;
                encoded.append(alphabet[(buffer >>> bits) & 31]);
            }
            if (encoded.length() == 26) break;
        }
        return encoded.toString();
    }

    public String graphScope() {
        // A new task/checkout or selected branch must not change node IDs.
        return "project:" + projectId;
    }

    public static String canonical(String address) {
        String input = address.trim();
        if (!input.contains("://")) {
            int colon = input.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("仓库地址需要包含 Git 主机和完整组路径");
            input = "ssh://" + input.substring(0, colon) + "/" + input.substring(colon + 1);
        }
        URI uri;
        try { uri = URI.create(input); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("仓库地址格式不正确"); }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!java.util.List.of("https", "http", "ssh", "git").contains(scheme) || uri.getHost() == null
                || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getUserInfo() != null && (!scheme.equals("ssh") || uri.getUserInfo().contains(":")))) {
            throw new IllegalArgumentException("请填写不含令牌、密码和查询参数的 Git 地址，凭证请在仓库认证中填写");
        }
        String path = uri.getPath().replaceAll("/+$", "").replaceAll("\\.git$", "");
        if (path.isBlank() || path.equals("/") || path.contains("//") || path.contains("/../") || path.contains("/./")) {
            throw new IllegalArgumentException("仓库地址缺少有效组路径和仓库名");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean standard = port == -1 || (scheme.equals("ssh") && port == 22)
            || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)
            || (scheme.equals("git") && port == 9418);
        // GitHub paths are case-insensitive; preserve case for self-hosted Git servers.
        if (host.equals("github.com")) path = path.toLowerCase(Locale.ROOT);
        return host + (standard ? "" : ":" + port) + path;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
