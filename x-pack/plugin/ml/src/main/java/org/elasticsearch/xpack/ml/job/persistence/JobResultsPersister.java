/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse.Result;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSizeStats;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.Quantiles;
import org.elasticsearch.xpack.core.ml.job.results.AnomalyRecord;
import org.elasticsearch.xpack.core.ml.job.results.Bucket;
import org.elasticsearch.xpack.core.ml.job.results.BucketInfluencer;
import org.elasticsearch.xpack.core.ml.job.results.CategoryDefinition;
import org.elasticsearch.xpack.core.ml.job.results.Forecast;
import org.elasticsearch.xpack.core.ml.job.results.ForecastRequestStats;
import org.elasticsearch.xpack.core.ml.job.results.Influencer;
import org.elasticsearch.xpack.core.ml.job.results.ModelPlot;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;
import static org.elasticsearch.xpack.core.ClientHelper.stashWithOrigin;
import static org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings.DOC_TYPE;

/**
 * Persists result types, Quantiles etc to Elasticsearch<br>
 * <h2>Bucket</h2> Bucket result. The anomaly score of the bucket may not match the summed
 * score of all the records as all the records may not have been outputted for the
 * bucket. Contains bucket influencers that are persisted both with the bucket
 * and separately.
 * <b>Anomaly Record</b> Each record was generated by a detector which can be identified via
 * the detectorIndex field.
 * <b>Influencers</b>
 * <b>Quantiles</b> may contain model quantiles used in normalization and are
 * stored in documents of type {@link Quantiles#TYPE} <br>
 * <b>ModelSizeStats</b> This is stored in a flat structure <br>
 * <b>ModelSnapShot</b> This is stored in a flat structure <br>
 *
 * @see ElasticsearchMappings
 */
public class JobResultsPersister {

    private static final Logger logger = LogManager.getLogger(JobResultsPersister.class);

    private final Client client;

    public JobResultsPersister(Client client) {
        this.client = client;
    }

    public Builder bulkPersisterBuilder(String jobId) {
        return new Builder(jobId);
    }

    public class Builder {
        private BulkRequest bulkRequest;
        private final String jobId;
        private final String indexName;

        private Builder(String jobId) {
            this.jobId = Objects.requireNonNull(jobId);
            indexName = AnomalyDetectorsIndex.resultsWriteAlias(jobId);
            bulkRequest = new BulkRequest();
        }

        /**
         * Persist the result bucket and its bucket influencers
         * Buckets are persisted with a consistent ID
         *
         * @param bucket The bucket to persist
         * @return this
         */
        public Builder persistBucket(Bucket bucket) {
            // If the supplied bucket has records then create a copy with records
            // removed, because we never persist nested records in buckets
            Bucket bucketWithoutRecords = bucket;
            if (!bucketWithoutRecords.getRecords().isEmpty()) {
                bucketWithoutRecords = new Bucket(bucket);
                bucketWithoutRecords.setRecords(Collections.emptyList());
            }
            String id = bucketWithoutRecords.getId();
            logger.trace("[{}] ES API CALL: index bucket to index [{}] with ID [{}]", jobId, indexName, id);
            indexResult(id, bucketWithoutRecords, "bucket");

            persistBucketInfluencersStandalone(jobId, bucketWithoutRecords.getBucketInfluencers());

            return this;
        }

        private void persistBucketInfluencersStandalone(String jobId, List<BucketInfluencer> bucketInfluencers) {
            if (bucketInfluencers != null && bucketInfluencers.isEmpty() == false) {
                for (BucketInfluencer bucketInfluencer : bucketInfluencers) {
                    String id = bucketInfluencer.getId();
                    logger.trace("[{}] ES BULK ACTION: index bucket influencer to index [{}] with ID [{}]", jobId, indexName, id);
                    indexResult(id, bucketInfluencer, "bucket influencer");
                }
            }
        }

        /**
         * Persist a list of anomaly records
         *
         * @param records the records to persist
         * @return this
         */
        public Builder persistRecords(List<AnomalyRecord> records) {
            for (AnomalyRecord record : records) {
                logger.trace("[{}] ES BULK ACTION: index record to index [{}] with ID [{}]", jobId, indexName, record.getId());
                indexResult(record.getId(), record, "record");
            }

            return this;
        }

        /**
         * Persist a list of influencers optionally using each influencer's ID or
         * an auto generated ID
         *
         * @param influencers the influencers to persist
         * @return this
         */
        public Builder persistInfluencers(List<Influencer> influencers) {
            for (Influencer influencer : influencers) {
                logger.trace("[{}] ES BULK ACTION: index influencer to index [{}] with ID [{}]", jobId, indexName, influencer.getId());
                indexResult(influencer.getId(), influencer, "influencer");
            }

            return this;
        }

