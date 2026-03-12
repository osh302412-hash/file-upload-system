package com.example.upload.dto;

public class CreateSessionRequest {
    private String filename;
    private Long totalSize;
    private Integer chunkSize;

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Long getTotalSize() { return totalSize; }
    public void setTotalSize(Long totalSize) { this.totalSize = totalSize; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
}
