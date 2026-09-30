package io.github.badfisher.ailog.domain.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.badfisher.ailog.domain.analysis.ActionableIssueSnapshot;
import io.github.badfisher.ailog.domain.analysis.RepresentativeEvent;
import lombok.Getter;

/** 一个 AI Item 的任务内 Issue 快照和有限代表样本。 */
@Getter
public final class AiTaskEvidenceContext {
    private final ActionableIssueSnapshot snapshot;
    private final List<RepresentativeEvent> representativeEvents;

    public AiTaskEvidenceContext(ActionableIssueSnapshot issueSnapshot,
            List<RepresentativeEvent> events) {
        if (issueSnapshot == null || events == null) {
            throw new IllegalArgumentException("AI task evidence context must not contain null");
        }
        snapshot = issueSnapshot;
        representativeEvents = Collections.unmodifiableList(
                new ArrayList<RepresentativeEvent>(events));
    }

}
