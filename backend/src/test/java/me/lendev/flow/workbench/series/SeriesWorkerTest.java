package me.lendev.flow.workbench.series;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class SeriesWorkerTest {

    @Test
    void failingRuleDoesNotPreventLaterRulesFromRunning() {
        var service = mock(SeriesService.class);
        when(service.due(32)).thenReturn(List.of(1L, 2L, 3L));
        when(service.generate(1L)).thenThrow(new TransientDataAccessResourceException("generation failed"));
        when(service.generate(2L)).thenThrow(new TransientDataAccessResourceException("generation failed again"));
        doThrow(new TransientDataAccessResourceException("could not persist retry")).when(service).deferFailure(2L);

        new SeriesWorker(service).tick();

        var order = inOrder(service);
        order.verify(service).generate(1L);
        order.verify(service).deferFailure(1L);
        order.verify(service).generate(2L);
        order.verify(service).deferFailure(2L);
        order.verify(service).generate(3L);
    }
}
