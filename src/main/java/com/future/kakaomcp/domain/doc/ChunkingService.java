package com.future.kakaomcp.domain.doc;

import java.util.List;

/**
 * 긴 문서를 요약 품질이 좋아지도록 적당한 크기의 조각(Chunk)으로 분할한다.
 */
public interface ChunkingService {

    List<String> chunk(String text);
}
