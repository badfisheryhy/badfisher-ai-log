package io.github.badfisher.ailog.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import io.github.badfisher.ailog.bootstrap.controller.response.GroupQueryResponse;
import io.github.badfisher.ailog.bootstrap.controller.response.IssueGroupView;
import io.github.badfisher.ailog.persistence.analysis.entity.AiLogIssueGroupEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiViewContractTest {
    @Test
    void detailDoesNotExposeInternalAiExecutionState() throws Exception {
        AiLogIssueGroupEntity entity = new AiLogIssueGroupEntity();
        entity.setId(42L);
        entity.setAiStatus("RUNNING");
        entity.setCurrentAiItemId(100L);
        entity.setActiveAiItemId(200L);
        entity.setLockVersion(7);
        GroupQueryResponse.Detail detail = new GroupQueryResponse.Detail();
        detail.setGroup(IssueGroupView.from(entity));
        var group = new ObjectMapper().valueToTree(detail).get("group");
        assertThat(group.get("id").asLong()).isEqualTo(42L);
        assertThat(group.get("currentAiItemId").asLong()).isEqualTo(100L);
        assertThat(group.has("activeAiItemId")).isFalse();
        assertThat(group.has("lockVersion")).isFalse();
    }
}
