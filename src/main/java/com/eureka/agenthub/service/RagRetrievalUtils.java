package com.eureka.agenthub.service;

import com.eureka.agenthub.model.RagHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class RagRetrievalUtils {

    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}\\u4e00-\\u9fa5]+");

    private RagRetrievalUtils() {
    }

    static List<String> splitRecursively(String text, int chunkSize, int chunkMinSize, int overlap) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        // 递归切分顺序：段落 -> 行 -> 句子 -> 词，最后兜底硬切。
        List<String> chunks = splitBySeparators(normalized, chunkSize,
                List.of("\n\n", "\n", "(?<=[。！？；;.!?])", "\\s+"));
        // 小碎片会降低检索质量，先做合并再做 overlap 扩展。
        List<String> merged = mergeSmallChunks(chunks, chunkMinSize, chunkSize);
        return applyOverlap(merged, overlap);
    }

    static List<RagHit> rerank(String query, List<RagHit> candidates, int finalTopK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(query);
        List<ScoredHit> scored = new ArrayList<>(candidates.size());
        for (RagHit hit : candidates) {
            double vectorScore = clamp01(hit.score());
            double coverageScore = coverageScore(queryTokens, tokenize(hit.content()));
            double lengthPenalty = lengthPenalty(hit.content());
            double finalScore = 0.75 * vectorScore + 0.25 * coverageScore - lengthPenalty;
            scored.add(new ScoredHit(hit, finalScore));
        }

        scored.sort(Comparator.comparingDouble(ScoredHit::finalScore).reversed());
        int need = Math.max(1, finalTopK);
        // 限制同 source 过度占位，避免 topK 全部来自同一文档片段。
        List<ScoredHit> selected = pickWithSourceDiversity(scored, need);
        selected.sort(Comparator.comparingDouble(ScoredHit::finalScore).reversed());

        List<RagHit> output = new ArrayList<>(selected.size());
        for (ScoredHit s : selected) {
            output.add(new RagHit(s.hit().source(), s.hit().content(), s.finalScore()));
        }
        return output;
    }

    private static List<String> splitBySeparators(String text, int chunkSize, List<String> separators) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }
        if (separators.isEmpty()) {
            // 递归分隔失败时，退化到定长切分，保证算法总能收敛。
            return hardSplit(text, chunkSize);
        }

        String separator = separators.get(0);
        List<String> restSeparators = separators.subList(1, separators.size());
        String[] units = text.split(separator);
        if (units.length <= 1) {
            return splitBySeparators(text, chunkSize, restSeparators);
        }

        List<String> packed = packUnits(units, chunkSize, separator);
        List<String> output = new ArrayList<>();
        for (String chunk : packed) {
            if (chunk.length() <= chunkSize) {
                output.add(chunk);
            } else {
                output.addAll(splitBySeparators(chunk, chunkSize, restSeparators));
            }
        }
        return output;
    }

    private static List<String> packUnits(String[] units, int chunkSize, String separator) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String joiner = "\\s+".equals(separator) ? " " : separator.replace("\\", "");

        for (String unit : units) {
            String trimmed = unit == null ? "" : unit.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int extra = current.isEmpty() ? trimmed.length() : joiner.length() + trimmed.length();
            if (!current.isEmpty() && current.length() + extra > chunkSize) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.isEmpty()) {
                current.append(trimmed);
            } else {
                current.append(joiner).append(trimmed);
            }
        }

        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static List<String> hardSplit(String text, int chunkSize) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    private static List<String> mergeSmallChunks(List<String> chunks, int chunkMinSize, int chunkMaxSize) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        int minSize = Math.max(1, chunkMinSize);
        List<String> out = new ArrayList<>();

        for (String chunk : chunks) {
            if (out.isEmpty()) {
                out.add(chunk);
                continue;
            }
            String prev = out.get(out.size() - 1);
            if ((chunk.length() < minSize || prev.length() < minSize)
                    && (prev.length() + 1 + chunk.length() <= chunkMaxSize)) {
                out.set(out.size() - 1, prev + "\n" + chunk);
            } else {
                out.add(chunk);
            }
        }
        return out;
    }

    private static List<String> applyOverlap(List<String> chunks, int overlap) {
        int safeOverlap = Math.max(0, overlap);
        if (safeOverlap == 0 || chunks.size() <= 1) {
            return chunks;
        }
        List<String> out = new ArrayList<>(chunks.size());
        String previous = null;
        for (String chunk : chunks) {
            if (previous == null) {
                out.add(chunk);
                previous = chunk;
                continue;
            }
            String tail = previous.substring(Math.max(0, previous.length() - safeOverlap));
            if (chunk.startsWith(tail)) {
                out.add(chunk);
            } else {
                // 通过上一个 chunk 的尾部补充上下文，降低语义边界断裂。
                out.add(tail + "\n" + chunk);
            }
            previous = chunk;
        }
        return out;
    }

    private static List<ScoredHit> pickWithSourceDiversity(List<ScoredHit> ranked, int need) {
        List<ScoredHit> selected = new ArrayList<>();
        Map<String, Integer> sourceCount = new HashMap<>();

        for (ScoredHit hit : ranked) {
            String source = hit.hit().source();
            int count = sourceCount.getOrDefault(source, 0);
            if (count >= 2) {
                continue;
            }
            selected.add(hit);
            sourceCount.put(source, count + 1);
            if (selected.size() >= need) {
                return selected;
            }
        }

        Set<ScoredHit> selectedSet = new LinkedHashSet<>(selected);
        for (ScoredHit hit : ranked) {
            if (selectedSet.contains(hit)) {
                continue;
            }
            selected.add(hit);
            if (selected.size() >= need) {
                break;
            }
        }
        return selected;
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] raw = TOKEN_SPLIT_PATTERN.split(text.toLowerCase(Locale.ROOT));
        Set<String> tokens = new HashSet<>();
        for (String t : raw) {
            if (t == null || t.isBlank()) {
                continue;
            }
            if (t.length() >= 2) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static double coverageScore(Set<String> queryTokens, Set<String> docTokens) {
        if (queryTokens.isEmpty() || docTokens.isEmpty()) {
            return 0.0;
        }
        int match = 0;
        for (String t : queryTokens) {
            if (docTokens.contains(t)) {
                match++;
            }
        }
        return (double) match / (double) queryTokens.size();
    }

    private static double lengthPenalty(String content) {
        int len = content == null ? 0 : content.length();
        if (len < 40) {
            return 0.08;
        }
        if (len > 1800) {
            return 0.10;
        }
        return 0.0;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    private record ScoredHit(RagHit hit, double finalScore) {
    }
}
