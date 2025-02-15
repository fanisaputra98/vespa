// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.task.util.file;

import com.yahoo.vespa.hosted.node.admin.component.TaskContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper class to find and list or deleteRecursively files and directories. Follows the general syntax of command line
 * tool `find`.
 *
 * @author freva
 */
public class FileFinder {
    private static final Logger logger = Logger.getLogger(FileFinder.class.getName());

    private final Path basePath;
    private Predicate<FileAttributes> matcher;
    private int maxDepth = Integer.MAX_VALUE;

    public FileFinder(Path basePath, Predicate<FileAttributes> initialMatcher) {
        this.basePath = basePath;
        this.matcher = initialMatcher;
    }

    /** Creates a FileFinder at the given basePath  */
    public static FileFinder from(Path basePath) {
        return new FileFinder(basePath, attrs -> true);
    }

    /** Creates a FileFinder at the given basePath that will match all files */
    public static FileFinder files(Path basePath) {
        return new FileFinder(basePath, FileAttributes::isRegularFile);
    }


    /** Creates a FileFinder at the given basePath that will match all directories */
    public static FileFinder directories(Path basePath) {
        return new FileFinder(basePath, FileAttributes::isDirectory);
    }


    /**
     * Predicate that will be used to match files and directories under the base path.
     *
     * NOTE: Consequtive calls to this method are ANDed (this include the initial filter from
     * {@link #files(Path)} or {@link #directories(Path)}.
     */
    public FileFinder match(Predicate<FileAttributes> matcher) {
        this.matcher = this.matcher.and(matcher);
        return this;
    }

    /**
     * Maximum depth (relative to basePath) where contents should be matched with the given filters.
     * Default is unlimited.
     */
    public FileFinder maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    /**
     * Recursively deletes all matching elements
     *
     * @return true iff anything was matched and deleted
     */
    public boolean deleteRecursively(TaskContext context) {
        List<Path> deletedPaths = new ArrayList<>();

        try {
            forEach(attributes -> {
                if (attributes.unixPath().deleteRecursively()) {
                    deletedPaths.add(attributes.path());
                }
            });
        } finally {
            if (deletedPaths.size() > 20) {
                context.log(logger, "Deleted " + deletedPaths.size() + " paths under " + basePath);
            } else if (deletedPaths.size() > 0) {
                List<Path> paths = deletedPaths.stream()
                        .map(basePath::relativize)
                        .sorted()
                        .collect(Collectors.toList());
                context.log(logger, "Deleted these paths in " + basePath + ": " + paths);
            }
        }

        return deletedPaths.size() > 0;
    }

    public List<FileAttributes> list() {
        LinkedList<FileAttributes> list = new LinkedList<>();
        forEach(list::add);
        return list;
    }

    public Stream<FileAttributes> stream() {
        return list().stream();
    }

    public void forEachPath(Consumer<Path> action) {
        forEach(attributes -> action.accept(attributes.path()));
    }

    /** Applies a given consumer to all the matching {@link FileFinder.FileAttributes} */
    public void forEach(Consumer<FileAttributes> action) {
        applyForEachToMatching(basePath, matcher, maxDepth, action);
    }
    

    /**
     * <p> This method walks a file tree rooted at a given starting file. The file tree traversal is
     * <em>depth-first</em>: The filter function is applied in pre-order (NLR), but the given
     * {@link Consumer} will be called in post-order (LRN).
     */
    private void applyForEachToMatching(Path basePath, Predicate<FileAttributes> matcher,
                                        int maxDepth, Consumer<FileAttributes> action) {
        try {
            // Only need to traverse as deep as we want to match, unless we want to match everything in directories
            // already matched
            Files.walkFileTree(basePath, Collections.emptySet(), maxDepth, new SimpleFileVisitor<>() {
                private final Stack<FileAttributes> matchingDirectoryStack = new Stack<>();
                private int currentLevel = -1;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    currentLevel++;

                    FileAttributes attributes = new FileAttributes(dir, attrs);
                    if (currentLevel > 0 && matcher.test(attributes))
                        matchingDirectoryStack.push(attributes);

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // When we find a directory at the max depth given to Files.walkFileTree, the directory
                    // will be passed to visitFile() rather than (pre|post)VisitDirectory
                    if (attrs.isDirectory()) {
                        preVisitDirectory(file, attrs);
                        return postVisitDirectory(file, null);
                    }

                    FileAttributes attributes = new FileAttributes(file, attrs);
                    if (matcher.test(attributes))
                        action.accept(attributes);

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (!matchingDirectoryStack.isEmpty())
                        action.accept(matchingDirectoryStack.pop());

                    currentLevel--;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) {

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    // Ideally, we would reuse the FileAttributes in this package, but unfortunately we only get
    // BasicFileAttributes and not PosixFileAttributes from FileVisitor
    public static class FileAttributes {
        private final Path path;
        private final BasicFileAttributes attributes;

        FileAttributes(Path path, BasicFileAttributes attributes) {
            this.path = path;
            this.attributes = attributes;
        }

        public Path path() { return path; }
        public UnixPath unixPath() { return new UnixPath(path); }
        public String filename() { return path.getFileName().toString(); }
        public Instant lastModifiedTime() { return attributes.lastModifiedTime().toInstant(); }
        public boolean isRegularFile() { return attributes.isRegularFile(); }
        public boolean isDirectory() { return attributes.isDirectory(); }
        public long size() { return attributes.size(); }
    }


    // Filters
    public static Predicate<FileAttributes> olderThan(Duration duration) {
        return attrs -> Duration.between(attrs.lastModifiedTime(), Instant.now()).compareTo(duration) > 0;
    }

    public static Predicate<FileAttributes> youngerThan(Duration duration) {
        return olderThan(duration).negate();
    }

    public static Predicate<FileAttributes> largerThan(long sizeInBytes) {
        return attrs -> attrs.size() > sizeInBytes;
    }

    public static Predicate<FileAttributes> smallerThan(long sizeInBytes) {
        return largerThan(sizeInBytes).negate();
    }

    public static Predicate<FileAttributes> nameMatches(Pattern pattern) {
        return attrs -> pattern.matcher(attrs.filename()).matches();
    }

    public static Predicate<FileAttributes> nameStartsWith(String string) {
        return attrs -> attrs.filename().startsWith(string);
    }

    public static Predicate<FileAttributes> nameEndsWith(String string) {
        return attrs -> attrs.filename().endsWith(string);
    }

    public static Predicate<FileAttributes> all() {
        return attrs -> true;
    }
}
