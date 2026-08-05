package com.webtoonmap.mobile.data;

public final class AvseeVideo {
    public final long id;
    public final String title;
    public final String filePath;
    public final String thumbnailPath;
    public final String pageUrl;
    public final String tags;
    public final String actors;
    public final String description;
    public final String createdAt;
    public final long sizeBytes;

    public AvseeVideo(long id, String title, String filePath, String thumbnailPath,
                      String pageUrl, String tags, String actors, String description,
                      String createdAt, long sizeBytes) {
        this.id = id;
        this.title = title;
        this.filePath = filePath;
        this.thumbnailPath = thumbnailPath;
        this.pageUrl = pageUrl;
        this.tags = tags;
        this.actors = actors;
        this.description = description;
        this.createdAt = createdAt;
        this.sizeBytes = sizeBytes;
    }
}