        public Builder persistModelPlot(ModelPlot modelPlot) {
            logger.trace("[{}] ES BULK ACTION: index model plot to index [{}] with ID [{}]", jobId, indexName, modelPlot.getId());
            indexResult(modelPlot.getId(), modelPlot, "model plot");
            return this;
        }

        public Builder persistForecast(Forecast forecast) {
            logger.trace("[{}] ES BULK ACTION: index forecast to index [{}] with ID [{}]", jobId, indexName, forecast.getId());
            indexResult(forecast.getId(), forecast, Forecast.RESULT_TYPE_VALUE);
            return this;
        }

        public Builder persistForecastRequestStats(ForecastRequestStats forecastRequestStats) {
            logger.trace("[{}] ES BULK ACTION: index forecast request stats to index [{}] with ID [{}]", jobId, indexName,
                    forecastRequestStats.getId());
            indexResult(forecastRequestStats.getId(), forecastRequestStats, Forecast.RESULT_TYPE_VALUE);
            return this;
        }

        private void indexResult(String id, ToXContent resultDoc, String resultType) {
            try (XContentBuilder content = toXContentBuilder(resultDoc)) {
                bulkRequest.add(new IndexRequest(indexName, DOC_TYPE, id).source(content));
            } catch (IOException e) {
                logger.error(new ParameterizedMessage("[{}] Error serialising {}", jobId, resultType), e);
            }

            if (bulkRequest.numberOfActions() >= JobRenormalizedResultsPersister.BULK_LIMIT) {
                executeRequest();
            }
        }

        /**
         * Execute the bulk action
         */
        public void executeRequest() {
            if (bulkRequest.numberOfActions() == 0) {
                return;
            }
            logger.trace("[{}] ES API CALL: bulk request with {} actions", jobId, bulkRequest.numberOfActions());

            try (ThreadContext.StoredContext ignore = stashWithOrigin(client.threadPool().getThreadContext(), ML_ORIGIN)) {
                BulkResponse addRecordsResponse = client.bulk(bulkRequest).actionGet();
                if (addRecordsResponse.hasFailures()) {
                    logger.error("[{}] Bulk index of results has errors: {}", jobId, addRecordsResponse.buildFailureMessage());
                }
            }

            bulkRequest = new BulkRequest();
        }

        // for testing
        BulkRequest getBulkRequest() {
            return bulkRequest;
        }
    }

    /**
     * Persist the category definition
     *
     * @param category The category to be persisted
     */
    public void persistCategoryDefinition(CategoryDefinition category) {
        Persistable persistable = new Persistable(category.getJobId(), category, category.getId());

        persistable.persist(AnomalyDetectorsIndex.resultsWriteAlias(category.getJobId())).actionGet();
        // Don't commit as we expect masses of these updates and they're not
        // read again by this process
    }

    /**
     * Persist the quantiles (blocking)
     */
    public void persistQuantiles(Quantiles quantiles) {
        Persistable persistable = new Persistable(quantiles.getJobId(), quantiles, Quantiles.documentId(quantiles.getJobId()));
        persistable.persist(AnomalyDetectorsIndex.jobStateIndexName()).actionGet();
    }

    /**
     * Persist the quantiles (async)
     */
    public void persistQuantiles(Quantiles quantiles, WriteRequest.RefreshPolicy refreshPolicy, ActionListener<IndexResponse> listener) {
        Persistable persistable = new Persistable(quantiles.getJobId(), quantiles, Quantiles.documentId(quantiles.getJobId()));
        persistable.setRefreshPolicy(refreshPolicy);
        persistable.persist(AnomalyDetectorsIndex.jobStateIndexName(), listener);
    }

    /**
     * Persist a model snapshot description
     */
    public IndexResponse persistModelSnapshot(ModelSnapshot modelSnapshot, WriteRequest.RefreshPolicy refreshPolicy) {
        Persistable persistable = new Persistable(modelSnapshot.getJobId(), modelSnapshot, ModelSnapshot.documentId(modelSnapshot));
        persistable.setRefreshPolicy(refreshPolicy);
        return persistable.persist(AnomalyDetectorsIndex.resultsWriteAlias(modelSnapshot.getJobId())).actionGet();
    }

    /**
     * Persist the memory usage data (blocking)
     */
    public void persistModelSizeStats(ModelSizeStats modelSizeStats) {
        String jobId = modelSizeStats.getJobId();
        logger.trace("[{}] Persisting model size stats, for size {}", jobId, modelSizeStats.getModelBytes());
        Persistable persistable = new Persistable(jobId, modelSizeStats, modelSizeStats.getId());
        persistable.persist(AnomalyDetectorsIndex.resultsWriteAlias(jobId)).actionGet();
    }

