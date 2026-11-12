package com.larv.ide.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Efficient file reader. Reads the whole file (up to a size cap) with a single
 * exact-size destination array — no intermediate chunk buffers and no copy —
 * detects BOMs and UTF-8/UTF-16, and falls back to ISO-8859-1 when the bytes
 * are not valid UTF-8 so no data is lost.
 */
public final class FileReader {

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private FileReader() {
    }

    public static String read(File file) throws IOException {
        return decode(readBytes(file));
    }

    public static byte[] readBytes(File file) throws IOException {
        if (file == null) throw new IOException("null file");
        long length = file.length();
        if (length > MAX_BYTES) {
            throw new IOException("File too large to open (" + (length / (1024 * 1024))
                + " MB). Limit is 2 MB.");
        }
        if (length == 0) return new byte[0];
        if (length > Integer.MAX_VALUE) throw new IOException("File too large to read");

        byte[] bytes = new byte[(int) length];
        try (FileInputStream fis = new FileInputStream(file)) {
            int total = 0;
            while (total < bytes.length) {
                int read = fis.read(bytes, total, bytes.length - total);
                if (read == -1) break;
                total += read;
            }
            if (total < bytes.length) {
                // File shrank between length() and read(): trim instead of
                // returning trailing zero bytes.
                byte[] trimmed = new byte[total];
                System.arraycopy(bytes, 0, trimmed, 0, total);
                return trimmed;
            }
        }
        return bytes;
    }

    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        // BOM detection
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer cb = dec.decode(ByteBuffer.wrap(bytes));
            return cb.toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            // Not valid UTF-8: keep every byte representable instead of corrupting.
            return new String(bytes, 0, bytes.length, StandardCharsets.ISO_8859_1);
        }
    }
}
