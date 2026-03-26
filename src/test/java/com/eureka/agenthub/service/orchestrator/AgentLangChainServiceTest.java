package com.eureka.agenthub.service.orchestrator;

import com.eureka.agenthub.port.ToolExecutorPort;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLangChainServiceTest {

    @Test
    void shouldInvokeWebFetchToolWithBoundedMaxChars() {
        ToolExecutorPort toolExecutorPort = mock(ToolExecutorPort.class);
        AgentLangChainService.ToolCallCollector collector = new AgentLangChainService.ToolCallCollector();
        AgentLangChainService.AgentTools tools = new AgentLangChainService.AgentTools(toolExecutorPort, collector);

        when(toolExecutorPort.callTool(eq("web_fetch"), eq(Map.of("url", "https://example.com", "maxChars", 10000))))
                .thenReturn("ok");

        String output = tools.webFetch("https://example.com", 50000);

        assertEquals("ok", output);
        assertTrue(collector.toolCalls().contains("web_fetch"));
        verify(toolExecutorPort).callTool("web_fetch", Map.of("url", "https://example.com", "maxChars", 10000));
    }

    @Test
    void shouldInvokeTranslateToolWithAutoSourceByDefault() {
        ToolExecutorPort toolExecutorPort = mock(ToolExecutorPort.class);
        AgentLangChainService.ToolCallCollector collector = new AgentLangChainService.ToolCallCollector();
        AgentLangChainService.AgentTools tools = new AgentLangChainService.AgentTools(toolExecutorPort, collector);

        when(toolExecutorPort.callTool(eq("translate_text"), eq(Map.of("text", "hello", "targetLang", "zh-CN", "sourceLang", "auto"))))
                .thenReturn("翻译结果");

        String output = tools.translateText("hello", "zh-CN", "");

        assertEquals("翻译结果", output);
        assertTrue(collector.toolCalls().contains("translate_text"));
        verify(toolExecutorPort).callTool("translate_text", Map.of("text", "hello", "targetLang", "zh-CN", "sourceLang", "auto"));
    }

    @Test
    void shouldDeduplicateSameToolCallInSingleAgentRun() {
        ToolExecutorPort toolExecutorPort = mock(ToolExecutorPort.class);
        AgentLangChainService.ToolCallCollector collector = new AgentLangChainService.ToolCallCollector();
        AgentLangChainService.AgentTools tools = new AgentLangChainService.AgentTools(toolExecutorPort, collector);

        when(toolExecutorPort.callTool(eq("translate_text"), eq(Map.of("text", "hello", "targetLang", "zh-CN", "sourceLang", "auto"))))
                .thenReturn("翻译结果");

        String first = tools.translateText("hello", "zh-CN", "auto");
        String second = tools.translateText("hello", "zh-CN", "auto");

        assertEquals("翻译结果", first);
        assertEquals("翻译结果", second);
        assertEquals(1, collector.toolCalls().size());
        verify(toolExecutorPort, times(1))
                .callTool("translate_text", Map.of("text", "hello", "targetLang", "zh-CN", "sourceLang", "auto"));
    }

    @Test
    void shouldNotMarkNormalWebContentAsToolError() {
        AgentLangChainService.ToolCallCollector collector = new AgentLangChainService.ToolCallCollector();

        collector.record("web_fetch", "网页抓取结果\n内容: 多款银行理财发行失败，这是新闻标题");

        assertTrue(collector.toolErrors().isEmpty());
    }

    @Test
    void shouldMarkExplicitToolFailureAsError() {
        AgentLangChainService.ToolCallCollector collector = new AgentLangChainService.ToolCallCollector();

        collector.record("web_fetch", "网页抓取失败: unknown certificate verification error");

        assertEquals(1, collector.toolErrors().size());
    }
}
