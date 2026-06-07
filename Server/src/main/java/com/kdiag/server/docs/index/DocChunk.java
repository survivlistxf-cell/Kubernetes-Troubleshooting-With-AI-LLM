package com.kdiag.server.docs.index;

public record DocChunk(Long pageId, String url, String title, int chunkIdx, String text) {}
