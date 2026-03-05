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
	private final boolean cacheCompatible;
	private final int offset;
	private final int resultCount;
	private int position = -1;

	public JdbcValuesCacheHit(List<?> cachedResults, JdbcValuesMapping resolvedMapping) {
		// See QueryCachePutManagerEnabledImpl for what is being put into the cached results
		this.cachedResults = cachedResults;
		final CachedJdbcValuesMetadata metadata = !cachedResults.isEmpty()
				&& cachedResults.get( 0 ) instanceof CachedJdbcValuesMetadata cachedMetadata
						? cachedMetadata
						: null;
		this.offset = metadata != null ? 1 : 0;
		this.numberOfRows = cachedResults.size() - offset - 1;
		this.resultCount = cachedResults.isEmpty() ? 0 : (int) cachedResults.get( cachedResults.size() - 1 );
		this.resolvedMapping = resolvedMapping;
		if ( metadata != null ) {
			final int[] storedMapping = metadata.getValueIndexesToCacheIndexes();
			this.cacheCompatible = isCacheCompatible(
					resolvedMapping,
					storedMapping,
					metadata
			);
			this.valueIndexesToCacheIndexes = storedMapping;
		}
		else {
			this.cacheCompatible = true;
			this.valueIndexesToCacheIndexes = resolvedMapping.getValueIndexesToCacheIndexes();
		}
	}

	/**
	 * Checks whether the cached data is compatible with the reader's mapping.
	 * This verifies both that every column position the reader needs is present
	 * in the stored mapping, and that the Java types of cached values match
	 * what the reader expects (to avoid {@link ClassCastException}s when the
	 * same SQL is executed with different result types).
	 *
	 * @param resolvedMapping the current result type's mapping
	 * @param storedMapping the compaction mapping stored in cache metadata
	 * @param metadata the cached metadata containing stored Java types
	 */
	private static boolean isCacheCompatible(
			JdbcValuesMapping resolvedMapping,
			int[] storedMapping,
			CachedJdbcValuesMetadata metadata) {
		final int[] readerMapping = resolvedMapping.getValueIndexesToCacheIndexes();
		// Check that every position the reader needs is present in the stored mapping
		for ( int i = 0; i < readerMapping.length; i++ ) {
			if ( readerMapping[i] != -1 ) {
				if ( i >= storedMapping.length || storedMapping[i] == -1 ) {
					return false;
				}
			}
		}
		// Check that the Java types of cached values match the reader's expected types
		for ( var selection : resolvedMapping.getSqlSelections() ) {
			final int valueIndex = selection.getValuesArrayPosition();
			final var storedJavaType = metadata.getStoredJavaType( valueIndex );
			final var expressionType = selection.getExpressionType();
			if ( storedJavaType != null && expressionType != null
					&& expressionType.getSingleJdbcMapping().getJavaTypeDescriptor() != storedJavaType ) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns whether the cached data is compatible with the resolved mapping.
	 * When {@code false}, the cache entry was populated by a query with a different
	 * result type that either cached fewer columns or used different Java types,
	 * and needs to be re-populated.
	 */
	public boolean isCacheCompatible() {
		return cacheCompatible;
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
			return array[valueIndexesToCacheIndexes[valueIndex]];
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
