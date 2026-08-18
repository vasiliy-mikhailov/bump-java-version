package tech.mikhailov.bjv.jvm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import tech.mikhailov.bjv.engine.Env;

/**
 * WHAT IS ACTUALLY INSIDE A DEPENDENCY — the one question the workspace cannot answer.
 *
 * <p>An agent can read every file in the project and still not know the thing that decides a Boot 2
 * to 3 migration: whether a third-party jar is compiled against {@code javax} and whether a jakarta
 * release exists to move to. One bump paid for that gap exactly. Told only that four tests had
 * stopped passing, a troubleshooter guessed at the cause, wrote {@code new Kaptcha()} against what
 * is an interface, and filed the resulting compile error as evidence that the dependency was
 * incompatible with JDK 21. Every fact needed to get it right was sitting in a jar it could not open.
 *
 * <p>Read straight out of the class files rather than shelled to {@code javap}, because the
 * controller image carries a JRE by design: it hosts agents and arbitrates, and the moment it also
 * carries build tooling the separation that keeps the arbiter honest is gone. A constant pool is a
 * simple format and this only reads it.
 */
public final class Jars {

    /** Signals nothing found, so the caller can answer rather than throw. */
    public static final class NotFound extends Exception {
        NotFound(String message) {
            super(message);
        }
    }

    private static final int ACC_INTERFACE = 0x0200;
    /** Class-file major to JDK, the whole range, because a Boot 2 era jar is often older than 8. */
    private static final Map<Integer, String> JDK = Map.ofEntries(
            Map.entry(49, "5"), Map.entry(50, "6"), Map.entry(51, "7"), Map.entry(52, "8"),
            Map.entry(53, "9"), Map.entry(54, "10"), Map.entry(55, "11"), Map.entry(56, "12"),
            Map.entry(57, "13"), Map.entry(58, "14"), Map.entry(59, "15"), Map.entry(60, "16"),
            Map.entry(61, "17"), Map.entry(62, "18"), Map.entry(63, "19"), Map.entry(64, "20"),
            Map.entry(65, "21"), Map.entry(66, "22"), Map.entry(67, "23"), Map.entry(68, "24"),
            Map.entry(69, "25"));

    private final Path repository;

    Jars(Path repository) {
        this.repository = repository;
    }

    /** The default local repository, which is where every resolved dependency already sits. */
    public static Jars local() {
        String m2 = Env.get("BJV_M2", System.getProperty("user.home") + "/.m2");
        return new Jars(Path.of(m2, "repository"));
    }

    // ---- locating ----

