package com.larv.ide.core.run.backend.embedded;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class TermuxPackageInstallerTest {

    private static final String INDEX =
        "Package: openjdk-17\n"
            + "Version: 17.0.14\n"
            + "Depends: libc++ (>= 1.0), libandroid-support | libandroid-support-static\n"
            + "Filename: pool/main/o/openjdk-17/openjdk-17_17.0.14_aarch64.deb\n"
            + "Size: 12345\n"
            + "\n"
            + "Package: libandroid-support\n"
            + "Version: 1.0\n"
            + "Pre-Depends: libc++\n"
            + "Filename: pool/main/l/libandroid-support/libandroid-support_1.0_aarch64.deb\n"
            + "\n";

    @Test
    public void parseIndexReadsNamesVersionsFilesAndDependencies() {
        Map<String, TermuxPackageInstaller.Pkg> packages =
            TermuxPackageInstaller.parseIndex(INDEX);
        assertEquals(2, packages.size());
        TermuxPackageInstaller.Pkg jdk = packages.get("openjdk-17");
        assertEquals("17.0.14", jdk.version);
        assertEquals("pool/main/o/openjdk-17/openjdk-17_17.0.14_aarch64.deb", jdk.filename);
        assertTrue(jdk.depends.contains("libc++ (>= 1.0)"));
    }

    @Test
    public void parseIndexMergesPreDepends() {
        TermuxPackageInstaller.Pkg lib = TermuxPackageInstaller.parseIndex(INDEX)
            .get("libandroid-support");
        assertTrue(lib.depends.contains("libc++"));
    }
}
