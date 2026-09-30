package io.github.badfisher.ailog.analysis.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.analysis.group.ErrorFilter.MatchType;
import io.github.badfisher.ailog.domain.sync.LogChannel;

class ErrorFilterTest {

    private final ErrorFilter filter = new ErrorFilter();

    @Test
    void acceptsParsedErrorLevelAsStrictError() {
        assertThat(filter.classify("ERROR", LogChannel.ERROR))
                .isEqualTo(MatchType.STRICT_ERROR);
    }

    @Test
    void rejectsParsedInfoAndWarnEvenWhenMessageCouldContainError() {
        assertThat(filter.classify("INFO", LogChannel.ERROR))
                .isEqualTo(MatchType.REJECTED);
        assertThat(filter.classify("WARN", LogChannel.ERROR))
                .isEqualTo(MatchType.REJECTED);
    }

    @Test
    void allowsUnknownLevelFallbackOnlyForDedicatedErrorFile() {
        assertThat(filter.classify("UNKNOWN", LogChannel.ERROR))
                .isEqualTo(MatchType.FALLBACK_ERROR);
        assertThat(filter.classify(null, LogChannel.ERROR))
                .isEqualTo(MatchType.FALLBACK_ERROR);
        assertThat(filter.classify("UNKNOWN", LogChannel.APPLICATION))
                .isEqualTo(MatchType.REJECTED);
    }
}
