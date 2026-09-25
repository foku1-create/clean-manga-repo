import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Puts the clean-manga guard in front of an extension.
 *
 * Every method the app calls (popular, latest, search, details, chapters, pages, related, client)
 * is renamed to NAME$gorig in the extension's source classes, and a new NAME on the topmost source
 * class hands the call to the guard, which calls NAME$gorig and filters the answer. Calls the
 * extension makes to itself go straight to NAME$gorig, so only the app goes through the guard.
 *
 * Usage: java -cp asm.jar:asm-tree.jar Patcher.java IN.jar OUT.jar GUARD_CLASSES_DIR LIB(1.4|1.6) MIXED(true|false) CODE_BUMP
 * Prints one line: "ok <details>" or exits with code 2 and "unguardable <reason>".
 */
public class Patcher {
    static final String HTTP_SOURCE = "eu/kanade/tachiyomi/source/online/HttpSource";
    static final Set<String> HOST_SOURCE_TYPES = Set.of(
            HTTP_SOURCE, "eu/kanade/tachiyomi/source/Source", "eu/kanade/tachiyomi/source/CatalogueSource");
    static final String CONT = "Lkotlin/coroutines/Continuation;";
    static final String MANGA = "Leu/kanade/tachiyomi/source/model/SManga;";
    static final String CHAPTER = "Leu/kanade/tachiyomi/source/model/SChapter;";
    static final String FILTERS = "Leu/kanade/tachiyomi/source/model/FilterList;";
    static final String OBS = "Lrx/Observable;";
    static final String G = "cleanmanga/guard/";
    static final String SUFFIX = "$gorig";

    /** A method the app calls. superProvides: HttpSource has a working default for it. */
    record Api(String name, String desc, String helperOwner, String hostIface, boolean superProvides, boolean required) {
        /** Helper.NAME(host, args...) with the same return type. */
        String helperDesc() {
            List<Type> args = new ArrayList<>();
            args.add(Type.getObjectType(hostIface));
            args.addAll(List.of(Type.getArgumentTypes(desc)));
            return Type.getMethodDescriptor(Type.getReturnType(desc), args.toArray(new Type[0]));
        }
    }

    static List<Api> apis(String lib) {
        List<Api> list = new ArrayList<>();
        list.add(new Api("getClient", "()Lokhttp3/OkHttpClient;", G + "GuardNet", G + "GuardHost", true, false));
        // HttpSource has these in 1.4; in 1.6 they are abstract, so the extension always has its own.
        boolean old = lib.equals("1.4");
        list.add(new Api("getMangaUrl", "(" + MANGA + ")Ljava/lang/String;", G + "Guard", G + "GuardHost", old, false));
        list.add(new Api("getChapterUrl", "(" + CHAPTER + ")Ljava/lang/String;", G + "Guard", G + "GuardHost", old, false));
        if (lib.equals("1.6")) {
            String h = G + "Guard16", i = G + "GuardHost16";
            list.add(new Api("getPopularManga", "(I" + CONT + ")Ljava/lang/Object;", h, i, false, false));
            list.add(new Api("getLatestUpdates", "(I" + CONT + ")Ljava/lang/Object;", h, i, false, false));
            list.add(new Api("getSearchManga", "(ILjava/lang/String;" + FILTERS + CONT + ")Ljava/lang/Object;", h, i, false, false));
            list.add(new Api("getMangaUpdate", "(" + MANGA + "Ljava/util/List;ZZ" + CONT + ")Ljava/lang/Object;", h, i, false, true));
            list.add(new Api("getPageList", "(" + CHAPTER + CONT + ")Ljava/lang/Object;", h, i, false, true));
            list.add(new Api("fetchRelatedMangaList", "(" + MANGA + CONT + ")Ljava/lang/Object;", h, i, false, false));
        } else if (lib.equals("1.4")) {
            String h = G + "Guard14", i = G + "GuardHost14";
            list.add(new Api("fetchPopularManga", "(I)" + OBS, h, i, true, true));
            list.add(new Api("fetchLatestUpdates", "(I)" + OBS, h, i, true, true));
            list.add(new Api("fetchSearchManga", "(ILjava/lang/String;" + FILTERS + ")" + OBS, h, i, true, true));
            list.add(new Api("fetchMangaDetails", "(" + MANGA + ")" + OBS, h, i, true, true));
            list.add(new Api("fetchChapterList", "(" + MANGA + ")" + OBS, h, i, true, true));
            list.add(new Api("fetchPageList", "(" + CHAPTER + ")" + OBS, h, i, true, true));
            list.add(new Api("fetchRelatedMangaList", "(" + MANGA + CONT + ")Ljava/lang/Object;", h, i, false, false));
        } else {
            throw new IllegalArgumentException("unknown extension lib " + lib);
        }
        return list;
    }

