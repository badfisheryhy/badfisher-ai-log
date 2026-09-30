package io.github.badfisher.ailog.analysis.issue;

import io.github.badfisher.ailog.domain.issue.TriggerChannel;
import io.github.badfisher.ailog.domain.log.LogEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TriggerChannelClassifierTest {

    @Test
    void recognizesGenericSchedulerWithoutVendorConfiguration() {
        LogEvent event = mock(LogEvent.class);
        when(event.getThreadName()).thenReturn("scheduling-1");
        assertThat(new TriggerChannelClassifier().classify(event)).isEqualTo(TriggerChannel.SCHEDULER);
    }

    @Test
    void vendorEvidenceRequiresAdapterInjection() {
        LogEvent event = mock(LogEvent.class);
        when(event.getLoggerClass()).thenReturn("com.xxl.job.core.thread.JobThread");
        assertThat(new TriggerChannelClassifier().classify(event)).isEqualTo(TriggerChannel.UNKNOWN);
        TriggerChannelClassifier classifier = new TriggerChannelClassifier(candidate -> candidate == event);
        assertThat(classifier.classify(event)).isEqualTo(TriggerChannel.SCHEDULER);
    }

    @Test
    void keepsHttpAndMessageQueueClassification() {
        LogEvent event = mock(LogEvent.class);
        when(event.getThreadName()).thenReturn("http-nio-8080-exec-1");
        assertThat(new TriggerChannelClassifier().classify(event)).isEqualTo(TriggerChannel.HTTP);
        when(event.getLoggerClass()).thenReturn("example.mq.OrderReceiver");
        assertThat(new TriggerChannelClassifier().classify(event)).isEqualTo(TriggerChannel.MQ);
    }
}
