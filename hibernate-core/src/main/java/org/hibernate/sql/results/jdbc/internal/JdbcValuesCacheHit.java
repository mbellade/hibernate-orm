/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
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

		final int[] mappingIndexes = resolvedMapping.getValueIndexesToCacheIndexes();
		// When the cached row was stored with identity compaction (all result set columns
		// at their original valuesArrayPosition), and this mapping compacts differently
		// (caches fewer columns), bypass compaction and read directly by position.
		// This handles the case where e.g. a Tuple result cached all columns, and an
		// Entity result reads from the same cache entry using different column positions.
		if ( mappingIndexes.length > 0 && numberOfRows > 0 && hasMetadata ) {
			final CachedJdbcValuesMetadata metadata = (CachedJdbcValuesMetadata) cachedResults.get( 0 );
			final Object firstRow = cachedResults.get( offset );
			final int cachedRowSize = firstRow instanceof Object[] ? ( (Object[]) firstRow ).length : 1;
			if ( cachedRowSize == metadata.getColumnCount()
					&& cachedRowSize > resolvedMapping.getRowToCacheSize() ) {
				this.valueIndexesToCacheIndexes = null;
			}
			else {
				this.valueIndexesToCacheIndexes = mappingIndexes;
			}
		}
		else {
			this.valueIndexesToCacheIndexes = mappingIndexes;
		}
	}

	/**
	 * Returns whether the cached data has enough columns for the resolved mapping.
	 * When {@code false}, the cache entry was populated by a different result type
	 * (e.g. entity) that cached fewer columns and needs to be re-populated.
	 */
	public boolean isDataSufficient() {
		if ( numberOfRows > 0 ) {
			final Object firstRow = cachedResults.get( offset );
			final int cachedRowSize; // = firstRow instanceof Object[] array ? array.length : 1;
			if ( firstRow instanceof Object[] ) {
				cachedRowSize = ( (Object[]) firstRow ).length;
			}
			else {
				cachedRowSize = 1;
			}
			return cachedRowSize >= resolvedMapping.getRowToCacheSize();
		}
		return true;
	}

	@Override
	protected boolean processNext(RowProcessingState rowProcessingState) {
		// NOTE : explicitly skipping limit handling because the cached state ought
		// 		already be the limited size since the cache key includes limits

		position++;

		if ( position >= numberOfRows ) {
			position = numberOfRows;
			return false;
		}

		return true;
	}

	@Override
	protected boolean processPrevious(RowProcessingState rowProcessingState) {
		// NOTE : explicitly skipping limit handling because the cached state ought
		// 		already be the limited size since the cache key includes limits

		position--;

		if ( position >= numberOfRows ) {
			position = numberOfRows;
			return false;
		}

		return true;
	}

	@Override
	protected boolean processScroll(int numberOfRows, RowProcessingState rowProcessingState) {
		// NOTE : explicitly skipping limit handling because the cached state should
		// 		already be the limited size since the cache key includes limits

		position += numberOfRows;

		if ( position > this.numberOfRows ) {
			position = this.numberOfRows;
			return false;
		}

		return true;
	}

	@Override
	public int getPosition() {
		return position;
	}

	@Override
	protected boolean processPosition(int position, RowProcessingState rowProcessingState) {
		// NOTE : explicitly skipping limit handling because the cached state should
		// 		already be the limited size since the cache key includes limits

		if ( position < 0 ) {
			// we need to subtract it from `numberOfRows`
			position = numberOfRows + position;
		}

		if ( position > numberOfRows ) {
			this.position = numberOfRows;
			return false;
		}

		this.position = position;
		return true;
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
		if ( numberOfRows == 0 ) {
			return position == 0;
		}
		else {
			return position == numberOfRows - 1;
		}
	}

	@Override
	public boolean last(RowProcessingState rowProcessingState) {
		if ( numberOfRows == 0 ) {
			position = 0;
			return false;
		}

		position = numberOfRows - 1;
		return true;
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
		if ( valueIndexesToCacheIndexes == null ) {
			return ( (Object[]) row )[valueIndex];
		}
		else if ( row instanceof Object[] ) {
			return ( (Object[]) row )[valueIndexesToCacheIndexes[valueIndex]];
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
