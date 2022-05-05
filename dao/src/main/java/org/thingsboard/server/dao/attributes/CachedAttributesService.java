/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.attributes;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thingsboard.server.common.data.CacheConstants;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.stats.DefaultCounter;
import org.thingsboard.server.common.stats.StatsFactory;
import org.thingsboard.server.dao.cache.CacheExecutorService;
import org.thingsboard.server.cache.TbCacheTransaction;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.dao.service.Validator;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.thingsboard.server.dao.attributes.AttributeUtils.validate;

@Service
@ConditionalOnProperty(prefix = "cache.attributes", value = "enabled", havingValue = "true")
@Primary
@Slf4j
public class CachedAttributesService implements AttributesService {
    private static final String STATS_NAME = "attributes.cache";
    public static final String LOCAL_CACHE_TYPE = "caffeine";

    private final AttributesDao attributesDao;
    private final CacheExecutorService cacheExecutorService;
    private final DefaultCounter hitCounter;
    private final DefaultCounter missCounter;
    private final TbTransactionalCache cache;
    private Executor cacheExecutor;

    @Value("${cache.type}")
    private String cacheType;

    public CachedAttributesService(AttributesDao attributesDao,
                                   StatsFactory statsFactory,
                                   CacheExecutorService cacheExecutorService,
                                   TbTransactionalCache cache) {
        this.attributesDao = attributesDao;
        this.cacheExecutorService = cacheExecutorService;
        this.cache = cache;

        this.hitCounter = statsFactory.createDefaultCounter(STATS_NAME, "result", "hit");
        this.missCounter = statsFactory.createDefaultCounter(STATS_NAME, "result", "miss");
    }

    @PostConstruct
    public void init() {
        this.cacheExecutor = getExecutor(cacheType, cacheExecutorService);
    }

    /**
     * Will return:
     * - for the <b>local</b> cache type (cache.type="coffeine"): directExecutor (run callback immediately in the same thread)
     * - for the <b>remote</b> cache: dedicated thread pool for the cache IO calls to unblock any caller thread
     */
    Executor getExecutor(String cacheType, CacheExecutorService cacheExecutorService) {
        if (StringUtils.isEmpty(cacheType) || LOCAL_CACHE_TYPE.equals(cacheType)) {
            log.info("Going to use directExecutor for the local cache type {}", cacheType);
            return MoreExecutors.directExecutor();
        }
        log.info("Going to use cacheExecutorService for the remote cache type {}", cacheType);
        return cacheExecutorService;
    }


    @Override
    public ListenableFuture<Optional<AttributeKvEntry>> find(TenantId tenantId, EntityId entityId, String scope, String attributeKey) {
        validate(entityId, scope);
        Validator.validateString(attributeKey, "Incorrect attribute key " + attributeKey);

        AttributeCacheKey attributeCacheKey = new AttributeCacheKey(scope, entityId, attributeKey);
        Cache.ValueWrapper cachedAttributeValue = cache.get(CacheConstants.ATTRIBUTES_CACHE, attributeCacheKey);
        if (cachedAttributeValue != null) {
            hitCounter.increment();
            AttributeKvEntry cachedAttributeKvEntry = (AttributeKvEntry) cachedAttributeValue.get();
            return Futures.immediateFuture(Optional.ofNullable(cachedAttributeKvEntry));
        } else {
            missCounter.increment();
            TbCacheTransaction cacheTransaction = cache.newTransactionForKey(CacheConstants.ATTRIBUTES_CACHE, attributeCacheKey);
            ListenableFuture<Optional<AttributeKvEntry>> result = attributesDao.find(tenantId, entityId, scope, attributeKey);
            cacheTransaction.rollBackOnFailure(result, cacheExecutor);
            return Futures.transform(result, foundAttrKvEntry -> {
                cacheTransaction.putIfAbsent(attributeCacheKey, foundAttrKvEntry.orElse(null));
                cacheTransaction.commit();
                return foundAttrKvEntry;
            }, cacheExecutor);
        }
    }

