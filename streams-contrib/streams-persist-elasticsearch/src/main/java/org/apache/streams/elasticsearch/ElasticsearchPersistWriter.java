/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.elasticsearch;

import org.apache.streams.config.ComponentConfigurator;
import org.apache.streams.config.StreamsConfigurator;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsPersistWriter;
import org.apache.streams.jackson.StreamsJacksonMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.common.settings.Settings;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ElasticsearchPersistUpdater updates documents to elasticsearch.
 */
public class ElasticsearchPersistWriter implements StreamsPersistWriter, Serializable {

  public static final String STREAMS_ID = ElasticsearchPersistWriter.class.getCanonicalName();

  private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchPersistWriter.class);
  private static final NumberFormat MEGABYTE_FORMAT = new DecimalFormat("#.##");
  private static final NumberFormat NUMBER_FORMAT = new DecimalFormat("###,###,###,###");
  private static final Long DEFAULT_BULK_FLUSH_THRESHOLD = 5L * 1024L * 1024L;
  private static final int DEFAULT_BATCH_SIZE = 100;
  //ES defaults its bulk index queue to 50 items.  We want to be under this on our backoff so set this to 1/2 ES default
  //at a batch size as configured here.
  private static final long WAITING_DOCS_LIMIT = DEFAULT_BATCH_SIZE * 25;
  //A document should have to wait no more than 10s to get flushed
  private static final long DEFAULT_MAX_WAIT = 10000;

  protected static final ObjectMapper OBJECT_MAPPER = StreamsJacksonMapper.getInstance();

  protected final List<String> affectedIndexes = new ArrayList<>();

  protected final ElasticsearchClientManager manager;
  protected final ElasticsearchWriterConfiguration config;

  protected BulkRequestBuilder bulkRequest;

  private boolean veryLargeBulk = false;  // by default this setting is set to false
  private long flushThresholdsRecords = DEFAULT_BATCH_SIZE;
  private long flushThresholdBytes = DEFAULT_BULK_FLUSH_THRESHOLD;

  private long flushThresholdTime = DEFAULT_MAX_WAIT;
  private long lastFlush = new Date().getTime();
  private Timer timer = new Timer();


  private final AtomicInteger batchesSent = new AtomicInteger(0);
  private final AtomicInteger batchesResponded = new AtomicInteger(0);

  protected final AtomicLong currentBatchItems = new AtomicLong(0);
  protected final AtomicLong currentBatchBytes = new AtomicLong(0);

  private final AtomicLong totalSent = new AtomicLong(0);
  private final AtomicLong totalSeconds = new AtomicLong(0);
  private final AtomicLong totalOk = new AtomicLong(0);
  private final AtomicLong totalFailed = new AtomicLong(0);
  private final AtomicLong totalSizeInBytes = new AtomicLong(0);

  public ElasticsearchPersistWriter() {
    this(new ComponentConfigurator<>(ElasticsearchWriterConfiguration.class)
        .detectConfiguration(StreamsConfigurator.getConfig().getConfig("elasticsearch")));
  }

  public ElasticsearchPersistWriter(ElasticsearchWriterConfiguration config) {
    this(config, ElasticsearchClientManager.getInstance(config));
  }

  /**
   * ElasticsearchPersistWriter constructor.
   * @param config config
   * @param manager manager
   */
  public ElasticsearchPersistWriter(ElasticsearchWriterConfiguration config, ElasticsearchClientManager manager) {
    this.config = config;
    this.manager = manager;
    this.bulkRequest = this.manager.client().prepareBulk();
  }

  public long getBatchesSent() {
    return this.batchesSent.get();
  }

  public long getBatchesResponded() {
    return batchesResponded.get();
  }

  public long getFlushThresholdsRecords() {
    return this.flushThresholdsRecords;
  }

  public long getFlushThresholdBytes() {
    return this.flushThresholdBytes;
  }

  public long getFlushThreasholdMaxTime() {
    return this.flushThresholdTime;
  }

  public void setFlushThresholdRecords(long val) {
    this.flushThresholdsRecords = val;
  }

  public void setFlushThresholdBytes(long val) {
    this.flushThresholdBytes = val;
  }

  public void setFlushThreasholdMaxTime(long val) {
    this.flushThresholdTime = val;
  }

  public void setVeryLargeBulk(boolean veryLargeBulk) {
    this.veryLargeBulk = veryLargeBulk;
  }

  private long getLastFlush() {
    return this.lastFlush;
  }

  public long getTotalOutstanding() {
    return this.totalSent.get() - (this.totalFailed.get() + this.totalOk.get());
  }

  public long getTotalSent() {
    return this.totalSent.get();
  }

  public long getTotalOk() {
    return this.totalOk.get();
  }

  public long getTotalFailed() {
    return this.totalFailed.get();
  }

  public long getTotalSizeInBytes() {
    return this.totalSizeInBytes.get();
  }

  public long getTotalSeconds() {
    return this.totalSeconds.get();
  }

  public List<String> getAffectedIndexes() {
    return this.affectedIndexes;
  }

  public boolean isConnected() {
    return (this.manager.client() != null);
  }

  @Override
  public String getId() {
    return STREAMS_ID;
  }

  @Override
  public void write(StreamsDatum streamsDatum) {

    if (streamsDatum == null || streamsDatum.getDocument() == null) {
      return;
    }

    checkForBackOff();

    LOGGER.debug("Write Document: {}", streamsDatum.getDocument());

    Map<String, Object> metadata = streamsDatum.getMetadata();

    LOGGER.debug("Write Metadata: {}", metadata);

    String index = ElasticsearchMetadataUtil.getIndex(metadata, config);
    String type = ElasticsearchMetadataUtil.getType(metadata, config);
    String id = ElasticsearchMetadataUtil.getId(streamsDatum);
    String parent = ElasticsearchMetadataUtil.getParent(streamsDatum);
    String routing = ElasticsearchMetadataUtil.getRouting(streamsDatum);

    try {
      streamsDatum = appendMetadata(streamsDatum);
      String docAsJson = docAsJson(streamsDatum.getDocument());
      add(index, type, id, parent, routing,
          streamsDatum.getTimestamp() == null ? Long.toString(DateTime.now().getMillis()) : Long.toString(streamsDatum.getTimestamp().getMillis()),
          docAsJson);
    } catch (Throwable ex) {
      LOGGER.warn("Unable to Write Datum to ElasticSearch: {}", ex.getMessage());
    }
  }

  protected String docAsJson(Object streamsDocument) throws IOException {
    return (streamsDocument instanceof String) ? streamsDocument.toString() : OBJECT_MAPPER.writeValueAsString(streamsDocument);
  }

  protected StreamsDatum appendMetadata(StreamsDatum streamsDatum) throws IOException {

    String docAsJson = (streamsDatum.getDocument() instanceof String) ? streamsDatum.getDocument().toString() : OBJECT_MAPPER.writeValueAsString(streamsDatum.getDocument());

    if (streamsDatum.getMetadata() == null || streamsDatum.getMetadata().size() == 0) {
      return streamsDatum;
    } else {
      ObjectNode node = (ObjectNode)OBJECT_MAPPER.readTree(docAsJson);
      node.put("_metadata", OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsBytes(streamsDatum.getMetadata())));
      streamsDatum.setDocument(OBJECT_MAPPER.writeValueAsString(node));
      return streamsDatum;
    }
  }

  @Override
  public void cleanUp() {

    try {

      LOGGER.debug("cleanUp started");

      // before they close, check to ensure that
      flushInternal();

      LOGGER.debug("flushInternal completed");

      waitToCatchUp(0, 5 * 60 * 1000);

      LOGGER.debug("waitToCatchUp completed");

    } catch (Throwable ex) {
      // this line of code should be logically unreachable.
      LOGGER.warn("This is unexpected: {}", ex);
    } finally {

      if (veryLargeBulk) {
        resetRefreshInterval();
      }

      if ( config.getRefresh() ) {
        refreshIndexes();
        LOGGER.debug("refreshIndexes completed");
      }

      LOGGER.debug("Closed ElasticSearch Writer: Ok[{}] Failed[{}] Orphaned[{}]",
          this.totalOk.get(), this.totalFailed.get(), this.getTotalOutstanding());
      timer.cancel();

      LOGGER.debug("cleanUp completed");
    }
  }

  private void resetRefreshInterval() {
    for (String indexName : this.affectedIndexes) {

      if (this.veryLargeBulk) {
        LOGGER.debug("Resetting our Refresh Interval: {}", indexName);
        // They are in 'very large bulk' mode and the process is finished. We now want to turn the
        // refreshing back on.
        UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(indexName);
        updateSettingsRequest.settings(Settings.settingsBuilder().put("refresh_interval", "5s"));

        // submit to ElasticSearch
        this.manager.client()
            .admin()
            .indices()
            .updateSettings(updateSettingsRequest)
            .actionGet();
      }
    }
  }

  private void refreshIndexes() {

    for (String indexName : this.affectedIndexes) {

      if (config.getRefresh()) {
        LOGGER.debug("Refreshing ElasticSearch index: {}", indexName);
        this.manager.client()
            .admin()
            .indices()
            .prepareRefresh(indexName)
            .execute()
            .actionGet();
      }
    }
  }

  private synchronized void flushInternal() {
    // we do not have a working bulk request, we can just exit here.
    if (this.bulkRequest == null || this.currentBatchItems.get() == 0) {
      return;
    }

    // wait for one minute to catch up if it needs to
    waitToCatchUp(5, 60 * 1000);

    // call the flush command.
    flush(this.bulkRequest, this.currentBatchItems.get(), this.currentBatchBytes.get());

    // reset the current batch statistics
    this.currentBatchItems.set(0);
    this.currentBatchBytes.set(0);

    // reset our bulk request builder
    this.bulkRequest = this.manager.client().prepareBulk();
  }

  private synchronized void waitToCatchUp(int batchThreshold, int timeOutThresholdInMS) {
    int counter = 0;
    // If we still have 5 batches outstanding, we need to give it a minute to catch up
    while (this.getBatchesSent() - this.getBatchesResponded() > batchThreshold && counter < timeOutThresholdInMS) {
      try {
        Thread.yield();
        Thread.sleep(1);
        counter++;
      } catch (InterruptedException ie) {
        LOGGER.warn("Catchup was interrupted.  Data may be lost");
        return;
      }
    }
  }

  private void checkForBackOff() {
    try {
      if (this.getTotalOutstanding() > WAITING_DOCS_LIMIT) {
        /*
         * Author:
         * Smashew
         *
         * Date:
         * 2013-10-20
         *
         * Note:
         * With the information that we have on hand. We need to develop a heuristic
         * that will determine when the cluster is having a problem indexing records
         * by telling it to pause and wait for it to catch back up. A
         *
         * There is an impact to us, the caller, whenever this happens as well. Items
         * that are not yet fully indexed by the server sit in a queue, on the client
         * that can cause the heap to overflow. This has been seen when re-indexing
         * large amounts of data to a small cluster. The "deletes" + "indexes" can
         * cause the server to have many 'outstandingItems" in queue. Running this
         * software with large amounts of data, on a small cluster, can re-create
         * this problem.
         *
         * DO NOT DELETE THESE LINES
         ****************************************************************************/

        // wait for the flush to catch up. We are going to cap this at
        int count = 0;
        while (this.getTotalOutstanding() > WAITING_DOCS_LIMIT && count++ < 500) {
          Thread.sleep(10);
        }
        if (this.getTotalOutstanding() > WAITING_DOCS_LIMIT) {
          LOGGER.warn("Even after back-off there are {} items still in queue.", this.getTotalOutstanding());
        }
      }
    } catch (Exception ex) {
      LOGGER.warn("We were broken from our loop: {}", ex.getMessage());
    }
  }

  /**
   * add based on supplied parameters.
   * @param indexName indexName
   * @param type type
   * @param id id
   * @param ts ts
   * @param json json
   */
  public void add(String indexName, String type, String id, String ts, String json) {
    add(indexName, type, id, null, null, ts, json);
  }

  /**
   * add based on supplied parameters.
   * @param indexName indexName
   * @param type type
   * @param id id
   * @param routing routing
   * @param ts ts
   * @param json json
   */
  public void add(String indexName, String type, String id, String parent, String routing, String ts, String json) {

    // make sure that these are not null
    Objects.requireNonNull(indexName);
    Objects.requireNonNull(type);
    Objects.requireNonNull(json);

    IndexRequestBuilder indexRequestBuilder = manager.client()
        .prepareIndex(indexName, type)
        .setSource(json);

    // / They didn't specify an ID, so we will create one for them.
    if (id != null) {
      indexRequestBuilder.setId(id);
    }
    if (ts != null) {
      indexRequestBuilder.setTimestamp(ts);
    }
    if (parent != null) {
      indexRequestBuilder.setParent(parent);
    }
    if (routing != null) {
      indexRequestBuilder.setRouting(routing);
    }
    add(indexRequestBuilder.request());
  }

  protected void add(IndexRequest request) {

    Objects.requireNonNull(request);
    Objects.requireNonNull(request.index());

    // If our queue is larger than our flush threshold, then we should flush the queue.
    synchronized (this) {
      checkIndexImplications(request.index());

      bulkRequest.add(request);

      this.currentBatchBytes.addAndGet(request.source().length());
      this.currentBatchItems.incrementAndGet();

      checkForFlush();
    }
  }

  protected void checkForFlush() {
    synchronized (this) {
      if (this.currentBatchBytes.get() >= this.flushThresholdBytes
          ||
          this.currentBatchItems.get() >= this.flushThresholdsRecords
          ||
          new Date().getTime() - this.lastFlush >= this.flushThresholdTime) {
        // We should flush
        flushInternal();
      }
    }
  }

  protected void checkIndexImplications(String indexName) {
    // We need this to be safe across all writers that are currently being executed
    synchronized (ElasticsearchPersistWriter.class) {

      // this will be common if we have already verified the index.
      if (this.affectedIndexes.contains(indexName)) {
        return;
      }

      // create the index if it is missing
      createIndexIfMissing(indexName);

      // we haven't log this index.
      this.affectedIndexes.add(indexName);

    }
  }

  protected void disableRefresh() {

    for (String indexName : this.affectedIndexes) {
      // They are in 'very large bulk' mode we want to turn off refreshing the index.
      // Create a request then add the setting to tell it to stop refreshing the interval
      UpdateSettingsRequest updateSettingsRequest = new UpdateSettingsRequest(indexName);
      updateSettingsRequest.settings(Settings.settingsBuilder().put("refresh_interval", -1));

      // submit to ElasticSearch
      this.manager.client()
          .admin()
          .indices()
          .updateSettings(updateSettingsRequest)
          .actionGet();
    }
  }

  /**
   * createIndexIfMissing
   * @param indexName indexName
   */
  public void createIndexIfMissing(String indexName) {
    // Synchronize this on a static class level
    if (!this.manager.client()
        .admin()
        .indices()
        .exists(new IndicesExistsRequest(indexName))
        .actionGet()
        .isExists()) {
      // It does not exist... So we are going to need to create the index.
      // we are going to assume that the 'templates' that we have loaded into
      // elasticsearch are sufficient to ensure the index is being created properly.
      CreateIndexResponse response = this.manager.client().admin().indices().create(new CreateIndexRequest(indexName)).actionGet();

      if (response.isAcknowledged()) {
        LOGGER.info("Index Created: {}", indexName);
      } else {
        LOGGER.error("Index {} did not exist. While attempting to create the index from stored ElasticSearch Templates we were unable to get an acknowledgement.", indexName);
        LOGGER.error("Error Message: {}", response.toString());
        throw new RuntimeException("Unable to create index " + indexName);
      }
    }
  }

  @Override
  public void prepare(Object configurationObject) {
    this.veryLargeBulk = config.getBulk() == null
        ? Boolean.FALSE
        : config.getBulk();

    this.flushThresholdsRecords = config.getBatchSize() == null
        ? DEFAULT_BATCH_SIZE
        : (int)(config.getBatchSize().longValue());

    this.flushThresholdTime = config.getMaxTimeBetweenFlushMs() != null && config.getMaxTimeBetweenFlushMs() > 0
        ? config.getMaxTimeBetweenFlushMs()
        : DEFAULT_MAX_WAIT;

    this.flushThresholdBytes = config.getBatchBytes() == null
        ? DEFAULT_BULK_FLUSH_THRESHOLD
        : config.getBatchBytes();

    timer.scheduleAtFixedRate(new TimerTask() {
      public void run() {
        checkForFlush();
      }
    }, this.flushThresholdTime, this.flushThresholdTime);

    if ( veryLargeBulk ) {
      disableRefresh();
    }
  }

  private void flush(final BulkRequestBuilder bulkRequest, final Long sent, final Long sizeInBytes) {
    LOGGER.debug("Writing to ElasticSearch: Items[{}] Size[{} mb]", sent, MEGABYTE_FORMAT.format(sizeInBytes / (double) (1024 * 1024)));


    // record the last time we flushed the index
    this.lastFlush = new Date().getTime();

    // add the totals
    this.totalSent.addAndGet(sent);

    // add the total number of batches sent
    this.batchesSent.incrementAndGet();

    try {
      bulkRequest.execute().addListener(new ActionListener<BulkResponse>() {
        public void onResponse(BulkResponse bulkItemResponses) {
          batchesResponded.incrementAndGet();
          updateTotals(bulkItemResponses, sent, sizeInBytes);
        }

        public void onFailure(Throwable throwable) {
          batchesResponded.incrementAndGet();
          throwable.printStackTrace();
        }
      });
    } catch (Throwable ex) {
      LOGGER.error("There was an error sending the batch: {}", ex.getMessage());
    }
  }

  private void updateTotals(final BulkResponse bulkItemResponses, final Long sent, final Long sizeInBytes) {
    long failed = 0;
    long passed = 0;
    long millis = bulkItemResponses.getTookInMillis();

    // keep track of the number of totalFailed and items that we have totalOk.
    for (BulkItemResponse resp : bulkItemResponses.getItems()) {
      if (resp == null || resp.isFailed()) {
        failed++;
        LOGGER.debug("{} ({},{},{}) failed: {}", resp.getOpType(), resp.getIndex(), resp.getType(), resp.getId(), resp.getFailureMessage());
      } else {
        passed++;
      }
    }

    if (failed > 0) {
      LOGGER.warn("Bulk Uploading had {} failures of {}", failed, sent);
    }

    this.totalOk.addAndGet(passed);
    this.totalFailed.addAndGet(failed);
    this.totalSeconds.addAndGet(millis / 1000);
    this.totalSizeInBytes.addAndGet(sizeInBytes);

    if (sent != (passed + failed)) {
      LOGGER.error("Count MisMatch: Sent[{}] Passed[{}] Failed[{}]", sent, passed, failed);
    }

    LOGGER.debug("Batch[{}mb {} items with {} failures in {}ms] - Total[{}mb {} items with {} failures in {}seconds] {} outstanding]",
        MEGABYTE_FORMAT.format(sizeInBytes / (double) (1024 * 1024)), NUMBER_FORMAT.format(passed), NUMBER_FORMAT.format(failed), NUMBER_FORMAT.format(millis),
        MEGABYTE_FORMAT.format((double) totalSizeInBytes.get() / (double) (1024 * 1024)), NUMBER_FORMAT.format(totalOk), NUMBER_FORMAT.format(totalFailed), NUMBER_FORMAT.format(totalSeconds), NUMBER_FORMAT.format(getTotalOutstanding()));
  }

}
