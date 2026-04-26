package dev.relism.flash.ext.webbundler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class AssetIo {
    private AssetIo() {
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading asset " + path, e);
        }
    }

    static byte[] readClasspath(String path) {
        try (InputStream in = AssetIo.class.getClassLoader().getResourceAsStream(stripLeadingSlash(path))) {
            if (in == null) throw new IllegalStateException("Missing classpath resource: " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading classpath resource: " + path, e);
        }
    }

    static byte[] readClasspathOrNull(String path) {
        try (InputStream in = AssetIo.class.getClassLoader().getResourceAsStream(stripLeadingSlash(path))) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading classpath resource: " + path, e);
        }
    }

    static String quotedSha1(byte[] data) {
        return "\"" + sha1Hex(data) + "\"";
    }

    private static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(data);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    static String stripLeadingSlash(String path) {
        if (path == null || path.isBlank()) return path;
        return path.startsWith("/") ? path.substring(1) : path;
    }

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String p = path.replace('\\', '/');
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    static String stripBrGzSuffix(String path) {
        if (path.endsWith(".br")) return path.substring(0, path.length() - 3);
        if (path.endsWith(".gz")) return path.substring(0, path.length() - 3);
        return path;
    }

    static boolean isFingerprinted(String path) {
        String file = path;
        int slash = file.lastIndexOf('/');
        if (slash >= 0) file = file.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        if (dot <= 0) return false;
        int prevDot = file.lastIndexOf('.', dot - 1);
        if (prevDot <= 0) return false;
        String token = file.substring(prevDot + 1, dot);
        if (token.length() < 8) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }
}
