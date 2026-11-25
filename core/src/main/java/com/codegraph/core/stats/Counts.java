package com.codegraph.core.stats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * File and line counts for a set of files. {@code lines = code + comment + blank};
 * binary files add to {@code files} and {@code bytes} only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Counts {
    public long files;
    public long lines;
    public long code;
    public long comment;
    public long blank;
    public long bytes;

    public Counts() {}

    public Counts(long files, long lines, long code, long comment, long blank, long bytes) {
        this.files = files;
        this.lines = lines;
        this.code = code;
        this.comment = comment;
        this.blank = blank;
        this.bytes = bytes;
    }

    /** Adds other's counts to these, and returns this. */
    public Counts add(Counts other) {
        files += other.files;
        lines += other.lines;
        code += other.code;
        comment += other.comment;
        blank += other.blank;
        bytes += other.bytes;
        return this;
    }

    public Counts copy() {
        return new Counts(files, lines, code, comment, blank, bytes);
    }

    /** The value of a metric by name: files, lines, code, comment, blank or bytes. */
    public long metric(String name) {
        return switch (name == null ? "code" : name) {
            case "files" -> files;
            case "lines" -> lines;
            case "comment" -> comment;
            case "blank" -> blank;
            case "bytes" -> bytes;
            default -> code;
        };
    }

    @Override
    public String toString() {
        return files + " files, " + lines + " lines (" + code + " code, " + comment + " comment, " + blank + " blank), " + bytes + " bytes";
    }
}
