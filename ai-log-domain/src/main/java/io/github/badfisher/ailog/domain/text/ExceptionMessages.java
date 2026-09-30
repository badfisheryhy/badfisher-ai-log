package io.github.badfisher.ailog.domain.text;

import java.util.regex.Pattern;

/**
 * Formats exception summaries. This is not a sensitive-data sanitizer.
 */
public final class ExceptionMessages {

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n\\t]+");

    private ExceptionMessages() {
    }

    /**
     * Return the original message, or the exception type when it is blank.
     *
     * @param exception source exception
     * @return message with its original whitespace, or the simple type name
     */
    public static String messageOrType(Exception exception) {
        String message = exception.getMessage();
        return Texts.hasText(message) ? message : exception.getClass().getSimpleName();
    }

    /**
     * Flatten line breaks and tabs without imposing a storage length limit.
     *
     * @param exception source exception
     * @return trimmed single-line message, or the simple type name
     */
    public static String singleLine(Exception exception) {
        return LINE_BREAKS.matcher(messageOrType(exception)).replaceAll(" ").trim();
    }

    /**
     * Use the exception type when the message is blank, then flatten and truncate.
     * The caller owns the storage limit and any required sensitive-data masking.
     */
    public static String singleLine(Exception exception, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        String normalized = singleLine(exception);
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength);
    }
}