    /**
     * Persist the memory usage data
     */
    public void persistModelSizeStats(ModelSizeStats modelSizeStats, WriteRequest.RefreshPolicy refreshPolicy,
                                      ActionListener<IndexResponse> listener) {
        String jobId = modelSizeStats.getJobId();
        logger.trace("[{}] Persisting model size stats, for size {}", jobId, modelSizeStats.getModelBytes());
        Persistable persistable = new Persistable(jobId, modelSizeStats, modelSizeStats.getId());
        persistable.setRefreshPolicy(refreshPolicy);
        persistable.persist(AnomalyDetectorsIndex.resultsWriteAlias(jobId), listener);
    }

    /**
     * Delete any existing interim results synchronously
     */
    public void deleteInterimResults(String jobId) {
        new JobDataDeleter(client, jobId).deleteInterimResults();
    }

    /**
     * Once all the job data has been written this function will be
     * called to commit the writes to the datastore.
     *
     * @param jobId The job Id
     */
    public void commitResultWrites(String jobId) {
        // We refresh using the read alias in order to ensure all indices will
        // be refreshed even if a rollover occurs in between.
        String indexName = AnomalyDetectorsIndex.jobResultsAliasedName(jobId);

        // Refresh should wait for Lucene to make the data searchable
        logger.trace("[{}] ES API CALL: refresh index {}", jobId, indexName);
        RefreshRequest refreshRequest = new RefreshRequest(indexName);
        refreshRequest.indicesOptions(IndicesOptions.lenientExpandOpen());
        try (ThreadContext.StoredContext ignore = stashWithOrigin(client.threadPool().getThreadContext(), ML_ORIGIN)) {
            client.admin().indices().refresh(refreshRequest).actionGet();
        }
    }

    /**
     * Once the job state has been written calling this function makes it
     * immediately searchable.
     *
     * @param jobId The job Id
     * */
    public void commitStateWrites(String jobId) {
        String indexName = AnomalyDetectorsIndex.jobStateIndexPattern();
        // Refresh should wait for Lucene to make the data searchable
        logger.trace("[{}] ES API CALL: refresh index {}", jobId, indexName);
        RefreshRequest refreshRequest = new RefreshRequest(indexName);
        refreshRequest.indicesOptions(IndicesOptions.lenientExpandOpen());
        try (ThreadContext.StoredContext ignore = stashWithOrigin(client.threadPool().getThreadContext(), ML_ORIGIN)) {
            client.admin().indices().refresh(refreshRequest).actionGet();
        }
    }

    private XContentBuilder toXContentBuilder(ToXContent obj) throws IOException {
        XContentBuilder builder = jsonBuilder();
        obj.toXContent(builder, ToXContent.EMPTY_PARAMS);
        return builder;
    }

    private class Persistable {

        private final String jobId;
        private final ToXContent object;
        private final String id;
        private WriteRequest.RefreshPolicy refreshPolicy;

        Persistable(String jobId, ToXContent object, String id) {
            this.jobId = jobId;
            this.object = object;
            this.id = id;
            this.refreshPolicy = WriteRequest.RefreshPolicy.NONE;
        }

        void setRefreshPolicy(WriteRequest.RefreshPolicy refreshPolicy) {
            this.refreshPolicy = refreshPolicy;
        }

        ActionFuture<IndexResponse> persist(String indexName) {
            PlainActionFuture<IndexResponse> actionFuture = PlainActionFuture.newFuture();
            persist(indexName, actionFuture);
            return actionFuture;
        }

        void persist(String indexName, ActionListener<IndexResponse> listener) {
            logCall(indexName);

            try (XContentBuilder content = toXContentBuilder(object)) {
                IndexRequest indexRequest = new IndexRequest(indexName, DOC_TYPE, id).source(content).setRefreshPolicy(refreshPolicy);
                executeAsyncWithOrigin(client.threadPool().getThreadContext(), ML_ORIGIN, indexRequest, listener, client::index);
            } catch (IOException e) {
                logger.error(new ParameterizedMessage("[{}] Error writing [{}]", jobId, (id == null) ? "auto-generated ID" : id), e);
                IndexResponse.Builder notCreatedResponse = new IndexResponse.Builder();
                notCreatedResponse.setResult(Result.NOOP);
                listener.onResponse(notCreatedResponse.build());
            }
        }

        private void logCall(String indexName) {
            if (id != null) {
                logger.trace("[{}] ES API CALL: to index {} with ID [{}]", jobId, indexName, id);
            } else {
                logger.trace("[{}] ES API CALL: to index {} with auto-generated ID", jobId, indexName);
            }
        }
    }
}
