package me.shedaniel.modflattener;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.util.version.VersionParsingException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Main {
    private static final Random RANDOM = new Random();
    private final List<String> warnings = new ArrayList<>();
    private final String arrow;
    private final String splitUUID;
    private final HashMap<String, String> zipProperties;
    private long ogSize = 0L;

    public Main() {
        this.arrow = generateRandomString(5);
        this.splitUUID = generateRandomString(7);

        this.zipProperties = new HashMap<>();
        zipProperties.put("create", "false");
    }

    public static void main(String[] args) throws Throwable {
        File root = new File(System.getProperty("user.dir"));
        new Main().flatten(root, new File(root, ".removejij"), new File(root, "flattenedMods"));
    }

    private void flatten(File mods, File tmp, File flattenedMods) throws Throwable {
        deleteRecursively(tmp);
        deleteRecursively(flattenedMods);
        flattenedMods.mkdirs();
        System.out.println();
        info("Step 1: Extracting Jars");
        System.out.println();
        for (File file : mods.listFiles()) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                if (isJarExcluded(file)) continue;
                ogSize += file.length();
                info("Extracting Jar -> " + file.getName());
                use("Extracting Depth-0 Jars", new FileInputStream(file), stream -> {
                    countJar(
                            file.lastModified(),
                            file.getName(),
                            stream,
                            true,
                            tmp
                    );
                });
                run("Copying Depth-0 Jars", () -> {
                    String modId = useMapped(new FileInputStream(file), jarBytes -> {
                        String id = getNullableModId(file.getName(), jarBytes);
                        if (id != null) return id;
                        return "invalid";
                    });
                    File toFile = new File(new File(tmp, modId),
                            last(file.getName().split("/")));
                    toFile.getParentFile().mkdirs();
                    use(new FileInputStream(file), stream -> {
                        use(new FileOutputStream(toFile),
                                writer -> IOUtils.write(IOUtils.toByteArray(stream), writer));
                    });
                });
            }
        }
        System.out.println();
        info("Step 2: Clearing JIJ Status");
        System.out.println();
        run("Clearing JIJ Status", () -> {
            for (File modIdFolder : tmp.listFiles()) {
                if (!modIdFolder.isDirectory())
                    throw new IllegalStateException("Non directory entry in tmp folder: " + modIdFolder.getAbsolutePath());
                for (File file : modIdFolder.listFiles()) {
                    info("Clearing JIJ Status -> " + mods.toPath().relativize(file.toPath()).toString());
                    clearJIJStatus(file);
                }
            }
        });
        System.out.println();
        info("Step 3: Selecting Jars");
        System.out.println();
        for (File modIdFolder : tmp.listFiles()) {
            if (!modIdFolder.isDirectory())
                throw new IllegalStateException("Non directory entry in tmp folder: " + modIdFolder.getAbsolutePath());
            run("Selecting Jar for " + modIdFolder.getName(), () -> selectMod(modIdFolder, flattenedMods, mods));
        }
        System.out.println();
        System.out.println("Mod Duplication Stats (Showing top 20 results)");
        Map<String, Integer> countMap = new HashMap<>();
        for (File modIdFolder : tmp.listFiles()) {
            if (!modIdFolder.isDirectory())
                throw new IllegalStateException("Non directory entry in tmp folder: " + modIdFolder.getAbsolutePath());
            countMap.put(modIdFolder.getName(), modIdFolder.listFiles().length);
        }
        countMap.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).limit(20).forEach(entry -> {
            System.out.println(" - " + entry.getKey() + " x" + entry.getValue());
        });
        deleteRecursively(tmp);
        long newSize = Stream.of(flattenedMods.listFiles()).filter(file -> file.isFile() && file.getName().endsWith(".jar"))
                .mapToLong(File::length).sum();
        if (!warnings.isEmpty()) {
            System.out.println();
            System.out.println("You have " + warnings.size() + " warnings:");
            for (String warning : warnings) {
                System.out.println(" - " + warning);
            }
        }
        System.out.println();
        System.out.println("Flattened " + readableFileSize(ogSize) + " to " + readableFileSize(newSize));
    }

    private String readableFileSize(long length) {
        if (length <= 0) return "0";
        String[] units = new String[]{"B", "kB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10((double) length) / Math.log10(1024.0));
        return new DecimalFormat("#,##0.#").format(length / Math.pow(1024.0, digitGroups)) + " " + units[digitGroups];
    }

    private void selectMod(File modIdFolder, File flattenedMods, File mods) throws Throwable {
        if (modIdFolder.getName().equals("invalid")) {
            for (File file : modIdFolder.listFiles()) {
                Files.copy(file.toPath(), new File(flattenedMods, stripInfoName(file.getName())).toPath());
            }
        } else {
            Optional<File> depth0File = Stream.of(modIdFolder.listFiles()).filter(file ->
                    file.isFile() && stripInfoName(file.getName()).endsWith(".jar") &&
                            new File(mods, stripInfoName(file.getName())).exists()).findFirst();
            if (depth0File.isPresent()) {
                Files.copy(depth0File.get().toPath(), new File(flattenedMods,
                        stripInfoName(depth0File.get().getName())).toPath());
                info("Selected " + stripAndDepthName(depth0File.get().getName()) +
                        " from " + modIdFolder.getName() + " as depth 0 mod");
                return;
            }
            Map<File, Map.Entry<String, Optional<SemanticVersion>>> semverMap = new LinkedHashMap<>();
            for (File file : modIdFolder.listFiles()) {
                String modVersion = useMapped(new FileInputStream(file), stream ->
                        getNullableModVersion(stripInfoName(file.getName()), stream));
                SemanticVersion semver = null;
                try {
                    semver = SemanticVersion.parse(modVersion);
                } catch (VersionParsingException ignored) {
                }
                semverMap.put(file, new AbstractMap.SimpleImmutableEntry<>(modVersion,
                        Optional.ofNullable(semver)));
            }
            if (semverMap.isEmpty())
                throw new IllegalStateException("Mod " + modIdFolder.getName() + " has no entries!");
            List<String> invalidVersions = semverMap.entrySet().stream().filter(
                    entry -> entry.getValue().getKey() == null
            ).map(entry -> stripAndDepthName(entry.getKey().getName())).collect(Collectors.toList());
            List<String> invalidSemverVersions = semverMap.entrySet().stream().filter(
                    entry -> !entry.getValue().getValue().isPresent()
            ).map(entry -> stripAndDepthName(entry.getKey().getName()) + " [" + entry.getValue().getKey() + "]").collect(Collectors.toList());
            String invalidVersion = UUID.randomUUID().toString();
            Map<SemanticVersion, List<File>> versionGroups = new HashMap<>();
            for (Map.Entry<File, Map.Entry<String, Optional<SemanticVersion>>> entry : distinctBy(semverMap.entrySet(), entry ->
                    entry.getValue().getKey() == null ? invalidVersion : entry.getValue().getKey())) {
                Optional<SemanticVersion> versionOptional = entry.getValue().getValue().flatMap(version -> versionGroups.keySet().stream().filter(semver -> semver != null && semver.compareTo(version) == 0).findFirst());
                SemanticVersion version = versionOptional.orElse(entry.getValue().getValue().orElse(null));
                List<File> files = versionGroups.get(version);
                if (files == null) {
                    versionGroups.put(version, new ArrayList<>());
                    files = versionGroups.get(version);
                }
                files.add(entry.getKey());
            }
            SortedMap<SemanticVersion, List<File>> sortedVersionGroups = new TreeMap<>(
                    Comparator.nullsFirst(Comparator.naturalOrder())
            );
            sortedVersionGroups.putAll(versionGroups);
            boolean forceSelect = false;
            if (semverMap.size() > 1 && !invalidVersions.isEmpty()) {
                warn(modIdFolder.getName() + " has invalid version(s): " + String.join(", ", invalidVersions));
                forceSelect = true;
            } else if (semverMap.size() > 1 && !invalidSemverVersions.isEmpty()) {
                warn(modIdFolder.getName() + " has invalid semantic version(s): " + String.join(", ", invalidSemverVersions));
                forceSelect = true;
            } else if (last(sortedVersionGroups.values()).size() > 1) {
                warn(modIdFolder.getName() + " has duplicate entries: " +
                        last(sortedVersionGroups.entrySet()).getValue().stream().map(
                                file -> stripAndDepthName(file.getName())
                        ).collect(Collectors.joining(", ")));
                forceSelect = true;
            } else {
                Map.Entry<File, Map.Entry<String, Optional<SemanticVersion>>> max = semverMap.entrySet().stream().max(Comparator.comparing(entry -> entry.getValue().getValue().get())).get();
                List<Map.Entry<File, Map.Entry<String, Optional<SemanticVersion>>>> entries = new ArrayList<>(semverMap.entrySet());
                entries.removeIf(entry -> entry.getValue().getValue().equals(max.getValue().getValue()));
                List<Map.Entry<File, Map.Entry<String, Optional<SemanticVersion>>>> against = distinctBy(entries, entry -> entry.getValue().getValue());

                if (against.isEmpty()) {
                    info("Selected " + stripAndDepthName(max.getKey().getName()) + " (" + max.getValue().getKey() +
                            ") from " + modIdFolder.getName() + " as the only version");
                } else {
                    info("Selected " + stripAndDepthName(max.getKey().getName()) + " (" + max.getValue().getKey() +
                            ") from " + modIdFolder.getName() + " as the latest version against " +
                            against.stream().map(entry -> entry.getValue().getKey()).collect(Collectors.joining(", ")));
                }
                Files.copy(max.getKey().toPath(),
                        new File(flattenedMods, stripInfoName(max.getKey().getName())).toPath());
            }
            if (semverMap.size() > 1 && forceSelect) {
                Map<String, File> order = new ConcurrentHashMap<>();
                Map<Integer, List<Map.Entry<File, Map.Entry<String, Optional<SemanticVersion>>>>> depthGroupedMap = semverMap.entrySet().stream().collect(Collectors.groupingBy(entry -> getDepth(entry.getKey().getName())));
                for (Map.Entry<File, Map.Entry<String, Optional<SemanticVersion>>> entry : depthGroupedMap.entrySet().stream().min(Map.Entry.comparingByKey()).get().getValue()) {
                    order.put(entry.getValue().getKey(), entry.getKey());
                }
                File first = first(order.values());
                warn("Forcefully selected " + stripAndDepthName(first.getName()) + " from " + modIdFolder.getName());
                Files.copy(first.toPath(), new File(flattenedMods, stripInfoName(first.getName())).toPath());
            }
        }
    }

    private void info(String msg) {
        System.out.println("[INFO] " + msg);
    }

    private void warn(String msg) {
        System.out.println("[WARN] " + msg);
        warnings.add(msg);
    }

    private String stripInfoName(String name) {
        int indexOf = name.indexOf(splitUUID);
        if (indexOf < 0) return name;
        return name.substring(indexOf + splitUUID.length());
    }

    private String onlyInfoName(String name) {
        int indexOf = name.indexOf(splitUUID);
        if (indexOf < 0) return name;
        return name.substring(0, indexOf);
    }

    private String stripAndDepthName(String name) {
        return stripInfoName(name) + " (Depth " + getDepth(name) + ")";
    }

    private int getDepth(String name) {
        return StringUtils.countMatches(onlyInfoName(name), arrow);
    }

    private void countJar(long lastModified, String modName, InputStream stream, boolean outer, File tmp) throws Throwable {
        ZipInputStream zip = new ZipInputStream(stream);
        while (true) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null) break;
            if (!entry.isDirectory() && entry.getName().endsWith(".jar")) {
                byte[] bytes = readBytes(zip);
                use("Extracting JIJ Jars", new ByteArrayInputStream(bytes), jarBytes -> {
                    countJar(
                            entry.getTime(),
                            modName + arrow + last(entry.getName().split("/")),
                            jarBytes,
                            true, tmp
                    );
                });
                String modId = useMapped(new ByteArrayInputStream(bytes), jarBytes -> {
                    String id = getNullableModId(entry.getName(), jarBytes);
                    if (id != null) return id;
                    return "invalid";
                });
                File toFile = new File(new File(tmp, modId),
                        (outer ? modName + arrow + splitUUID : "") +
                                last(entry.getName().split("/")));
                toFile.getParentFile().mkdirs();
                use("Copying JIJ jar into tmp folder", new FileOutputStream(toFile),
                        writer -> IOUtils.write(bytes, writer));
            }
        }
    }

    private void clearJIJStatus(File file) {
        run("Clearing JIJ Status of " + file.getAbsolutePath(), () -> {
            List<String> jars = new ArrayList<>();
            use("Getting JIJ jars from " + file.getAbsolutePath(), new FileInputStream(file), stream -> {
                ZipInputStream zip = new ZipInputStream(stream);
                while (true) {
                    ZipEntry entry = zip.getNextEntry();
                    if (entry == null) break;
                    if (!entry.isDirectory() && entry.getName().endsWith(".jar")) {
                        jars.add(entry.getName());
                    }
                }
            });

            URI zipURI = file.getAbsoluteFile().toURI();
            use(FileSystems.newFileSystem(URI.create("jar:" + zipURI), zipProperties), zipFs -> {
                for (String jar : jars) Files.delete(zipFs.getPath(jar));
                Path fabricModJson = zipFs.getPath("fabric.mod.json");
                try {
                    JsonObject object = JsonParser.parseString(
                            useMapped(Files.newInputStream(fabricModJson), this::readText)
                    ).getAsJsonObject();
                    object.remove("jars");
                    Files.delete(fabricModJson);
                    use(Files.newBufferedWriter(fabricModJson), writer ->
                            IOUtils.write(new Gson().toJson(object), writer));
                } catch (NoSuchFileException ignored) {
                }
            });
        });
    }

    private String getNullableModId(String modName, InputStream stream) {
        return supply("Reading mod id from " + modName, () -> {
            ZipInputStream zip = new ZipInputStream(stream);
            while (true) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;
                if (entry.getName().equals("fabric.mod.json"))
                    try {
                        return JsonParser.parseReader(new JsonReader(new StringReader(readText(zip)))).getAsJsonObject()
                                .get("id").getAsJsonPrimitive().getAsString();
                    } catch (Throwable ignored) {
                    }
            }
            return null;
        });
    }

    private String getNullableModVersion(String modName, InputStream stream) {
        return supply("Reading mod version from " + modName, () -> {
            ZipInputStream zip = new ZipInputStream(stream);
            while (true) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null) break;
                if (entry.getName().equals("fabric.mod.json"))
                    try {
                        return JsonParser.parseString(readText(zip)).getAsJsonObject()
                                .get("version").getAsJsonPrimitive().getAsString();
                    } catch (Throwable ignored) {
                    }
            }
            return null;
        });
    }

    private String readText(InputStream stream) {
        return supply("Reading text from stream",
                () -> String.join("\n", IOUtils.readLines(stream, Charset.defaultCharset())));
    }

    private String generateRandomString(int range) {
        String allowedChar = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ123456789";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < range; i++)
            builder.append(allowedChar.charAt(RANDOM.nextInt(allowedChar.length())));
        return builder.toString();
    }

    private boolean isJarExcluded(File file) throws Throwable {
        return useMapped("Checking if jar should be excluded: " + file.getAbsolutePath(),
                new FileInputStream(file), stream -> {
                    ZipInputStream zip = new ZipInputStream(stream);
                    while (true) {
                        ZipEntry entry = zip.getNextEntry();
                        if (entry == null) break;
                        if (entry.getName().equals(".modpacks-flatter-exclude"))
                            return true;
                    }
                    return false;
                });
    }

    private byte[] readBytes(InputStream stream) throws IOException {
        return IOUtils.toByteArray(stream);
    }

    private <T extends Closeable> void use(T closeable, Consumer<T> block) throws Throwable {
        Throwable throwable = null;
        try {
            block.accept(closeable);
        } catch (Throwable t) {
            throwable = t;
            throw t;
        } finally {
            if (closeable != null && throwable == null) {
                try {
                    closeable.close();
                } catch (Throwable t) {
                    throwable.addSuppressed(t);
                }
            }
        }
    }

    private <T extends Closeable> void use(String task, T closeable, Consumer<T> block) throws Throwable {
        Throwable throwable = null;
        try {
            block.accept(closeable);
        } catch (Throwable t) {
            t = new RuntimeException(task, t);
            throwable = t;
            throw t;
        } finally {
            if (closeable != null && throwable == null) {
                try {
                    closeable.close();
                } catch (Throwable t) {
                    throwable.addSuppressed(t);
                }
            }
        }
    }

    private <T extends Closeable, R> R useMapped(T closeable, Function<T, R> block) throws Throwable {
        Throwable throwable = null;
        try {
            return block.apply(closeable);
        } catch (Throwable t) {
            throwable = t;
            throw t;
        } finally {
            if (closeable != null && throwable == null) {
                try {
                    closeable.close();
                } catch (Throwable t) {
                    throwable.addSuppressed(t);
                }
            }
        }
    }

    private <T extends Closeable, R> R useMapped(String task, T closeable, Function<T, R> block) throws Throwable {
        Throwable throwable = null;
        try {
            return block.apply(closeable);
        } catch (Throwable t) {
            t = new RuntimeException(task, t);
            throwable = t;
            throw t;
        } finally {
            if (closeable != null && throwable == null) {
                try {
                    closeable.close();
                } catch (Throwable t) {
                    throwable.addSuppressed(t);
                }
            }
        }
    }

    private <T> T first(Collection<T> values) {
        return values.iterator().next();
    }

    private <T, R> List<T> distinctBy(Iterable<T> iterable, Function<T, R> selection) {
        return supply("Distinct List", () -> {
            Set<R> set = new HashSet<>();
            List<T> list = new ArrayList<>();
            for (T t : iterable) {
                R key = selection.apply(t);
                if (set.add(key))
                    list.add(t);
            }
            return list;
        });
    }

    private void run(String task, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            throw new RuntimeException(task, t);
        }
    }

    private <T> T supply(String task, Supplier<T> runnable) {
        try {
            return runnable.get();
        } catch (Throwable t) {
            throw new RuntimeException(task, t);
        }
    }

    private void deleteRecursively(File folder) {
        if (!folder.exists())
            return;
        run("Delete folder recursively " + folder.getAbsolutePath(), () -> {
            Files.walk(folder.toPath()).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        });
    }

    private String last(String[] split) {
        return split[split.length - 1];
    }

    private <T> T last(Iterable<T> collection) {
        if (collection instanceof List)
            return ((List<T>) collection).get(((List<T>) collection).size() - 1);
        Iterator<T> iterator = collection.iterator();
        if (!iterator.hasNext())
            throw new NoSuchElementException("Iterable is empty.");
        T next = iterator.next();
        while (iterator.hasNext())
            next = iterator.next();
        return next;
    }

    private interface Runnable {
        void run() throws Throwable;
    }

    private interface Consumer<T> {
        void accept(T t) throws Throwable;
    }

    private interface Supplier<T> {
        T get() throws Throwable;
    }

    private interface Function<T, R> {
        R apply(T t) throws Throwable;
    }
}
