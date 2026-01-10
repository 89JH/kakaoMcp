package com.future.kakaomcp.domain.doc;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * - 1차: 문단(\n\n) 기준으로 쪼갠 뒤 목표 길이(targetChars)에 맞춰 pack
 * - 2차: 문단이 너무 길면 문장 단위로 재분해
 * - overlapChars 만큼 앞 chunk 끝을 다음 chunk 앞에 덧붙여 문맥 보강
 */
@Service
public class DefaultChunkingService implements ChunkingService {

    private final int targetChars;
    private final int overlapChars;
    private final int minChunkChars;

    public DefaultChunkingService(
            @Value("${app.summarize.chunk.target-chars:4500}") int targetChars,
            @Value("${app.summarize.chunk.overlap-chars:300}") int overlapChars,
            @Value("${app.summarize.chunk.min-chars:1200}") int minChunkChars
    ) {
        this.targetChars = Math.max(800, targetChars);
        this.overlapChars = Math.max(0, overlapChars);
        this.minChunkChars = Math.max(200, minChunkChars);
    }

    @Override
    public List<String> chunk(String text) {
        if (text == null) return List.of();
        String normalized = text.trim();
        if (normalized.isEmpty()) return List.of();

        List<String> paragraphs = splitParagraphs(normalized);

        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String para : paragraphs) {
            if (para.isBlank()) continue;

            // 문단이 너무 길면 문장 단위로 쪼개서 pack
            if (para.length() > targetChars) {
                flushIfNeeded(chunks, buf);
                packSentencesIntoChunks(chunks, para);
                continue;
            }

            // 현재 버퍼에 더하면 너무 커지는 경우 flush
            if (buf.length() > 0 && buf.length() + 2 + para.length() > targetChars) {
                flushIfNeeded(chunks, buf);
            }

            if (buf.length() > 0) buf.append("\n\n");
            buf.append(para);
        }

        flushIfNeeded(chunks, buf);

        // overlap 적용
        if (overlapChars > 0 && chunks.size() > 1) {
            List<String> overlapped = new ArrayList<>(chunks.size());
            overlapped.add(chunks.get(0));
            for (int i = 1; i < chunks.size(); i++) {
                String prev = chunks.get(i - 1);
                String cur = chunks.get(i);
                String overlap = tail(prev, overlapChars);
                overlapped.add((overlap.isBlank() ? "" : overlap + "\n\n") + cur);
            }
            return overlapped;
        }

        return chunks;
    }

    private List<String> splitParagraphs(String text) {
        // 2개 이상의 개행은 문단 경계로 취급
        String[] arr = text.split("\\n\\s*\\n+");
        List<String> out = new ArrayList<>(arr.length);
        for (String s : arr) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void packSentencesIntoChunks(List<String> chunks, String longParagraph) {
        List<String> sentences = splitSentences(longParagraph);
        StringBuilder buf = new StringBuilder();

        for (String sent : sentences) {
            if (sent.isBlank()) continue;

            if (buf.length() > 0 && buf.length() + 1 + sent.length() > targetChars) {
                flushIfNeeded(chunks, buf);
            }
            if (buf.length() > 0) buf.append(' ');
            buf.append(sent.trim());
        }
        flushIfNeeded(chunks, buf);
    }

    private List<String> splitSentences(String text) {
        // 한국어/영어 문서 모두 적당히 동작하도록 Locale.KOREAN 사용
        BreakIterator bi = BreakIterator.getSentenceInstance(Locale.KOREAN);
        bi.setText(text);
        List<String> out = new ArrayList<>();
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String s = text.substring(start, end).trim();
            if (!s.isEmpty()) out.add(s);
        }
        if (out.isEmpty()) out.add(text.trim());
        return out;
    }

    private void flushIfNeeded(List<String> chunks, StringBuilder buf) {
        if (buf.length() == 0) return;
        String chunk = buf.toString().trim();
        buf.setLength(0);

        if (chunk.isEmpty()) return;

        // 너무 짧으면 이전 chunk에 붙여서 조각화 과다 방지
        if (!chunks.isEmpty() && chunk.length() < minChunkChars) {
            String merged = (chunks.get(chunks.size() - 1) + "\n\n" + chunk).trim();
            chunks.set(chunks.size() - 1, merged);
            return;
        }

        chunks.add(chunk);
    }

    private String tail(String s, int maxChars) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= maxChars) return t;
        return t.substring(t.length() - maxChars);
    }
}
