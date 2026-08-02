package com.shrestaexclusive.platform.testsupport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.shrestaexclusive.platform.kv.KvReadThroughCache;
import java.util.List;
import java.util.function.Supplier;

public class ImmediateKvReadThroughCache implements KvReadThroughCache {

    @Override
    public <T> T getOrLoad(String cacheName, String cacheKey, List<String> tableNames, TypeReference<T> type, Supplier<T> loader) {
        return loader.get();
    }

    @Override
    public void putFresh(String cacheName, String cacheKey, List<String> tableNames, Object value) {
    }

    @Override
    public void invalidateTables(List<String> tableNames) {
    }
}
