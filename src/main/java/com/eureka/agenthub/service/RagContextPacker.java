package com.eureka.agenthub.service;

import com.eureka.agenthub.model.RagHit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG 上下文打包器。
 * <p>
 * 目标：在不引入复杂 token 计算的前提下，做去重、截断和预算控制，
 * 避免把冗余大段文本直接塞进 prompt 导致“中段丢失”和上下文噪声。
 */
final class RagContextPacker {

    private RagContextPacker() {
    }

    static List<RagHit> pack(List<RagHit> hits, int maxTotalChars, int maxChunkChars) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        int totalBudget = Math.max(500, maxTotalChars);
        int chunkBudget = Math.max(120, maxChunkChars);

        List<RagHit> packed = new ArrayList<>();
        Set<String> dedupeKeys = new LinkedHashSet<>();
        int used = 0;

        for (RagHit hit : hits) {
            String content = normalize(hit.content());
            if (content.isEmpty()) {
                continue;
            }

            String dedupeKey = dedupeKey(content);
            if (dedupeKeys.contains(dedupeKey)) {
                continue;
            }

            String clipped = clip(content, chunkBudget);
            int nextCost = clipped.length();
            if (!packed.isEmpty() && used + nextCost > totalBudget) {
                break;
            }

            packed.add(new RagHit(hit.source(), clipped, hit.score()));
            dedupeKeys.add(dedupeKey);
            used += nextCost;
        }

        return packed;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String clip(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private static String dedupeKey(String content) {
        // 用前缀做轻量去重键，避免高成本相似度计算。
        String prefix = content.length() <= 80 ? content : content.substring(0, 80);
        return prefix.toLowerCase(Locale.ROOT);
    }
}
