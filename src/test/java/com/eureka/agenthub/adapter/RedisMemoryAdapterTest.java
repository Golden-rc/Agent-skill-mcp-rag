package com.eureka.agenthub.adapter;

import com.eureka.agenthub.model.ChatMessage;
import com.eureka.agenthub.service.MemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisMemoryAdapterTest {

    @Test
    void shouldDelegateMemoryOperations() {
        MemoryService memoryService = mock(MemoryService.class);
        ChatMessage m1 = new ChatMessage("user", "hello");
        when(memoryService.loadHistory("s1")).thenReturn(List.of(m1));

        RedisMemoryAdapter adapter = new RedisMemoryAdapter(memoryService);
        List<ChatMessage> history = adapter.loadHistory("s1");
        adapter.append("s1", new ChatMessage("assistant", "ok"));

        assertEquals(1, history.size());
        assertEquals("hello", history.get(0).content());
        verify(memoryService).loadHistory("s1");
        verify(memoryService).append("s1", new ChatMessage("assistant", "ok"));
    }
}
