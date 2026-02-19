/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.sql.results.jdbc.internal;

import java.util.List;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMapping;
import org.hibernate.sql.results.jdbc.spi.RowProcessingState;

/**
 * An {@link AbstractJdbcValues} implementation for cases where we had a cache hit.
 *
 * @author Steve Ebersole
 */
public class JdbcValuesCacheHit extends AbstractJdbcValues {
	private List<?> cachedResults;
	private final int numberOfRows;
	private final JdbcValuesMapping resolvedMapping;
	private final int[] valueIndexesToCacheIndexes;
	private final boolean dataSufficient;
	private final int offset;
	private final int resultCount;
	private int position = -1;

	public JdbcValuesCacheHit(List<?> cachedResults, JdbcValuesMapping resolvedMapping) {
		// See QueryCachePutManagerEnabledImpl for what is being put into the cached results
		this.cachedResults = cachedResults;
		final boolean hasMetadata = !cachedResults.isEmpty()
				&& cachedResults.get( 0 ) instanceof CachedJdbcValuesMetadata;
		this.offset = hasMetadata ? 1 : 0;
		this.numberOfRows = cachedResults.size() - offset - 1;
		this.resultCount = cachedResults.isEmpty() ? 0 : (int) cachedResults.get( cachedResults.size() - 1 );
		this.resolvedMapping = resolvedMapping;

		if ( hasMetadata ) {
			final int[] storedMapping =
					( (CachedJdbcValuesMetadata) cachedResults.get( 0 ) ).getValueIndexesToCacheIndexes();
			this.dataSufficient = isMappingCompatible(
					resolvedMapping.getValueIndexesToCacheIndexes(),
					storedMapping
			);
			this.valueIndexesToCacheIndexes = storedMapping;
		}
		else {
			this.dataSufficient = true;
			this.valueIndexesToCacheIndexes = resolvedMapping.getValueIndexesToCacheIndexes();
		}
	}

	/**
	 * Checks whether the stored (writer's) compaction mapping is compatible with the reader's mapping.
	 * Compatible means: every column position the reader needs is present in the stored mapping.
	 *
	 * @param readerMapping the current result type's compaction mapping (from resolvedMapping)
	 * @param storedMapping the compaction mapping stored in cache metadata, or null for identity
	 */
	private static boolean isMappingCompatible(int[] readerMapping, int[] storedMapping) {
		if ( storedMapping == null ) {
			// Writer cached all columns at original positions (identity) — always compatible
			return true;
		}
		// Check that every position the reader needs is present in the stored mapping
		for ( int i = 0; i < readerMapping.length; i++ ) {
			if ( readerMapping[i] != -1 ) {
				if ( i >= storedMapping.length || storedMapping[i] == -1 ) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Returns whether the cached data has enough columns for the resolved mapping.
	 * When {@code false}, the cache entry was populated by a different result type
	 * (e.g. entity) that cached fewer columns and needs to be re-populated.
	 */
	public boolean isDataSufficient() {
		return dataSufficient;
	}

	@Override
	protected boolean processNext(RowProcessingState rowProcessingState) {
		// NOTE: explicitly skipping limit handling because the cached state ought
		// 		 already be the limited size since the cache key includes limits
		position++;
		if ( position >= numberOfRows ) {
			position = numberOfRows;
			return false;
		}
		else {
			return true;
		}
	}

	@Override
	protected boolean processPrevious(RowProcessingState rowProcessingState) {
		// NOTE: explicitly skipping limit handling because the cached state ought
		// 		 already be the limited size since the cache key includes limits
		position--;
		if ( position >= numberOfRows ) {
			position = numberOfRows;
			return false;
		}
		else {
			return true;
		}
	}

	@Override
	protected boolean processScroll(int numberOfRows, RowProcessingState rowProcessingState) {
		// NOTE: explicitly skipping limit handling because the cached state should
		// 		 already be the limited size since the cache key includes limits
		position += numberOfRows;
		if ( position >= this.numberOfRows ) {
			position = this.numberOfRows;
			return false;
		}
		else {
			return true;
		}
	}

	@Override
	public int getPosition() {
		return position + 1;
	}

	@Override
	protected boolean processPosition(int position, RowProcessingState rowProcessingState) {
		// NOTE: explicitly skipping limit handling because the cached state should
		// 		 already be the limited size since the cache key includes limits

		if ( position < 0 ) {
			// we need to subtract it from `numberOfRows`
			position = numberOfRows + position;
		}
		else {
			// internally, positions are indexed from zero
			position--;
		}

		if ( position >= numberOfRows ) {
			this.position = numberOfRows;
			return false;
		}
		else {
			this.position = position;
			return true;
		}
	}

	@Override
	public boolean isBeforeFirst(RowProcessingState rowProcessingState) {
		return position < 0;
	}

	@Override
	public void beforeFirst(RowProcessingState rowProcessingState) {
		position = -1;
	}

	@Override
	public boolean isFirst(RowProcessingState rowProcessingState) {
		return position == 0;
	}

	@Override
	public boolean first(RowProcessingState rowProcessingState) {
		position = 0;
		return numberOfRows > 0;
	}

	@Override
	public boolean isAfterLast(RowProcessingState rowProcessingState) {
		return position >= numberOfRows;
	}

	@Override
	public void afterLast(RowProcessingState rowProcessingState) {
		position = numberOfRows;
	}

	@Override
	public boolean isLast(RowProcessingState rowProcessingState) {
		return numberOfRows == 0
				? position == 0
				: position == numberOfRows - 1;
	}

	@Override
	public boolean last(RowProcessingState rowProcessingState) {
		if ( numberOfRows == 0 ) {
			position = 0;
			return false;
		}
		else {
			position = numberOfRows - 1;
			return true;
		}
	}

	@Override
	public JdbcValuesMapping getValuesMapping() {
		return resolvedMapping;
	}

	@Override
	public boolean usesFollowOnLocking() {
		return true;
	}

	@Override
	public Object getCurrentRowValue(int valueIndex) {
		if ( position >= numberOfRows ) {
			return null;
		}
		final Object row = cachedResults.get( position + offset );
		if ( row instanceof Object[] array ) {
			return valueIndexesToCacheIndexes == null
					? array[valueIndex]
					: array[valueIndexesToCacheIndexes[valueIndex]];
		}
		else {
			assert valueIndexesToCacheIndexes[valueIndex] == 0;
			return row;
		}
	}

	@Override
	public void finishUp(SharedSessionContractImplementor session) {
		cachedResults = null;
	}

	@Override
	public void finishRowProcessing(RowProcessingState rowProcessingState, boolean wasAdded) {
		// No-op
	}

	@Override
	public void setFetchSize(int fetchSize) {}

	@Override
	public int getResultCountEstimate() {
		return resultCount;
	}
}
