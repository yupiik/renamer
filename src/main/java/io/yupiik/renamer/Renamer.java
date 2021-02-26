package io.yupiik.renamer;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;
import static lombok.AccessLevel.PRIVATE;

@Log
@RequiredArgsConstructor(access = PRIVATE)
public final class Renamer extends SimpleFileVisitor<Path> implements Runnable {
    private final Path from;
    private final Path to;
    private final Collection<Predicate<String>> excludes;
    private final Collection<Predicate<String>> excludeFiltering;
    private final Collection<BiFunction<String, String, String>> replacements;
    private final boolean dryRun;
    private final boolean renameFolders;
    private final boolean overwrite;

    @Override
    public void run() {
        log.finest(() -> "Configuration\n" +
                "from: " + from + '\n' +
                "to: " + to + '\n' +
                "excludes: " + excludes + '\n' +
                "excludeFiltering: " + excludeFiltering + '\n' +
                "replacements: " + replacements + '\n' +
                "dryRun: " + dryRun + '\n' +
                "renameFolders: " + renameFolders + '\n' +
                "overwrite: " + overwrite + '\n');
        try {
            Files.walkFileTree(from, this);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
        final var name = dir.getFileName().toString();
        if (excludes.stream().anyMatch(e -> e.test(name))) {
            log.finest(() -> "Skipping " + dir);
            return FileVisitResult.SKIP_SUBTREE;
        }
        log.finest(() -> "Visiting " + dir);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
        if (renameFolders && to == from) {
            var relativized = from.relativize(dir).toString();
            var value = relativized;
            for (final var fn : replacements) {
                value = fn.apply("", value);
            }
            if (!relativized.equals(value)) {
                final var r = relativized;
                final var v = value;
                log.finest(() -> "Renamed folder " + r + " to " + v + " so deleting it now");
                if (!dryRun) {
                    Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            } else {
                log.finest(() -> "Keeping folder " + dir);
            }
        }
        return super.postVisitDirectory(dir, exc);
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
        final var name = file.getFileName().toString();
        if (excludes.stream().anyMatch(e -> e.test(name))) {
            log.finest(() -> "Skipping " + file);
            return FileVisitResult.CONTINUE;
        }
        log.finest(() -> "Replacing " + file);
        try {
            if (excludeFiltering.stream().anyMatch(e -> e.test(name))) {
                final Path target = toTarget(file);
                if (dryRun) {
                    log.info(() -> "[DRYRUN] Copying " + file + " to " + target);
                    return FileVisitResult.CONTINUE;
                }
                if (!overwrite && Files.exists(target)) {
                    log.info(() -> target + " already exists, skipping");
                    return FileVisitResult.CONTINUE;
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
                return FileVisitResult.CONTINUE;
            }

            final var originalContent = Files.readString(file);
            var content = originalContent;
            for (final var fn : replacements) {
                content = fn.apply(name, content);
            }
            if (content.equals(originalContent)) {
                log.finest(() -> "No replacement in " + file);
            } else {
                log.info(() -> "Replacements done in " + file);
            }

            final Path target = toTarget(file);
            if (dryRun) {
                final var ctt = content;
                log.info(() -> "[DRYRUN] Writing " + file + " to " + target + ":\n" + ctt);
                return FileVisitResult.CONTINUE;
            }
            if (!overwrite && Files.exists(target)) {
                log.info(() -> target + " already exists, skipping");
            } else if (target.equals(file)) {
                Files.writeString(file, content, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (final IOException ioe) {
            log.log(Level.SEVERE, "Error reading " + file + " (" + ioe.getMessage() + ")", ioe);
            throw ioe;
        }
        return FileVisitResult.CONTINUE;
    }

    private Path toTarget(Path file) {
        var relativized = from.relativize(file).toString();
        if (renameFolders) {
            var value = relativized;
            for (final var fn : replacements) {
                value = fn.apply("", value);
            }
            if (!relativized.equals(value)) {
                final var r = relativized;
                final var v = value;
                log.finest(() -> "Renaming folder " + r + " to " + v);
            }
            relativized = value;
        }
        return to.resolve(relativized);
    }

    public static void main(final String... args) {
        System.setProperty("java.util.logging.manager", System.getProperty("java.util.logging.manager",
                "io.yupiik.logging.jul.YupiikLogManager"));

        if (args.length == 0 || args.length % 2 != 0) {
            throw new IllegalArgumentException("Invalid arguments");
        }

        boolean dryRun = false;
        boolean renameFolders = false;
        boolean overwrite = false;
        Path from = null;
        Path to = null;
        final Collection<Predicate<String>> excludes = new ArrayList<>();
        final Collection<Predicate<String>> excludeFiltering = new ArrayList<>();
        final Collection<BiFunction<String /*filename*/, String/*content*/, String/*result*/>> renamings = new ArrayList<>();
        for (int i = 0; i < args.length; i += 2) {
            final var current = args[i];
            if (!current.startsWith("--")) {
                throw new IllegalArgumentException("Illegal argument " + i + ", " + current + ", should start with '--'");
            }
            final var value = args[i + 1];
            switch (current) {
                case "--dry":
                    dryRun = Boolean.parseBoolean(value);
                    break;
                case "--overwrite":
                    overwrite = Boolean.parseBoolean(value);
                    break;
                case "--rename-folders":
                    renameFolders = Boolean.parseBoolean(value);
                    break;
                case "--from":
                    from = Paths.get(value);
                    break;
                case "--to":
                    to = Paths.get(value);
                    break;
                case "--exclude-filtering":
                    if ("auto".equals(value)) {
                        excludeFiltering.addAll(List.of(
                                new EndsWith(".so"), new EndsWith(".png"), new EndsWith(".svg"),
                                new EndsWith(".gif"), new EndsWith(".jpeg"), new EndsWith(".jpg"),
                                new EndsWith(".xsl"), new EndsWith(".xslx"), new EndsWith(".ico"),
                                new EndsWith(".ttf"), new EndsWith(".woff"), new EndsWith(".woff2"),
                                new EndsWith(".eot"), new EndsWith(".otf")));
                    } else if (value.startsWith("*")) {
                        final var suffix = value.substring(1);
                        excludeFiltering.add(s -> s.endsWith(suffix));
                    } else if (value.endsWith("*")) {
                        final var prefix = value.substring(0, value.length() - 1);
                        excludeFiltering.add(s -> s.startsWith(prefix));
                    } else if (value.startsWith("r/")) {
                        excludeFiltering.add(Pattern.compile(value.substring("r/".length())).asMatchPredicate());
                    } else {
                        excludeFiltering.add(s -> Objects.equals(s, value));
                    }
                    break;
                case "--exclude":
                    if ("auto".equals(value)) {
                        excludes.addAll(List.of(
                                new Equals("node_modules"), new Equals(".idea"), new Equals("target"),
                                new Equals(".project"), new Equals(".classpath"), new Equals(".settings"),
                                new Equals(".factorypath"), new Equals(".vscode"), new Equals("generated"),
                                new Equals(".cache"), new Equals(".node"), new Equals("screenshots"), new Equals("derby.log"),
                                new Equals("release.properties"), new EndsWith(".releaseBackup"),
                                new Equals(".git"),
                                new EndsWith(".iml"), new EndsWith(".ipr"), new EndsWith(".iws"),
                                new EndsWith(".mp4")));
                    } else if (value.startsWith("*")) {
                        final var suffix = value.substring(1);
                        excludes.add(s -> s.endsWith(suffix));
                    } else if (value.endsWith("*")) {
                        final var prefix = value.substring(0, value.length() - 1);
                        excludes.add(s -> s.startsWith(prefix));
                    } else if (value.startsWith("r/")) {
                        excludes.add(Pattern.compile(value.substring("r/".length())).asMatchPredicate());
                    } else {
                        excludes.add(s -> Objects.equals(s, value));
                    }
                    break;
                case "--renaming": // before=after
                    var paramsStart = value.lastIndexOf('?');
                    if (paramsStart < 0) {
                        paramsStart = value.length();
                    }
                    final var split = value.indexOf('=');
                    if (split < 0) {
                        throw new IllegalArgumentException("Invalid renaming config: <before>=<after>[?ext=java,ts] for example");
                    }
                    final Predicate<String> filter;
                    if (paramsStart == value.length()) {
                        filter = s -> true; // no filter
                    } else {
                        final var exts = value.indexOf("ext=");
                        if (exts < 0) {
                            throw new IllegalArgumentException("Only ext= is supported as remapping parameter for now");
                        }
                        final var extensions = Stream.of(value.substring(exts + "ext=".length()).split(",")).collect(toSet());
                        filter = s -> {
                            final var dot = s.lastIndexOf('.');
                            return dot > 0 && extensions.contains(s.substring(dot + 1));
                        };
                    }
                    final var source = value.substring(0, split);
                    final var target = value.substring(split + 1);
                    final Function<String, String> replacement;
                    if (source.startsWith("r/")) {
                        final var pattern = Pattern.compile(source.substring("r/".length()));
                        replacement = s -> pattern.matcher(s).replaceAll(target);
                    } else {
                        replacement = s -> s.replace(source, target);
                    }
                    renamings.add(new BiFunction<>() {
                        @Override
                        public String apply(final String filename, final String content) {
                            if (!filter.test(filename)) {
                                return content;
                            }
                            return replacement.apply(content);
                        }

                        @Override
                        public String toString() {
                            return source + " -> " + target;
                        }
                    });
                    break;
                default:
                    throw new IllegalArgumentException("unknown argument " + current);
            }
        }
        if (from == null || !Files.exists(from)) {
            throw new IllegalArgumentException("Missing from (" + from + ")");
        }
        if (to == null || to.equals(from) /*ensure we can check with ==*/) {
            to = from;
        }
        new Renamer(from, to, excludes, excludeFiltering, renamings, dryRun, renameFolders, overwrite).run();
    }

    @RequiredArgsConstructor
    private static class EndsWith implements Predicate<String> {
        private final String suffix;

        @Override
        public boolean test(final String s) {
            return s.endsWith(suffix);
        }

        @Override
        public String toString() {
            return "*." + suffix;
        }
    }

    @RequiredArgsConstructor
    private static class Equals implements Predicate<String> {
        private final String value;

        @Override
        public boolean test(final String s) {
            return Objects.equals(s, value);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
