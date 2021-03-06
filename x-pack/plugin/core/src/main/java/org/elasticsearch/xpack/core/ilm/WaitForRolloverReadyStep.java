/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.ilm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * Waits for at least one rollover condition to be satisfied, using the Rollover API's dry_run option.
 */
public class WaitForRolloverReadyStep extends AsyncWaitStep {
    private static final Logger logger = LogManager.getLogger(WaitForRolloverReadyStep.class);

    public static final String NAME = "check-rollover-ready";

    private final ByteSizeValue maxSize;
    private final TimeValue maxAge;
    private final Long maxDocs;

    public WaitForRolloverReadyStep(StepKey key, StepKey nextStepKey, Client client, ByteSizeValue maxSize, TimeValue maxAge,
                                    Long maxDocs) {
        super(key, nextStepKey, client);
        this.maxSize = maxSize;
        this.maxAge = maxAge;
        this.maxDocs = maxDocs;
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    @Override
    public void evaluateCondition(IndexMetadata indexMetadata, Listener listener, TimeValue masterTimeout) {
        String rolloverAlias = RolloverAction.LIFECYCLE_ROLLOVER_ALIAS_SETTING.get(indexMetadata.getSettings());

        if (Strings.isNullOrEmpty(rolloverAlias)) {
            listener.onFailure(new IllegalArgumentException(String.format(Locale.ROOT,
                "setting [%s] for index [%s] is empty or not defined", RolloverAction.LIFECYCLE_ROLLOVER_ALIAS,
                indexMetadata.getIndex().getName())));
            return;
        }

        if (indexMetadata.getRolloverInfos().get(rolloverAlias) != null) {
            logger.info("index [{}] was already rolled over for alias [{}], not attempting to roll over again",
                indexMetadata.getIndex().getName(), rolloverAlias);
            listener.onResponse(true, new WaitForRolloverReadyStep.EmptyInfo());
            return;
        }

        // The order of the following checks is important in ways which may not be obvious.

        // First, figure out if 1) The configured alias points to this index, and if so,
        // whether this index is the write alias for this index
        boolean aliasPointsToThisIndex = indexMetadata.getAliases().containsKey(rolloverAlias);

        Boolean isWriteIndex = null;
        if (aliasPointsToThisIndex) {
            // The writeIndex() call returns a tri-state boolean:
            // true  -> this index is the write index for this alias
            // false -> this index is not the write index for this alias
            // null  -> this alias is a "classic-style" alias and does not have a write index configured, but only points to one index
            //          and is thus the write index by default
            isWriteIndex = indexMetadata.getAliases().get(rolloverAlias).writeIndex();
        }

        boolean indexingComplete = LifecycleSettings.LIFECYCLE_INDEXING_COMPLETE_SETTING.get(indexMetadata.getSettings());
        if (indexingComplete) {
            logger.trace(indexMetadata.getIndex() + " has lifecycle complete set, skipping " + WaitForRolloverReadyStep.NAME);
            // If this index is still the write index for this alias, skipping rollover and continuing with the policy almost certainly
            // isn't what we want, as something likely still expects to be writing to this index.
            // If the alias doesn't point to this index, that's okay as that will be the result if this index is using a
            // "classic-style" alias and has already rolled over, and we want to continue with the policy.
            if (aliasPointsToThisIndex && Boolean.TRUE.equals(isWriteIndex)) {
                listener.onFailure(new IllegalStateException(String.format(Locale.ROOT,
                    "index [%s] has [%s] set to [true], but is still the write index for alias [%s]",
                    indexMetadata.getIndex().getName(), LifecycleSettings.LIFECYCLE_INDEXING_COMPLETE, rolloverAlias)));
                return;
            }

            listener.onResponse(true, new WaitForRolloverReadyStep.EmptyInfo());
            return;
        }

        // If indexing_complete is *not* set, and the alias does not point to this index, we can't roll over this index, so error out.
        if (aliasPointsToThisIndex == false) {
            listener.onFailure(new IllegalArgumentException(String.format(Locale.ROOT,
                "%s [%s] does not point to index [%s]", RolloverAction.LIFECYCLE_ROLLOVER_ALIAS, rolloverAlias,
                indexMetadata.getIndex().getName())));
            return;
        }

        // Similarly, if isWriteIndex is false (see note above on false vs. null), we can't roll over this index, so error out.
        if (Boolean.FALSE.equals(isWriteIndex)) {
            listener.onFailure(new IllegalArgumentException(String.format(Locale.ROOT,
                "index [%s] is not the write index for alias [%s]", indexMetadata.getIndex().getName(), rolloverAlias)));
            return;
        }

        RolloverRequest rolloverRequest = new RolloverRequest(rolloverAlias, null).masterNodeTimeout(masterTimeout);
        rolloverRequest.dryRun(true);
        if (maxAge != null) {
            rolloverRequest.addMaxIndexAgeCondition(maxAge);
        }
        if (maxSize != null) {
            rolloverRequest.addMaxIndexSizeCondition(maxSize);
        }
        if (maxDocs != null) {
            rolloverRequest.addMaxIndexDocsCondition(maxDocs);
        }
        getClient().admin().indices().rolloverIndex(rolloverRequest,
            ActionListener.wrap(response -> listener.onResponse(response.getConditionStatus().values().stream().anyMatch(i -> i),
                new WaitForRolloverReadyStep.EmptyInfo()), listener::onFailure));
    }

    ByteSizeValue getMaxSize() {
        return maxSize;
    }

    TimeValue getMaxAge() {
        return maxAge;
    }

    Long getMaxDocs() {
        return maxDocs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), maxSize, maxAge, maxDocs);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        WaitForRolloverReadyStep other = (WaitForRolloverReadyStep) obj;
        return super.equals(obj) &&
            Objects.equals(maxSize, other.maxSize) &&
            Objects.equals(maxAge, other.maxAge) &&
            Objects.equals(maxDocs, other.maxDocs);
    }

    // We currently have no information to provide for this AsyncWaitStep, so this is an empty object
    private class EmptyInfo implements ToXContentObject {
        private EmptyInfo() {
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder;
        }
    }
}
