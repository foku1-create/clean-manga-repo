import com.android.apksig.ApkSigner;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.JdkClassFileProvider;
import com.android.tools.r8.OutputMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds the Android (APK) twin of a patched jar: the upstream APK with its code replaced by the
 * patched classes (turned into dex by D8), its version code bumped the same way, signed with our key.
 */
public class ApkPatcher {
    static final int VERSION_CODE_ATTR = 0x0101021b;

    static void build(Path patchedJar, Path upstreamApk, Path outApk, int bump, PrivateKey key, List<X509Certificate> certs, Path work) throws Exception {
        Files.createDirectories(work);
        // 1. classes only -> dex
        Path classes = work.resolve("classes.jar");
        Map<String, byte[]> jar = Patcher.readZip(patchedJar);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(classes))) {
            for (Map.Entry<String, byte[]> e : jar.entrySet()) {
                if (!e.getKey().endsWith(".class")) continue;
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        Path dexDir = work.resolve("dex");
        Files.createDirectories(dexDir);
        final List<String> errors = new ArrayList<>();
        D8.run(D8Command.builder(new DiagnosticsHandler() {
                    @Override
                    public void error(Diagnostic d) {
                        errors.add(d.getDiagnosticMessage());
                    }

                    @Override
                    public void warning(Diagnostic d) {
                    }

                    @Override
                    public void info(Diagnostic d) {
                    }
                })
                .addProgramFiles(classes)
                .addLibraryResourceProvider(JdkClassFileProvider.fromSystemJdk())
                .setMinApiLevel(26)
                .setMode(CompilationMode.RELEASE)
                .setOutput(dexDir, OutputMode.DexIndexed)
                .build());
        if (!errors.isEmpty()) throw new Patcher.Unguardable("d8: " + errors.get(0));

        // 2. new unsigned apk: upstream resources + new dex + bumped manifest
        Path unsigned = work.resolve("unsigned.apk");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(upstreamApk));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(unsigned))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                String name = e.getName();
                if (e.isDirectory() || name.matches("classes\\d*\\.dex") || Patcher.isSignatureFile(name)) continue;
                byte[] data = Patcher.readAll(in);
                if (name.equals("AndroidManifest.xml")) data = bumpBinaryVersionCode(data, bump);
                put(out, name, data, e.getMethod() == ZipEntry.STORED || name.equals("resources.arsc"));
            }
            List<Path> dexes;
            try (Stream<Path> s = Files.list(dexDir)) {
                dexes = s.filter(p -> p.toString().endsWith(".dex")).sorted(Comparator.comparing(Path::toString)).toList();
            }
            if (dexes.isEmpty()) throw new Patcher.Unguardable("d8 wrote no dex");
            for (Path d : dexes) put(out, d.getFileName().toString(), Files.readAllBytes(d), false);
        }

        // 3. sign (apksig also aligns stored entries)
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("CERT", key, certs).build();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(unsigned.toFile())
                .setOutputApk(outApk.toFile())
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign();
    }

    static void put(ZipOutputStream out, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(Patcher.FIXED_TIME);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        }
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    /** Finds android:versionCode in the binary manifest and sets it to code * 100 + bump. */
    static byte[] bumpBinaryVersionCode(byte[] axml, int bump) throws Patcher.Unguardable {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        int[] resIds = new int[0];
        List<String> strings = new ArrayList<>();
        int pos = b.getShort(2) & 0xffff; // after the file header
        while (pos + 8 <= axml.length) {
            int type = b.getShort(pos) & 0xffff;
            int headerSize = b.getShort(pos + 2) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) break;
            if (type == 0x0001) {
                strings = readStringPool(b, pos);
            } else if (type == 0x0180) {
                resIds = new int[(size - headerSize) / 4];
                for (int i = 0; i < resIds.length; i++) resIds[i] = b.getInt(pos + headerSize + i * 4);
            } else if (type == 0x0102) {
                int ext = pos + headerSize;
                int attrStart = b.getShort(ext + 8) & 0xffff;
                int attrSize = b.getShort(ext + 10) & 0xffff;
                int attrCount = b.getShort(ext + 12) & 0xffff;
                for (int i = 0; i < attrCount; i++) {
                    int a = ext + attrStart + i * attrSize;
                    int nameIdx = b.getInt(a + 4);
                    boolean isVersionCode = (nameIdx >= 0 && nameIdx < resIds.length && resIds[nameIdx] == VERSION_CODE_ATTR)
                            || (nameIdx >= 0 && nameIdx < strings.size() && strings.get(nameIdx).equals("versionCode"));
                    if (!isVersionCode) continue;
                    int dataType = b.get(a + 15) & 0xff;
                    if (dataType < 0x10 || dataType > 0x11) throw new Patcher.Unguardable("versionCode is not an int");
                    long code = (b.getInt(a + 16) & 0xffffffffL) * 100 + bump;
                    if (code > Integer.MAX_VALUE) throw new Patcher.Unguardable("versionCode too large");
                    byte[] copy = axml.clone();
                    ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN).putInt(a + 16, (int) code);
                    return copy;
                }
            }
            pos += size;
        }
        throw new Patcher.Unguardable("binary manifest has no versionCode");
    }

    static List<String> readStringPool(ByteBuffer b, int pos) {
        int count = b.getInt(pos + 8);
        int flags = b.getInt(pos + 16);
        int stringsStart = b.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        int headerSize = b.getShort(pos + 2) & 0xffff;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int off = pos + stringsStart + b.getInt(pos + headerSize + i * 4);
            if (utf8) {
                off += (b.get(off) & 0x80) != 0 ? 2 : 1; // utf-16 length
                int len = b.get(off) & 0xff;
                if ((len & 0x80) != 0) {
                    len = ((len & 0x7f) << 8) | (b.get(off + 1) & 0xff);
                    off += 2;
                } else {
                    off += 1;
                }
                byte[] s = new byte[len];
                for (int k = 0; k < len; k++) s[k] = b.get(off + k);
                out.add(new String(s, StandardCharsets.UTF_8));
            } else {
                int len = b.getShort(off) & 0xffff;
                if ((len & 0x8000) != 0) {
                    len = ((len & 0x7fff) << 16) | (b.getShort(off + 2) & 0xffff);
                    off += 4;
                } else {
                    off += 2;
                }
                char[] s = new char[len];
                for (int k = 0; k < len; k++) s[k] = b.getChar(off + k * 2);
                out.add(new String(s));
            }
        }
        return out;
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }
}
