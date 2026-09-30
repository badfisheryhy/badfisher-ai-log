package io.github.badfisher.ailog.domain.aggregation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.Getter;

/** 有界流式聚合器一次 drain 的不可变输出。 */
@Getter
public final class AggregateBatch {
    private final List<AggregatedError> errors;
    private final long occurrenceCount;

    public AggregateBatch(List<AggregatedError> aggregatedErrors) {
        errors = Collections.unmodifiableList(
                new ArrayList<AggregatedError>(aggregatedErrors));
        long count = 0L;
        for (AggregatedError error : aggregatedErrors) {
            count += error.getOccurrenceCount();
        }
        occurrenceCount = count;
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }
}
