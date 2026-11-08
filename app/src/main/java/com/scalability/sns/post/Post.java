package com.scalability.sns.post;

import java.time.Instant;

public record Post(long id, long userId, String content, int likeCount, Instant createdAt) {
}
