package io.github.xiaoaiswitch.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Confirms identical code and resources for an already reviewed, separately authenticated APK. */
public final class VerifiedControlPayload {
    private static final long SIZE_LIMIT = 2L << 30;
    private static final Pattern SIGNATURE = Pattern.compile("META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC))");

    private VerifiedControlPayload() {}

    public static String identity(List<File> apks) throws IOException {
        if (apks.isEmpty()) throw new IOException("No installed APK");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        var number = ByteBuffer.allocate(Long.BYTES);
        var buffer = new byte[65536];
        long total = 0;
        writeLength(digest, number, apks.size());
        for (File apk : apks) {
            try (var zip = new ZipFile(apk)) {
                var entries = zip.stream().filter(entry -> !entry.isDirectory() && !entry.getName().equals("AndroidManifest.xml")
                                && !SIGNATURE.matcher(entry.getName()).matches())
                        .sorted(Comparator.comparing(ZipEntry::getName)).collect(Collectors.toList());
                writeLength(digest, number, entries.size());
                for (ZipEntry entry : entries) {
                    long size = entry.getSize();
                    if (size < 0 || size > SIZE_LIMIT - total) throw new IOException("Control payload exceeds byte limit");
                    total += size;
                    byte[] name = entry.getName().getBytes(StandardCharsets.UTF_8);
                    writeLength(digest, number, name.length);
                    digest.update(name);
                    writeLength(digest, number, size);
                    try (var data = zip.getInputStream(entry)) {
                        long consumed = 0;
                        for (int read; (read = data.read(buffer)) != -1; ) {
                            consumed += read;
                            if (consumed > size) throw new IOException("Unexpected entry size");
                            digest.update(buffer, 0, read);
                        }
                        if (consumed != size) throw new IOException("Unexpected entry size");
                    }
                }
            }
        }
        var result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static void writeLength(MessageDigest digest, ByteBuffer buffer, long value) {
        buffer.putLong(0, value);
        digest.update(buffer.array());
    }
}
