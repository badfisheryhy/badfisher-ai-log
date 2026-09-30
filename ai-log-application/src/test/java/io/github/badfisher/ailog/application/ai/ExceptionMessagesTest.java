package io.github.badfisher.ailog.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.domain.text.ExceptionMessages;

/** Shared failure summaries retain each caller's existing storage contract. */
class ExceptionMessagesTest {

    @Test
    void preservesRawWhitespaceWhenOnlyMessageFallbackIsRequested() {
        String message = "  first\r\n\tsecond  ";

        assertThat(ExceptionMessages.messageOrType(new IllegalStateException(message)))
                .isEqualTo(message);
        assertThat(ExceptionMessages.messageOrType(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
        assertThat(ExceptionMessages.messageOrType(new IllegalArgumentException(" \r\n\t ")))
                .isEqualTo("IllegalArgumentException");
    }

    @Test
    void leavesUnboundedSingleLineMessagesUntruncated() {
        String body = String.join("", Collections.nCopies(600, "x"));
        Exception failure = new IllegalStateException("  " + body + "\r\n\ttail  ");

        assertThat(ExceptionMessages.singleLine(failure)).isEqualTo(body + " tail");
        assertThat(ExceptionMessages.singleLine(failure, 500))
                .isEqualTo(body.substring(0, 500));
        assertThat(ExceptionMessages.singleLine(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void preservesTheExistingNonAsciiWhitespaceContract() {
        Exception failure = new IllegalArgumentException("\u2003");

        assertThat(ExceptionMessages.messageOrType(failure)).isEqualTo("\u2003");
        assertThat(ExceptionMessages.singleLine(failure)).isEqualTo("\u2003");
        assertThat(ExceptionMessages.singleLine(failure, 500)).isEqualTo("\u2003");
    }

    @Test
    void fallsBackToTheExceptionTypeForMissingOrBlankMessages() {
        assertThat(ExceptionMessages.singleLine(new IllegalStateException(), 500))
                .isEqualTo("IllegalStateException");
        assertThat(ExceptionMessages.singleLine(new IllegalArgumentException(" \r\n\t "), 500))
                .isEqualTo("IllegalArgumentException");
    }

    @Test
    void flattensOnlyLineBreaksAndTabsBeforeTruncating() {
        Exception failure = new IllegalStateException("  first\r\n\tsecond  third  ");

        assertThat(ExceptionMessages.singleLine(failure, 500)).isEqualTo("first second  third");
        assertThat(ExceptionMessages.singleLine(failure, 12)).isEqualTo("first second");
    }

    @Test
    void preservesExactBoundariesAndTruncatesTheFallbackToo() {
        assertThat(ExceptionMessages.singleLine(new IllegalStateException("abc"), 3))
                .isEqualTo("abc");
        assertThat(ExceptionMessages.singleLine(new IllegalStateException("abcd"), 3))
                .isEqualTo("abc");
        assertThat(ExceptionMessages.singleLine(new IllegalStateException(), 7))
                .isEqualTo("Illegal");
    }

    @Test
    void keepsTheOriginalExceptionAndMessageUnchanged() {
        Exception cause = new IllegalArgumentException("cause");
        Exception failure = new IllegalStateException("  first\nsecond  ", cause);

        ExceptionMessages.singleLine(failure, 5);

        assertThat(failure.getMessage()).isEqualTo("  first\nsecond  ");
        assertThat(failure.getCause()).isSameAs(cause);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> ExceptionMessages.singleLine(new Exception("message"), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExceptionMessages.singleLine(new Exception("message"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