    @Override
    public ListenableFuture<List<AttributeKvEntry>> find(TenantId tenantId, EntityId entityId, String scope, Collection<String> attributeKeys) {
        validate(entityId, scope);
        attributeKeys.forEach(attributeKey -> Validator.validateString(attributeKey, "Incorrect attribute key " + attributeKey));

        Map<String, Cache.ValueWrapper> wrappedCachedAttributes = findCachedAttributes(entityId, scope, attributeKeys);

        List<AttributeKvEntry> cachedAttributes = wrappedCachedAttributes.values().stream()
                .map(wrappedCachedAttribute -> (AttributeKvEntry) wrappedCachedAttribute.get())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (wrappedCachedAttributes.size() == attributeKeys.size()) {
            return Futures.immediateFuture(cachedAttributes);
        }

        Set<String> notFoundAttributeKeys = new HashSet<>(attributeKeys);
        notFoundAttributeKeys.removeAll(wrappedCachedAttributes.keySet());

        List<AttributeCacheKey> notFoundKeys = notFoundAttributeKeys.stream().map(k -> new AttributeCacheKey(scope, entityId, k)).collect(Collectors.toList());

        TbCacheTransaction cacheTransaction = cache.newTransactionForKeys(CacheConstants.ATTRIBUTES_CACHE, notFoundKeys);
        ListenableFuture<List<AttributeKvEntry>> result = attributesDao.find(tenantId, entityId, scope, notFoundAttributeKeys);
        return Futures.transform(result, foundInDbAttributes -> {
            for (AttributeKvEntry foundInDbAttribute : foundInDbAttributes) {
                AttributeCacheKey attributeCacheKey = new AttributeCacheKey(scope, entityId, foundInDbAttribute.getKey());
                cacheTransaction.putIfAbsent(attributeCacheKey, foundInDbAttribute);
                notFoundAttributeKeys.remove(foundInDbAttribute.getKey());
            }
            for (String key : notFoundAttributeKeys) {
                cacheTransaction.putIfAbsent(new AttributeCacheKey(scope, entityId, key), null);
            }
            List<AttributeKvEntry> mergedAttributes = new ArrayList<>(cachedAttributes);
            mergedAttributes.addAll(foundInDbAttributes);
            cacheTransaction.commit();
            return mergedAttributes;
        }, cacheExecutor);

    }

    private Map<String, Cache.ValueWrapper> findCachedAttributes(EntityId entityId, String scope, Collection<String> attributeKeys) {
        Map<String, Cache.ValueWrapper> cachedAttributes = new HashMap<>();
        for (String attributeKey : attributeKeys) {
            Cache.ValueWrapper cachedAttributeValue = cache.get(CacheConstants.ATTRIBUTES_CACHE, new AttributeCacheKey(scope, entityId, attributeKey));
            if (cachedAttributeValue != null) {
                hitCounter.increment();
                cachedAttributes.put(attributeKey, cachedAttributeValue);
            } else {
                missCounter.increment();
            }
        }
        return cachedAttributes;
    }

    @Override
    public ListenableFuture<List<AttributeKvEntry>> findAll(TenantId tenantId, EntityId entityId, String scope) {
        validate(entityId, scope);
        return attributesDao.findAll(tenantId, entityId, scope);
    }

    @Override
    public List<String> findAllKeysByDeviceProfileId(TenantId tenantId, DeviceProfileId deviceProfileId) {
        return attributesDao.findAllKeysByDeviceProfileId(tenantId, deviceProfileId);
    }

    @Override
    public List<String> findAllKeysByEntityIds(TenantId tenantId, EntityType entityType, List<EntityId> entityIds) {
        return attributesDao.findAllKeysByEntityIds(tenantId, entityType, entityIds);
    }

    @Override
    public ListenableFuture<List<String>> save(TenantId tenantId, EntityId entityId, String scope, List<AttributeKvEntry> attributes) {
        validate(entityId, scope);
        attributes.forEach(AttributeUtils::validate);

        List<ListenableFuture<String>> futures = new ArrayList<>(attributes.size());
        for (var attribute : attributes) {
            ListenableFuture<String> future = attributesDao.save(tenantId, entityId, scope, attribute);
            futures.add(Futures.transform(future, key -> {
                cache.evict(CacheConstants.ATTRIBUTES_CACHE, new AttributeCacheKey(scope, entityId, key));
                return key;
            }, cacheExecutor));
        }

        return Futures.allAsList(futures);
    }

    @Override
    public ListenableFuture<List<String>> removeAll(TenantId tenantId, EntityId entityId, String scope, List<String> attributeKeys) {
        validate(entityId, scope);
        List<ListenableFuture<String>> futures = attributesDao.removeAll(tenantId, entityId, scope, attributeKeys);
        return Futures.allAsList(futures.stream().map(future -> Futures.transform(future, key -> {
            cache.evict(CacheConstants.ATTRIBUTES_CACHE, new AttributeCacheKey(scope, entityId, key));
            return key;
        }, cacheExecutor)).collect(Collectors.toList()));
    }

}
