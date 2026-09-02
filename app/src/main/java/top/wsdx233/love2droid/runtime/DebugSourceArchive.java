package top.wsdx233.love2droid.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class DebugSourceArchive {
    static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ENTRY_COUNT = 4096;

    private DebugSourceArchive() {
    }

    static String readSource(InputStream archive, String source) throws IOException {
        if (archive == null) {
            return null;
        }
        String normalizedSource = normalizeSource(source);
        if (normalizedSource.isEmpty()) {
            archive.close();
            return null;
        }
        String suffixMatch = null;
        try (ZipInputStream zip = new ZipInputStream(archive)) {
            int entryCount = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    return null;
                }
                String entryName = normalizeEntry(entry.getName());
                if (!entry.isDirectory() && !entryName.isEmpty()) {
                    boolean exact = entryName.equals(normalizedSource);
                    boolean suffix = normalizedSource.endsWith("/" + entryName);
                    if (exact || (suffix && suffixMatch == null)) {
                        String text = readUtf8Entry(zip);
                        if (exact) {
                            return text;
                        }
                        suffixMatch = text;
                    }
                }
                zip.closeEntry();
            }
        }
        return suffixMatch;
    }

    private static String normalizeSource(String source) {
        if (source == null) {
            return "";
        }
        String normalized = source.trim().replace('\\', '/');
        if (normalized.startsWith("@")) {
            normalized = normalized.substring(1);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String normalizeEntry(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            return "";
        }
        String normalized = name;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return "";
            }
        }
        return normalized;
    }

    private static String readUtf8Entry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = zip.read(buffer)) != -1) {
            total += count;
            if (total > MAX_SOURCE_BYTES) {
                return null;
            }
            output.write(buffer, 0, count);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString();
        } catch (CharacterCodingException error) {
            return null;
        }
    }
}
