package com.votivo.user_service.image;

public record ImageData(byte[] bytes, ImageFormat format) {
    public long size() {
        return bytes.length;
    }
}
