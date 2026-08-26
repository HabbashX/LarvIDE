import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class RuntimeProvisioner {

    private static final String APP = "app/src/main";

    private static final Pattern ENGINE = Pattern.compile(
        "\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"url\"\\s*:\\s*\"([^\"]+)\"\\s*,"
            + "\\s*\"sha256\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"files\"\\s*:\\s*\\[(.*?)\\]\\s*\\}",
        Pattern.DOTALL);

    private static final Pattern FILE_MAP = Pattern.compile(
        "\\{\\s*\"from\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"to\"\\s*:\\s*\"([^\"]+)\"\\s*\\}");

    record Engine(String id, String url, String sha256, List<String[]> files) {}

    public static void main(String[] args) throws Exception {
        Path manifestPath = args.length > 0 ? Path.of(args[0])
            : Path.of("tools/runtime-manifest.json");
        if (!Files.exists(manifestPath)) {
            System.err.println("Manifest not found: " + manifestPath);
            System.exit(2);
        }
        List<Engine> engines = parseEngines(Files.readString(manifestPath));
        System.out.println("Provisioning " + engines.size() + " engine pack(s) from " + manifestPath);
        int failures = 0;
        for (Engine e : engines) {
            try {
                provision(e);
            } catch (Exception ex) {
                failures++;
                System.err.println("FAILED [" + e.id + "]: " + ex.getMessage());
            }
        }
        System.out.println(failures == 0 ? "All packs provisioned."
            : failures + " pack(s) failed.");
        if (failures > 0) System.exit(1);
    }

    static List<Engine> parseEngines(String json) {
        List<Engine> list = new ArrayList<>();
        Matcher m = ENGINE.matcher(json);
        while (m.find()) {
            List<String[]> files = new ArrayList<>();
            Matcher fm = FILE_MAP.matcher(m.group(4));
            while (fm.find()) {
                files.add(new String[]{fm.group(1), fm.group(2)});
            }
            list.add(new Engine(m.group(1), m.group(2), m.group(3).trim(), files));
        }
        return list;
    }

    static void provision(Engine e) throws Exception {
        System.out.println("[" + e.id + "] " + e.url);
        Path zip = Files.createTempFile(e.id + "-", ".zip");
        download(e.url, zip);
        if (!e.sha256.isBlank() && !e.sha256.startsWith("TBD")) {
            String actual = sha256(zip);
            if (!actual.equalsIgnoreCase(e.sha256)) {
                throw new IllegalStateException("sha256 mismatch: expected "
                    + e.sha256 + " got " + actual);
            }
            System.out.println("  sha256 OK");
        } else {
            System.out.println("  WARN: sha256 not pinned yet");
        }
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (String[] f : e.files) {
                ZipEntry entry = zf.getEntry(f[0]);
                if (entry == null) {
                    throw new IllegalStateException("missing zip entry: " + f[0]);
                }
                Path dest = Path.of(APP, f[1]);
                Files.createDirectories(dest.getParent());
                try (InputStream in = zf.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(dest)) {
                    in.transferTo(out);
                }
                System.out.println("  " + f[0] + " -> " + dest);
            }
        }
        Files.deleteIfExists(zip);
    }

    static void download(String url, Path dest) throws Exception {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Path local = Paths.get(url);
            if (!Files.exists(local)) {
                throw new IllegalStateException("pack file not found: " + local.toAbsolutePath());
            }
            Files.copy(local, dest, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        var conn = URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dest)) {
            in.transferTo(out);
        }
    }

    static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
