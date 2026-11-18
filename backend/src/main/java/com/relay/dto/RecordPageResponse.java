package com.relay.dto;

import java.util.List;

public record RecordPageResponse(List<RecordResponse> data, Pagination pagination) {
    public record Pagination(int limit, String nextCursor) { }
}
