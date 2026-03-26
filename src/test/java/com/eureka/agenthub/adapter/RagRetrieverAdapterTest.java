package com.eureka.agenthub.adapter;

import com.eureka.agenthub.model.RagHit;
import com.eureka.agenthub.service.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRetrieverAdapterTest {

    @Test
    void shouldDelegateRetrieveToRagService() {
        RagService ragService = mock(RagService.class);
        when(ragService.retrieve("hello", 5)).thenReturn(List.of(new RagHit("kb", "chunk", 0.9)));

        RagRetrieverAdapter adapter = new RagRetrieverAdapter(ragService);
        List<RagHit> hits = adapter.retrieve("hello", 5);

        assertEquals(1, hits.size());
        assertEquals("kb", hits.get(0).source());
        verify(ragService).retrieve("hello", 5);
    }
}
