package me.lendev.flow.audit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import me.lendev.flow.audit.store.AuditStore;
import me.lendev.flow.audit.store.AuditStore.Delivery;
import me.lendev.flow.audit.store.AuditStore.Lease;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class AuditWorkerTest {

    @Test
    void skipsLostClaimsAndResumesPollingAfterAStoreFailure() {
        var store = mock(AuditStore.class);
        var properties = new AuditProperties();
        var worker = new AuditWorker(store, properties);
        properties.setWorkerEnabled(false);
        worker.poll();
        verifyNoInteractions(store);

        properties.setWorkerEnabled(true);
        var lost = new Delivery(1, "lost", "op-1", "PENDING", 1, 0, null, null, 0, 0, 0);
        var available = new Delivery(2, "available", "op-2", "PENDING", 1, 0, null, null, 0, 0, 0);
        var lease = new Lease(2, "owned");
        when(store.due()).thenThrow(new TransientDataAccessResourceException("outbox unavailable"))
                .thenReturn(List.of(lost, available));
        when(store.claim(available)).thenReturn(lease);

        assertThatCode(worker::poll).doesNotThrowAnyException();
        worker.poll();

        var order = inOrder(store);
        order.verify(store).claim(lost);
        order.verify(store).claim(available);
        order.verify(store).consume(lease);
        order.verifyNoMoreInteractions();
    }
}
