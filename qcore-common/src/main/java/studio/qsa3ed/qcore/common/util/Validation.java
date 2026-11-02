package studio.qsa3ed.qcore.common.util;

import java.util.Collection;
import java.util.regex.Pattern;

/** Small, dependency-free validation helpers used at every trust boundary. */
public final class Validation {

    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,128}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private Validation() {
    }

    /** Ensures a value is present and not blank. */
    public static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** Ensures a value, when present, does not exceed a length. */
    public static String maxLength(String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
        return value;
    }

    /** Ensures a node id uses the documented character set. */
    public static String requireNodeId(String value, String field) {
        requireNonBlank(value, field);
        if (!NODE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match " + NODE_ID.pattern());
        }
        return value;
    }

    /** Ensures a Minecraft username uses the documented character set. */
    public static String requireUsername(String value, String field) {
        requireNonBlank(value, field);
        if (!USERNAME.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match " + USERNAME.pattern());
        }
        return value;
    }

    /** Ensures a number is inside an inclusive range. */
    public static <T extends Comparable<T>> T requireRange(T value, T min, T max, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(field + " must be within [" + min + ", " + max + "]");
        }
        return value;
    }

    /** Ensures a collection is present and not empty. */
    public static <T> Collection<T> requireNonEmpty(Collection<T> value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value;
    }
}
