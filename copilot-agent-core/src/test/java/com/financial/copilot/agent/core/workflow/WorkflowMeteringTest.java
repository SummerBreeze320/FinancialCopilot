package com.financial.copilot.agent.core.workflow;

import com.financial.copilot.agent.core.agents.*;
import com.financial.copilot.agent.core.pipeline.*;
import com.financial.copilot.agent.core.memory.*;
import com.financial.copilot.agent.core.llm.dto.LlmResponse;
import com.financial.copilot.domain.fund.port.FundDataPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Sinks;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowMeteringTest {
    @Test
    void cancellationAfterContentStillDrainsUsageAndComparisonReceivesMeter() throws Exception {
        var decomposer = mock(TaskDecomposer.class);
        var comparator = mock(ComparatorAgent.class);
        var synthesizer = mock(ReportSynthesizer.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var settled = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        Consumer<LlmResponse> meter = usage -> settled.countDown();
        when(decomposer.decompose(anyString(), anyBoolean(), same(meter))).thenReturn(ExecutionPlan.builder()
                .steps(List.of(SubTask.builder().taskType("COMPARISON").build(),
                        SubTask.builder().taskType("SYNTHESIS").build())).build());
        when(comparator.compareFunds(anyString(), anyString(), same(meter))).thenReturn("facts");
        Sinks.Many<String> upstream = Sinks.many().unicast().onBackpressureBuffer();
        when(synthesizer.synthesizeStream(anyString(), anyString(), anyBoolean(), same(meter)))
                .thenReturn(upstream.asFlux().doOnSubscribe(s -> started.countDown())
                        .doOnComplete(() -> meter.accept(LlmResponse.builder().build())));
        doAnswer(inv -> { completed.countDown(); return null; }).when(publisher).publishEvent(any(org.springframework.context.ApplicationEvent.class));
        var workflow = new FinancialResearchWorkflow(decomposer, mock(ScreenerAgent.class), mock(AnalyzerAgent.class),
                comparator, synthesizer, mock(FundDataPort.class), mock(ShortTermMemoryService.class),
                mock(LongTermMemoryService.class), mock(MemoryRefinementTask.class), publisher);
        var content = new CountDownLatch(1);
        var subscription = workflow.executePipelineStream("session", "prompt", false, null, meter)
                .subscribe(event -> { if ("CONTENT".equals(event.getType())) content.countDown(); });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        upstream.tryEmitNext("partial report");
        assertTrue(content.await(5, TimeUnit.SECONDS));
        subscription.dispose();
        assertEquals(Sinks.EmitResult.OK, upstream.tryEmitComplete());
        assertTrue(settled.await(5, TimeUnit.SECONDS));
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        verify(comparator).compareFunds(anyString(), anyString(), same(meter));
    }
}