    /**
     * Batch mode, one Java start for all jars:
     * --batch JOBS.tsv GUARD_CLASSES_DIR CODE_BUMP KEYSTORE.p12 ALIAS   (password in env CLEAN_KEY_PASSWORD)
     * Each job line: IN.jar TAB OUT.jar TAB LIB TAB MIXED. Prints one result line per job.
     */
    static void batch(String[] args) throws Exception {
        List<String> jobs = Files.readAllLines(Path.of(args[1]));
        Path guardDir = Path.of(args[2]);
        int bump = Integer.parseInt(args[3]);
        char[] password = System.getenv("CLEAN_KEY_PASSWORD").toCharArray();
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(args[4]))) {
            ks.load(in, password);
        }
        java.security.PrivateKey key = (java.security.PrivateKey) ks.getKey(args[5], password);
        java.security.cert.CertPath chain = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertPath(List.of(ks.getCertificateChain(args[5])));
        jdk.security.jarsigner.JarSigner signer = new jdk.security.jarsigner.JarSigner.Builder(key, chain)
                .digestAlgorithm("SHA-256")
                .signatureAlgorithm("SHA256withRSA")
                .signerName("CERT")
                .build();

        List<java.security.cert.X509Certificate> certs = new ArrayList<>();
        for (java.security.cert.Certificate c : ks.getCertificateChain(args[5])) certs.add((java.security.cert.X509Certificate) c);

        // Each job: IN.jar OUT.jar LIB MIXED [IN.apk OUT.apk]
        for (String job : jobs) {
            if (job.isBlank()) continue;
            String[] f = job.split("\t");
            Path out = Path.of(f[1]);
            Path unsigned = out.resolveSibling(out.getFileName() + ".unsigned");
            Path work = out.resolveSibling(out.getFileName() + ".work");
            try {
                String report = patch(Path.of(f[0]), unsigned, guardDir, f[2], Boolean.parseBoolean(f[3]), bump);
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(unsigned.toFile());
                     java.io.OutputStream os = Files.newOutputStream(out)) {
                    signer.sign(zip, os);
                }
                if (f.length >= 6) {
                    ApkPatcher.build(unsigned, Path.of(f[4]), Path.of(f[5]), bump, key, certs, work);
                }
                System.out.println("ok\t" + f[1] + "\t" + report);
            } catch (Unguardable e) {
                Files.deleteIfExists(out);
                System.out.println("unguardable\t" + f[1] + "\t" + e.getMessage());
            } catch (Throwable e) {
                Files.deleteIfExists(out);
                System.out.println("error\t" + f[1] + "\t" + e);
            } finally {
                Files.deleteIfExists(unsigned);
                ApkPatcher.deleteTree(work);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--batch")) {
            batch(args);
            return;
        }
        if (args.length != 6) {
            System.err.println("usage: Patcher IN.jar OUT.jar GUARD_CLASSES_DIR LIB MIXED CODE_BUMP");
            System.exit(1);
        }
        Path in = Path.of(args[0]), out = Path.of(args[1]), guardDir = Path.of(args[2]);
        String lib = args[3];
        boolean mixed = Boolean.parseBoolean(args[4]);
        int bump = Integer.parseInt(args[5]);
        try {
            String report = patch(in, out, guardDir, lib, mixed, bump);
            System.out.println("ok " + report);
        } catch (Unguardable e) {
            System.out.println("unguardable " + e.getMessage());
            System.exit(2);
        }
    }

    static class Unguardable extends Exception {
        Unguardable(String m) {
            super(m);
        }
    }

    static String patch(Path in, Path out, Path guardDir, String lib, boolean mixed, int bump) throws Exception {
        Map<String, byte[]> entries = readZip(in);
        byte[] manifest = entries.get("AndroidManifest.xml");
        if (manifest == null) throw new Unguardable("no AndroidManifest.xml");
        Matcher pm = PACKAGE.matcher(new String(manifest, StandardCharsets.UTF_8));
        if (!pm.find()) throw new Unguardable("manifest has no package");
        String pkg = pm.group(1);
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class")) continue;
            ClassNode node = new ClassNode();
            new ClassReader(e.getValue()).accept(node, 0);
            classes.put(node.name, node);
        }

        // The topmost source classes and everything below them.
        List<ClassNode> roots = new ArrayList<>();
        for (ClassNode c : classes.values()) {
            if (classes.containsKey(c.superName)) continue;
            boolean source = HTTP_SOURCE.equals(c.superName)
                    || c.interfaces.stream().anyMatch(HOST_SOURCE_TYPES::contains);
            if (!source) continue;
            if (!HTTP_SOURCE.equals(c.superName) && !"java/lang/Object".equals(c.superName)) {
                throw new Unguardable(c.name + " extends unknown " + c.superName);
            }
            roots.add(c);
        }
        if (roots.isEmpty()) throw new Unguardable("no source class found");

        Set<String> modified = new HashSet<>();
        // owner class -> set of "name desc" whose calls must go to $gorig
        Map<String, Set<String>> redirect = new HashMap<>();
        List<String> report = new ArrayList<>();
        List<Api> apis = apis(lib);

        for (ClassNode root : roots) {
            List<ClassNode> subtree = new ArrayList<>();
            for (ClassNode c : classes.values()) if (descendsFrom(c, root, classes)) subtree.add(c);
            boolean httpRoot = HTTP_SOURCE.equals(root.superName);
            List<String> wrapped = new ArrayList<>();
            String hostIface = null;

            for (Api api : apis) {
                boolean defined = false;
                for (ClassNode c : subtree) {
                    for (MethodNode m : c.methods) {
                        if ((m.access & Opcodes.ACC_STATIC) == 0 && m.name.equals(api.name) && m.desc.equals(api.desc)) {
                            m.name = api.name + SUFFIX;
                            m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
                            defined = true;
                            modified.add(c.name);
                        }
                    }
                }
                boolean superCall = httpRoot && api.superProvides;
                if (!defined && !superCall) {
                    if (api.required) throw new Unguardable(root.name + " has no " + api.name);
                    continue;
                }
                if (findMethod(root, api.name + SUFFIX, api.desc) == null) {
                    if (superCall) {
                        root.methods.add(superCall(root, api));
                    } else if ((root.access & Opcodes.ACC_ABSTRACT) == 0) {
                        // Only subclasses have it and the root itself is concrete: nothing sane to call.
                        if (api.required) throw new Unguardable(root.name + " cannot reach " + api.name);
                        continue;
                    }
                }
                root.methods.add(wrapper(api));
                for (ClassNode c : subtree) {
                    redirect.computeIfAbsent(c.name, k -> new HashSet<>()).add(api.name + " " + api.desc);
                }
                wrapped.add(api.name);
                if (!api.hostIface.endsWith("GuardHost")) hostIface = api.hostIface;
            }
            if (hostIface == null) {
                if (lib.equals("1.6")) throw new Unguardable(root.name + " exposes nothing to guard");
                hostIface = G + "GuardHost14";
            }
            root.interfaces.add(hostIface);
            root.methods.add(constant("guard$mixed", mixed));
            root.methods.add(constant("guard$pkg", pkg));
            // Every interface method gets a body, so strict runtimes accept the class.
            for (Api api : apis) {
                if (findMethod(root, api.name + SUFFIX, api.desc) == null) root.methods.add(unsupported(api));
            }
            modified.add(root.name);
            report.add(root.name + "[" + String.join(",", wrapped) + "]");
        }

        // Calls the extension makes to itself skip the guard.
        for (ClassNode c : classes.values()) {
            for (MethodNode m : c.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode mi) {
                        Set<String> r = redirect.get(mi.owner);
                        if (r != null && r.contains(mi.name + " " + mi.desc)) {
                            mi.name = mi.name + SUFFIX;
                            modified.add(c.name);
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        for (int i = 0; i < indy.bsmArgs.length; i++) {
                            Object redirected = redirectHandle(indy.bsmArgs[i], redirect);
                            if (redirected != indy.bsmArgs[i]) {
                                indy.bsmArgs[i] = redirected;
                                modified.add(c.name);
                            }
                        }
                    } else if (insn instanceof LdcInsnNode ldc) {
                        Object redirected = redirectHandle(ldc.cst, redirect);
                        if (redirected != ldc.cst) {
                            ldc.cst = redirected;
                            modified.add(c.name);
                        }
                    }
                }
            }
        }

        Map<String, byte[]> result = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (isSignatureFile(name)) continue;
            byte[] data = e.getValue();
            if (name.endsWith(".class")) {
                ClassNode node = classes.get(name.substring(0, name.length() - 6));
                if (node != null && modified.contains(node.name)) {
                    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(w);
                    data = w.toByteArray();
                }
            } else if (name.equals("AndroidManifest.xml")) {
                data = bumpVersionCode(new String(data, StandardCharsets.UTF_8), bump).getBytes(StandardCharsets.UTF_8);
            }
            result.put(name, data);
        }
        try (Stream<Path> files = Files.walk(guardDir)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = guardDir.relativize(p).toString().replace('\\', '/');
                if (!name.endsWith(".class")) continue;
                if (result.containsKey(name)) throw new Unguardable("jar already has " + name);
                result.put(name, Files.readAllBytes(p));
            }
        }
        writeZip(out, result);
        return String.join(" ", report);
    }

    static boolean descendsFrom(ClassNode c, ClassNode root, Map<String, ClassNode> classes) {
        for (ClassNode k = c; k != null; k = classes.get(k.superName)) {
            if (k == root) return true;
        }
        return false;
    }

    static MethodNode findMethod(ClassNode c, String name, String desc) {
        for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    static Object redirectHandle(Object o, Map<String, Set<String>> redirect) {
        if (o instanceof Handle h) {
            Set<String> r = redirect.get(h.getOwner());
            if (r != null && r.contains(h.getName() + " " + h.getDesc())) {
                return new Handle(h.getTag(), h.getOwner(), h.getName() + SUFFIX, h.getDesc(), h.isInterface());
            }
        }
        return o;
    }

    /** public NAME$gorig(args) { return super.NAME(args); } */
    static MethodNode superCall(ClassNode root, Api api) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, api.name + SUFFIX, api.desc, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loadArgs(m, api.desc);
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, root.superName, api.name, api.desc, false));
        m.instructions.add(new InsnNode(Type.getReturnType(api.desc).getOpcode(Opcodes.IRETURN)));
        return m;
    }

    /** public NAME(args) { return Helper.NAME(this, args); } */
    static MethodNode wrapper(Api api) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, api.name, api.desc, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        loadArgs(m, api.desc);
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, api.helperOwner, api.name, api.helperDesc(), false));
        m.instructions.add(new InsnNode(Type.getReturnType(api.desc).getOpcode(Opcodes.IRETURN)));
        return m;
    }

    /** public NAME$gorig(args) { throw new UnsupportedOperationException(); } */
    static MethodNode unsupported(Api api) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, api.name + SUFFIX, api.desc, null, null);
        m.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
        m.instructions.add(new InsnNode(Opcodes.DUP));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false));
        m.instructions.add(new InsnNode(Opcodes.ATHROW));
        return m;
    }

    static MethodNode constant(String name, boolean value) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, "()Z", null, null);
        m.instructions.add(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        m.instructions.add(new InsnNode(Opcodes.IRETURN));
        return m;
    }

    static MethodNode constant(String name, String value) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, "()Ljava/lang/String;", null, null);
        m.instructions.add(new LdcInsnNode(value));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        return m;
    }

    static final Pattern PACKAGE = Pattern.compile("<manifest[^>]*?\\spackage=\"([^\"]+)\"", Pattern.DOTALL);

    static void loadArgs(MethodNode m, String desc) {
        int slot = 1;
        for (Type t : Type.getArgumentTypes(desc)) {
            m.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
            slot += t.getSize();
        }
    }

    static final Pattern VERSION_CODE = Pattern.compile("android:versionCode=\"(\\d+)\"");

    static String bumpVersionCode(String manifest, int bump) throws Unguardable {
        Matcher m = VERSION_CODE.matcher(manifest);
        if (!m.find()) throw new Unguardable("manifest has no versionCode");
        long code = Long.parseLong(m.group(1)) * 100 + bump;
        if (code > Integer.MAX_VALUE) throw new Unguardable("versionCode too large");
        return m.replaceFirst("android:versionCode=\"" + code + "\"");
    }

    static boolean isSignatureFile(String name) {
        String n = name.toUpperCase();
        return n.equals("META-INF/MANIFEST.MF")
                || (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")));
    }

    static Map<String, byte[]> readZip(Path p) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(p))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (!e.isDirectory()) map.put(e.getName(), readAll(z));
            }
        }
        return map;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        in.transferTo(b);
        return b.toByteArray();
    }

    static final long FIXED_TIME = 1704499200000L;

    static void writeZip(Path out, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(out))) {
            // AndroidManifest.xml first, like upstream.
            List<String> names = new ArrayList<>(entries.keySet());
            names.remove("AndroidManifest.xml");
            names.add(0, "AndroidManifest.xml");
            for (String name : names) {
                byte[] data = entries.get(name);
                if (data == null) continue;
                ZipEntry e = new ZipEntry(name);
                e.setTime(FIXED_TIME);
                z.putNextEntry(e);
                z.write(data);
                z.closeEntry();
            }
        }
    }
}