    /** Every version of {@code group:artifact} present locally, newest last. */
    List<String> versions(String group, String artifact) throws NotFound {
        Path dir = repository.resolve(group.replace('.', '/')).resolve(artifact);
        if (!Files.isDirectory(dir)) {
            throw new NotFound("nothing under " + group + ":" + artifact
                    + " in the local repository. Check the coordinates against the pom: a typo here"
                    + " looks exactly like an artifact that does not exist.");
        }
        List<String> found = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .forEach(found::add);
        } catch (IOException e) {
            throw new NotFound("could not list versions: " + e.getMessage());
        }
        found.sort(Migrate::compare);
        return found;
    }

    private Path jar(String group, String artifact, String version) throws NotFound {
        Path p = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + ".jar");
        if (!Files.isRegularFile(p)) {
            throw new NotFound("no jar at " + p + ". Versions present: "
                    + String.join(", ", versions(group, artifact)));
        }
        return p;
    }

    // ---- reporting ----

    /**
     * A whole-artifact report: how it registers with Spring, what namespaces it needs, what it holds.
     *
     * <p>Shaped around the questions a migration actually asks, not around what a class file
     * contains. A raw dump would be larger and would leave the reader to notice the two facts that
     * matter.
     */
    public String describe(String group, String artifact, String version) throws NotFound {
        List<String> all = versions(group, artifact);
        String v = version == null || version.isBlank() ? all.get(all.size() - 1) : version;
        Path jar = jar(group, artifact, v);
        StringBuilder out = new StringBuilder();
        out.append(group).append(':').append(artifact).append(':').append(v).append('\n');
        out.append("  versions in the local repository: ").append(String.join(", ", all))
                .append('\n');
        out.append("    (this is what has been resolved here before, which is not the same as what\n"
                + "     exists upstream. A single version present is weak evidence either way.)\n");

        Set<String> namespaces = new LinkedHashSet<>();
        Map<String, Integer> javaxUsers = new TreeMap<>();
        List<String> types = new ArrayList<>();
        int minMajor = Integer.MAX_VALUE;
        String factories = null;
        boolean bootThreeImports = false;

        try (JarFile jf = new JarFile(jar.toFile())) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                JarEntry e = it.nextElement();
                String name = e.getName();
                if (name.equals("META-INF/spring.factories")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        factories = new String(in.readAllBytes());
                    }
                } else if (name.startsWith("META-INF/spring/")
                        && name.endsWith("AutoConfiguration.imports")) {
                    bootThreeImports = true;
                } else if (name.endsWith(".class") && !name.contains("module-info")) {
                    try (InputStream in = jf.getInputStream(e)) {
                        ClassFile cf = ClassFile.read(in);
                        minMajor = Math.min(minMajor, cf.major);
                        types.add((cf.isInterface ? "interface " : "class ") + cf.name);
                        for (String ref : cf.referenced) {
                            String ns = namespace(ref);
                            if (ns != null) {
                                namespaces.add(ns);
                                if (ns.startsWith("javax.")) {
                                    javaxUsers.merge(cf.name, 1, Integer::sum);
                                }
                            }
                        }
                    } catch (IOException | RuntimeException unreadable) {
                        types.add("unreadable " + name);
                    }
                }
            }
        } catch (IOException e) {
            throw new NotFound("could not open " + jar + ": " + e.getMessage());
        }

        out.append("  compiled for: JDK ")
                .append(JDK.getOrDefault(minMajor, "major " + minMajor)).append('\n');

        out.append("\n  SPRING REGISTRATION\n");
        if (factories != null) {
            out.append("    META-INF/spring.factories is present. SPRING BOOT 3 DOES NOT READ IT,\n"
                    + "    so every autoconfiguration it declares is simply absent under Boot 3:\n");
            factories.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .limit(12).forEach(l -> out.append("      ").append(l).append('\n'));
        } else {
            out.append("    no META-INF/spring.factories\n");
        }
        out.append("    META-INF/spring/...AutoConfiguration.imports (the Boot 3 mechanism): ")
                .append(bootThreeImports ? "present" : "ABSENT").append('\n');

        out.append("\n  NAMESPACES IT NEEDS\n");
        List<String> javax = namespaces.stream().filter(n -> n.startsWith("javax.")).sorted().toList();
        List<String> jakarta = namespaces.stream().filter(n -> n.startsWith("jakarta.")).sorted()
                .toList();
        if (javax.isEmpty() && jakarta.isEmpty()) {
            out.append("    neither javax nor jakarta: this artifact is not part of that migration\n");
        }
        if (!javax.isEmpty()) {
            out.append("    javax:   ").append(String.join(", ", javax)).append('\n');
            out.append("    Under Spring Boot 3 these do not exist. The classes that need them:\n");
            javaxUsers.keySet().stream().limit(10)
                    .forEach(c -> out.append("      ").append(c).append('\n'));
        }
        if (!jakarta.isEmpty()) {
            out.append("    jakarta: ").append(String.join(", ", jakarta)).append('\n');
        }

        out.append("\n  TYPES (").append(types.size()).append(")\n");
        types.stream().sorted().limit(60).forEach(t -> out.append("    ").append(t).append('\n'));
        if (types.size() > 60) {
            out.append("    ... and ").append(types.size() - 60).append(" more\n");
        }
        String deps = declared(group, artifact, v);
        if (!deps.isBlank()) {
            out.append("\n  IT DECLARES THESE DEPENDENCIES\n").append(deps);
            out.append("    A class this artifact uses but does not contain lives in one of these.\n"
                    + "    That matters when only some classes here are the blocker: the pieces\n"
                    + "    underneath are often untouched by the javax move and still usable.\n");
        }
        out.append("\n  Ask again with `type` set to one of these for its members.\n");
        return out.toString();
    }

    /** One type's members, which is what tells a class from an interface before writing {@code new}. */
    public String describeType(String group, String artifact, String version, String type)
            throws NotFound {
        List<String> all = versions(group, artifact);
        String v = version == null || version.isBlank() ? all.get(all.size() - 1) : version;
        Path jar = jar(group, artifact, v);
        String want = type.replace('.', '/') + ".class";
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry e = jf.getJarEntry(want);
            if (e == null) {
                // GUESSING THE COORDINATES IS THE FAILURE MODE. A troubleshooter looking for
                // DefaultKaptcha guessed com.google.code:kaptcha from the package name, got nothing,
                // and concluded the migration was impossible. The package name is not the artifact
                // name; the pom next door says which artifact it really is.
                String deps = declared(group, artifact, v);
                throw new NotFound(type + " is not in " + artifact + "-" + v + ".jar."
                        + (deps.isBlank() ? " Ask without `type` for the list of what is."
                        : " It may be in one of the artifacts this one declares:\n" + deps
                        + "Do not guess coordinates from the package name; they rarely match."));
            }
            try (InputStream in = jf.getInputStream(e)) {
                ClassFile cf = ClassFile.read(in);
                StringBuilder out = new StringBuilder();
                out.append(cf.isInterface ? "interface " : "class ").append(cf.name);
                if (cf.isInterface) {
                    out.append("\n  THIS IS AN INTERFACE. `new ").append(simple(cf.name))
                            .append("()` will not compile; it needs an implementation or a bean.");
                }
                out.append("\n  compiled for JDK ")
                        .append(JDK.getOrDefault(cf.major, "major " + cf.major)).append('\n');
                out.append("\n  MEMBERS\n");
                cf.fields.forEach(f -> out.append("    field  ").append(f).append('\n'));
                cf.methods.forEach(m -> out.append("    method ").append(m).append('\n'));
                List<String> ns = cf.referenced.stream().map(Jars::namespace)
                        .filter(java.util.Objects::nonNull).distinct().sorted().toList();
                if (!ns.isEmpty()) {
                    out.append("\n  NAMESPACES THIS TYPE NEEDS: ").append(String.join(", ", ns))
                            .append('\n');
                }
                return out.toString();
            }
        } catch (IOException io) {
            throw new NotFound("could not read " + type + ": " + io.getMessage());
        }
    }

    /**
     * The dependencies an artifact declares, read from the pom beside its jar.
     *
     * <p>Deliberately not a search. The local repository holds 139,935 jars and scanning them for a
     * class name is neither cheap nor necessary: the artifact that owns a type a jar references is
     * almost always one this pom already names.
     */
    private String declared(String group, String artifact, String version) {
        Path pom = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
                .resolve(artifact + "-" + version + ".pom");
        if (!Files.isRegularFile(pom)) {
            return "";
        }
        String text;
        try {
            text = Files.readString(pom);
        } catch (IOException unreadable) {
            return "";
        }
        int start = text.indexOf("<dependencies>");
        int end = text.lastIndexOf("</dependencies>");
        if (start < 0 || end < start) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<dependency>(.*?)</dependency>", java.util.regex.Pattern.DOTALL)
                .matcher(text.substring(start, end));
        int shown = 0;
        while (m.find() && shown < 25) {
            String g = tag(m.group(1), "groupId");
            String a = tag(m.group(1), "artifactId");
            String v = tag(m.group(1), "version");
            String scope = tag(m.group(1), "scope");
            if (a.isBlank()) {
                continue;
            }
            out.append("    ").append(g).append(':').append(a);
            if (!v.isBlank()) {
                out.append(':').append(v);
            }
            if (!scope.isBlank()) {
                out.append("  (").append(scope).append(')');
            }
            out.append('\n');
            shown++;
        }
        return out.toString();
    }

    private static String tag(String xml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static String simple(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(dot + 1);
    }

    /** The javax/jakarta package a referenced type belongs to, or null if it is neither. */
    private static String namespace(String internalName) {
        String n = internalName.replace('/', '.');
        if (!n.startsWith("javax.") && !n.startsWith("jakarta.")) {
            return null;
        }
        // Two segments is the useful grain: javax.servlet, jakarta.persistence. Deeper is noise,
        // shallower cannot tell the servlet migration from javax.imageio, which never moved.
        int first = n.indexOf('.');
        int second = n.indexOf('.', first + 1);
        return second < 0 ? n : n.substring(0, second);
    }

    // ---- the class file itself ----

    /** Just enough of the format to answer the questions above. */
    private static final class ClassFile {
        String name;
        int major;
        boolean isInterface;
        final List<String> fields = new ArrayList<>();
        final List<String> methods = new ArrayList<>();
        final Set<String> referenced = new LinkedHashSet<>();

        static ClassFile read(InputStream raw) throws IOException {
            DataInputStream in = new DataInputStream(raw);
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            ClassFile cf = new ClassFile();
            in.readUnsignedShort();
            cf.major = in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> {
                        int idx = in.readUnsignedShort();
                        if (tag == 7) {
                            classNameIndex[i] = idx;
                        }
                    }
                    case 15 -> in.skipBytes(3);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++; // longs and doubles take two constant pool slots
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    default -> throw new IOException("unknown constant pool tag " + tag);
                }
            }
            for (int i = 1; i < count; i++) {
                if (classNameIndex[i] != 0 && utf8[classNameIndex[i]] != null) {
                    cf.referenced.add(utf8[classNameIndex[i]]);
                }
            }
            int access = in.readUnsignedShort();
            cf.isInterface = (access & ACC_INTERFACE) != 0;
            int thisClass = in.readUnsignedShort();
            cf.name = utf8[classNameIndex[thisClass]].replace('/', '.');
            in.readUnsignedShort();
            in.skipBytes(2 * in.readUnsignedShort());
            cf.fields.addAll(members(in, utf8, false));
            cf.methods.addAll(members(in, utf8, true));
            // Descriptors in signatures name types the constant pool may not carry as Class entries.
            for (String m : cf.methods) {
                for (String t : m.split("[ (),]+")) {
                    if (t.contains(".")) {
                        cf.referenced.add(t.replace('.', '/'));
                    }
                }
            }
            return cf;
        }

        private static List<String> members(DataInputStream in, String[] utf8, boolean method)
                throws IOException {
            List<String> out = new ArrayList<>();
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                in.readUnsignedShort();
                String name = utf8[in.readUnsignedShort()];
                String descriptor = utf8[in.readUnsignedShort()];
                out.add(method ? signature(name, descriptor) : type(descriptor) + " " + name);
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    in.readUnsignedShort();
                    in.skipBytes(in.readInt());
                }
            }
            return out;
        }
    }

    /** {@code (Ljava/lang/String;J)Z} plus {@code validate} becomes {@code boolean validate(String, long)}. */
    private static String signature(String name, String descriptor) {
        int close = descriptor.lastIndexOf(')');
        String returns = type(descriptor.substring(close + 1));
        List<String> params = new ArrayList<>();
        String inner = descriptor.substring(1, close);
        int i = 0;
        while (i < inner.length()) {
            int start = i;
            while (inner.charAt(i) == '[') {
                i++;
            }
            if (inner.charAt(i) == 'L') {
                i = inner.indexOf(';', i);
            }
            i++;
            params.add(type(inner.substring(start, i)));
        }
        return returns + " " + name + "(" + String.join(", ", params) + ")";
    }

    private static String type(String descriptor) {
        int arrays = 0;
        while (descriptor.startsWith("[")) {
            arrays++;
            descriptor = descriptor.substring(1);
        }
        String base = switch (descriptor.charAt(0)) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            default -> descriptor;
        };
        return base + "[]".repeat(arrays);
    }
}
