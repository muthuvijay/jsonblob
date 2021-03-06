package com.lowtuna.jsonblob.core;

import com.codahale.metrics.CachedGauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.WriteResult;
import com.mongodb.util.JSON;
import com.mongodb.util.JSONParseException;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.util.Duration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import javax.annotation.concurrent.GuardedBy;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
public class MongoDbJsonBlobManager implements Managed, Runnable, JsonBlobManager {
    public static final String UPDATED_ATTR_NAME = "updated";
    public static final String CREATED_ATTR_NAME = "created";
    public static final String ACCESSED_ATTR_NAME = "accessed";
    public static final String ID_ATTR_NAME = "_id";
    public static final String BLOB_ATTR_NAME = "blob";

    @GuardedBy("pendingLastAccessedWritesLock")
    private final Map<ObjectId, DateTime> pendingLastAccessedWrites = Maps.newHashMap();
    private final ReentrantReadWriteLock pendingLastAccessedWritesLock = new ReentrantReadWriteLock();

    private final ScheduledExecutorService scheduledExecutorService;
    private final Duration blobCleanupFrequency;
    @Getter
    private final Duration blobAccessTtl;
    private final MetricRegistry metricRegistry;

    @Getter
    private final DBCollection collection;
    private final Timer createTimer;
    private final Timer readTimer;
    private final Timer updateTimer;
    private final Timer deleteTimer;

    public MongoDbJsonBlobManager(DB mongoDb, String blobCollectionName, ScheduledExecutorService scheduledExecutorService, Duration blobCleanupFrequency, Duration blobAccessTtl, MetricRegistry metrics) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.blobCleanupFrequency = blobCleanupFrequency;
        this.blobAccessTtl = blobAccessTtl;
        this.metricRegistry = metrics;

        this.collection = mongoDb.getCollection(blobCollectionName);
        this.createTimer = metrics.timer(MetricRegistry.name(getClass(), "create"));
        this.readTimer = metrics.timer(MetricRegistry.name(getClass(), "read"));
        this.updateTimer = metrics.timer(MetricRegistry.name(getClass(), "update"));
        this.deleteTimer = metrics.timer(MetricRegistry.name(getClass(), "delete"));

