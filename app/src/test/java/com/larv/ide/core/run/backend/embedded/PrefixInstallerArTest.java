package com.larv.ide.core.run.backend.embedded;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Regression test for the .deb (ar) reader. The ar header size field is 10
 * bytes wide; reading 12 bytes pulls in the trailing "`\n" magic and makes
 * every member size unparsable, so data.tar.* is never found.
 */
public class PrefixInstallerArTest {

    private static final byte[] PAYLOAD =
        "libtalloc.so.2 payload".getBytes(StandardCharsets.US_ASCII);

    @Test
    public void readsGzippedDataMemberWithTenByteSizeField() throws Exception {
        byte[] gz = gzip(PAYLOAD);
        byte[] deb = concat(ar("data.tar.gz", gz));

        byte[] extracted = extractDataTar(deb);
        assertArrayEquals(PAYLOAD, extracted);
    }

    @Test
    public void skipsMembersBeforeDataAndHandlesOddSizes() throws Exception {
        byte[] deb = concat(
            ar("debian-binary/", "2.0\n".getBytes(StandardCharsets.US_ASCII)), // odd size
            ar("control.tar.xz/", new byte[] {1, 2, 3, 4, 5}),
            ar("data.tar.gz", gzip(PAYLOAD)));

        assertArrayEquals(PAYLOAD, extractDataTar(deb));
    }

    @Test
    public void rejectsNonArInput() {
        try {
            extractDataTar("not-an-ar-archive".getBytes(StandardCharsets.US_ASCII));
            fail("expected an exception");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue(String.valueOf(cause.getMessage()),
                cause.getMessage() != null
                    && cause.getMessage().contains("Not a valid ar archive"));
        }
    }

    private static byte[] extractDataTar(byte[] deb) throws Exception {
        Method m = PrefixInstaller.class.getDeclaredMethod("extractDataTar", byte[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, (Object) deb);
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    /** Build one ar member with a correct 10-byte size field and 2-byte magic. */
    private static byte[] ar(String name, byte[] data) {
        byte[] header = new byte[60];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, header, 0, n.length);

        byte[] size = String.valueOf(data.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 48, size.length);

        header[58] = '`';
        header[59] = '\n';

        int padded = data.length + (data.length % 2);
        byte[] member = new byte[60 + padded];
        System.arraycopy(header, 0, member, 0, 60);
        System.arraycopy(data, 0, member, 60, data.length);
        return member;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] magic = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);
        out.write(magic, 0, magic.length);
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
