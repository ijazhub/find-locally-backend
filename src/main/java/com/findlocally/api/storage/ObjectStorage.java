package com.findlocally.api.storage;

import java.io.InputStream;

public interface ObjectStorage {
    void upload(String key, String contentType, long size, InputStream content);
    void delete(String key);
    void verify(String key, String contentType, long size);
}