        metrics.register(MetricRegistry.name(getClass(), "blobCount"), new CachedGauge<Long>(1, TimeUnit.HOURS) {
            @Override
            protected Long loadValue() {
                return collection.count();
            }
        });
    }

    private BasicDBObject getDBObject(ObjectId objectId) {
        return new BasicDBObject(ID_ATTR_NAME, objectId);
    }

    private DBObject createDBObject(String json, Optional<String> objectId) {
        final DateTime now = DateTime.now(DateTimeZone.UTC);
        BasicDBObjectBuilder builder = BasicDBObjectBuilder
                .start(UPDATED_ATTR_NAME, new Date(now.getMillis()))
                .append(ACCESSED_ATTR_NAME, new Date(now.getMillis()))
                .append(ID_ATTR_NAME, new ObjectId(objectId.isPresent() ? objectId.get() : UUID.randomUUID().toString()))
                .append(BLOB_ATTR_NAME, JSON.parse(json));

        if (!objectId.isPresent()) {
            builder = builder.append(CREATED_ATTR_NAME, new Date(now.getMillis()));
        }

        return builder.get();
    }

    static boolean isValidJson(String json) {
        try {
            JSON.parse(json);
            return true;
        } catch (JSONParseException e) {
            return false;
        }
    }

    private DBObject create(String json) {
        try (Timer.Context timerContext = createTimer.time()) {
            log.debug("inserting blob");
            log.trace("new blob json='{}'", json);
            DBObject parsed = createDBObject(json, Optional.absent());
            collection.insert(parsed);
            log.debug("successfully inserted blob of json as objectId='{}'", parsed.get(ID_ATTR_NAME));
            return parsed;
        }
    }

    private DBObject read(final ObjectId id) throws BlobNotFoundException {
        try (Timer.Context timerContext = readTimer.time()) {
            log.debug("attempting to retrieve blob with id='{}'", id);
            DBObject objectId = getDBObject(id);
            if (objectId != null) {
                log.debug("finding blob with objectId='{}'", objectId);
                final DBObject obj = collection.findOne(objectId);
                if (obj != null) {
                    try(ClosableLock closableLock = new ClosableLock(pendingLastAccessedWritesLock.writeLock())) {
                        closableLock.lock();
                        pendingLastAccessedWrites.put(id, DateTime.now(DateTimeZone.UTC));
                    }

                    return obj;
                }
            }
            log.debug("couldn't retrieve blob with id='{}'", id);
            throw new BlobNotFoundException(id.toString());
        }
    }

    private DBObject update(ObjectId id, String json) throws BlobNotFoundException {
        try (Timer.Context timerContext = updateTimer.time()) {
            log.debug("attempting to update blob with id='{}'", id);
            log.trace("blob json='{}'", json);
            DBObject objectId = getDBObject(id);
            if (objectId != null) {
                log.debug("finding blob to update with objectId='{}'", objectId);
                DBObject obj = collection.findOne(objectId);
                if (obj != null) {
                    DBObject parsed = createDBObject(json, Optional.of(id.toString()));
                    collection.update(obj, parsed);
                    log.debug("successfully updated blob of json with objectId='{}'", id);
                    return parsed;
                }
            }
            log.debug("couldn't update blob with id='{}'", id);
            throw new BlobNotFoundException(id.toString());
        }
    }

    private boolean delete(ObjectId id) throws BlobNotFoundException {
        try (Timer.Context timerContext = deleteTimer.time()) {
            log.debug("attempting to delete blob with id='{}'", id);
            DBObject objectId = getDBObject(id);
            if (objectId != null) {
                log.debug("finding blob to delete with objectId='{}'", objectId);
                DBObject obj = collection.findOne(objectId);
                if (obj != null) {
                    WriteResult result = collection.remove(obj);
                    boolean removed = result.getN() > 0 && result.wasAcknowledged();
                    if (removed) {
                        log.debug("successfully removed {} blob(s) of json with objectId='{}'", result.getN(), id);
                    } else {
                        log.debug("did not remove any blob(s) of json with objectId='{}'", id);
                    }
                    return removed;
                }
            }
            log.debug("couldn't remove blob with id='{}'", id);
            throw new BlobNotFoundException(id.toString());
        }
    }

    @Override
    public void start() throws Exception {
        scheduledExecutorService.scheduleWithFixedDelay(
                new BlobCleanupJob(collection, blobAccessTtl, metricRegistry),
                0,
                blobCleanupFrequency.getQuantity(),
                blobCleanupFrequency.getUnit()
        );
        scheduledExecutorService.scheduleWithFixedDelay(this, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void stop() throws Exception {
        run();
    }

    @Override
    public void run() {
        HashMap<ObjectId, DateTime> updates = Maps.newHashMap();

        try(ClosableLock closableLock = new ClosableLock(pendingLastAccessedWritesLock.writeLock())) {
            closableLock.lock();
            updates.putAll(pendingLastAccessedWrites);
            pendingLastAccessedWrites.clear();
        }

        log.debug("updating last accessed time for {} blobs", updates.size());
        for (Map.Entry<ObjectId, DateTime> lastAccessedEntry: updates.entrySet()) {
            final DBObject obj = collection.findOne(lastAccessedEntry.getKey());
            if (obj != null) {
                DateTime accessed = lastAccessedEntry.getValue();

                BasicDBObject updatedAccessedDbObject = new BasicDBObject();
                updatedAccessedDbObject.append("$set", new BasicDBObject().append(ACCESSED_ATTR_NAME, new Date(accessed.getMillis())));

                log.debug("updating last accessed time for blob with objectId='{}' to {}", lastAccessedEntry.getKey(), accessed);
                collection.update(obj, updatedAccessedDbObject, false, false);
                log.debug("updated last accessed time for blob with objectId='{}' to {}", lastAccessedEntry.getKey(), accessed);
            }
        }
    }

    @Override
    public String createBlob(String blob) throws IllegalArgumentException {
        if (!isValidJson(blob)) {
            throw new IllegalArgumentException("Invalid JSON");
        }

        DBObject newBlob = create(blob);
        ObjectId id = (ObjectId) newBlob.get(ID_ATTR_NAME);
        if (id == null) {
            throw new IllegalStateException("Blob ID was null");
        }

        return id.toString();
    }

    @Override
    public String getBlob(String blobId) throws BlobNotFoundException {
        DBObject object = read(new ObjectId(blobId));
        return object.get(BLOB_ATTR_NAME).toString();
    }

    @Override
    public boolean updateBlob(String blobId, String blob) throws IllegalArgumentException, BlobNotFoundException {
        if (!isValidJson(blob)) {
            throw new IllegalArgumentException("Invalid JSON");
        }

        update(new ObjectId(blobId), blob);
        return true; //UGH
    }

    @Override
    public boolean deleteBlob(String blobId) throws BlobNotFoundException {
        delete(new ObjectId(blobId));
        return true;
    }

    @RequiredArgsConstructor
    private static class ClosableLock implements AutoCloseable {
        private final Lock lock;

        public void lock() {
            lock.lock();
        }

        public void close() {
            lock.unlock();
        }
    }
}
