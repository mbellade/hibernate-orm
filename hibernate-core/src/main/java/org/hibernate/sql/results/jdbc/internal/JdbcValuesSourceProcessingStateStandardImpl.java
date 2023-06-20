/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.results.jdbc.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.engine.spi.CollectionKey;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.EntityUniqueKey;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.service.spi.EventListenerGroup;
import org.hibernate.event.spi.EventSource;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;
import org.hibernate.event.spi.PreLoadEvent;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.sql.exec.spi.Callback;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.results.graph.Initializer;
import org.hibernate.sql.results.graph.collection.CollectionInitializer;
import org.hibernate.sql.results.graph.collection.LoadingCollectionEntry;
import org.hibernate.sql.results.graph.collection.internal.ArrayInitializer;
import org.hibernate.sql.results.graph.entity.LoadingEntityEntry;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesSourceProcessingOptions;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesSourceProcessingState;

/**
 * @author Steve Ebersole
 */
public class JdbcValuesSourceProcessingStateStandardImpl implements JdbcValuesSourceProcessingState {

	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( JdbcValuesSourceProcessingStateStandardImpl.class );

	private final ExecutionContext executionContext;
	private final JdbcValuesSourceProcessingOptions processingOptions;

	private Map<EntityKey, LoadingEntityEntry> loadingEntityMap;
	private Map<EntityKey, LoadingEntityEntry> reloadedEntityMap;
	private Map<EntityUniqueKey, Initializer> initializerByUniquKeyMap;
	private Map<CollectionKey, LoadingCollectionEntry> loadingCollectionMap;
	private List<CollectionInitializer> arrayInitializers;

	private final PreLoadEvent preLoadEvent;
	private final PostLoadEvent postLoadEvent;

	public JdbcValuesSourceProcessingStateStandardImpl(
			ExecutionContext executionContext,
			JdbcValuesSourceProcessingOptions processingOptions) {
		this.executionContext = executionContext;
		this.processingOptions = processingOptions;

		if ( executionContext.getSession().isEventSource() ) {
			final EventSource eventSource = executionContext.getSession().asEventSource();
			preLoadEvent = new PreLoadEvent( eventSource );
			postLoadEvent = new PostLoadEvent( eventSource );
		}
		else {
			preLoadEvent = null;
			postLoadEvent = null;
		}
	}

	@Override
	public ExecutionContext getExecutionContext() {
		return executionContext;
	}

	@Override
	public QueryOptions getQueryOptions() {
		return executionContext.getQueryOptions();
	}

	@Override
	public JdbcValuesSourceProcessingOptions getProcessingOptions() {
		return processingOptions;
	}

	@Override
	public PreLoadEvent getPreLoadEvent() {
		return preLoadEvent;
	}

	@Override
	public PostLoadEvent getPostLoadEvent() {
		return postLoadEvent;
	}

	@Override
	public void registerLoadingEntity(
			EntityKey entityKey,
			LoadingEntityEntry loadingEntry) {
		if ( loadingEntityMap == null ) {
			loadingEntityMap = new HashMap<>();
		}
		loadingEntityMap.put( entityKey, loadingEntry );
	}

	@Override
	public void registerReloadedEntity(EntityKey entityKey, LoadingEntityEntry loadingEntry) {
		if ( reloadedEntityMap == null ) {
			reloadedEntityMap = new HashMap<>();
		}

		reloadedEntityMap.put( entityKey, loadingEntry );
	}

	@Override
	public void registerInitializer(EntityUniqueKey entityKey, Initializer initializer) {
		if ( initializerByUniquKeyMap == null ) {
			initializerByUniquKeyMap = new HashMap<>();
		}
		initializerByUniquKeyMap.put( entityKey, initializer );
	}

	@Override
	public Initializer findInitializer(EntityUniqueKey entityKey) {
		return initializerByUniquKeyMap == null ? null : initializerByUniquKeyMap.get( entityKey );
	}

	@Override
	public LoadingEntityEntry findLoadingEntityLocally(EntityKey entityKey) {
		final LoadingEntityEntry entry = loadingEntityMap == null ? null : loadingEntityMap.get( entityKey );
		if ( entry != null && entry.getDescriptor() == entityKey.getPersister() ) {
			return entry;
		}
		return null;
	}

	@Override
	public LoadingCollectionEntry findLoadingCollectionLocally(CollectionKey key) {
		if ( loadingCollectionMap == null ) {
			return null;
		}

		return loadingCollectionMap.get( key );
	}

	@Override
	public void registerSubselect() {
		if ( loadingEntityMap != null && loadingEntityMap.size() > 1 ) {
			loadingEntityMap.forEach(
					(entityKey, loadingEntityEntry) ->
							executionContext.registerSubselect( entityKey, loadingEntityEntry )
			);
		}
		else {
			LOG.tracef(
					"Skipping create subselects because there are fewer than 2 results, so query by key is more efficient.",
					getClass().getName()
			);
		}
	}

	@Override
	public void registerLoadingCollection(CollectionKey key, LoadingCollectionEntry loadingCollectionEntry) {
		if ( loadingCollectionMap == null ) {
			loadingCollectionMap = new HashMap<>();
		}

		loadingCollectionMap.put( key, loadingCollectionEntry );
		if ( loadingCollectionEntry.getInitializer() instanceof ArrayInitializer ) {
			if ( arrayInitializers == null ) {
				arrayInitializers = new ArrayList<>();
			}
			arrayInitializers.add( loadingCollectionEntry.getInitializer() );
		}
	}

	@Override
	public SharedSessionContractImplementor getSession() {
		return executionContext.getSession();
	}

	@Override
	public void finishUp() {
		// now we can finalize loading collections
		finishLoadingCollections();

		postLoad();
	}

	private void postLoad() {
		final Callback callback = executionContext.getCallback();
		if ( loadingEntityMap != null ) {
			final EventListenerGroup<PostLoadEventListener> listenerGroup = executionContext.getSession().getFactory()
					.getFastSessionServices()
					.eventListenerGroup_POST_LOAD;

			loadingEntityMap.forEach(
					(entityKey, loadingEntityEntry) -> {
						if ( loadingEntityEntry.getEntityInstance() != null ) {
							if ( postLoadEvent != null ) {
								postLoadEvent.reset();
								postLoadEvent.setEntity( loadingEntityEntry.getEntityInstance() )
										.setId( entityKey.getIdentifier() )
										.setPersister( loadingEntityEntry.getDescriptor() );
								listenerGroup.fireEventOnEachListener(
										postLoadEvent,
										PostLoadEventListener::onPostLoad
								);
							}

							if ( callback != null ) {
								callback.invokeAfterLoadActions(
										loadingEntityEntry.getEntityInstance(),
										loadingEntityEntry.getDescriptor(),
										getSession()
								);
							}
						}
					}
			);
		}
		loadingEntityMap = null;

		if ( reloadedEntityMap != null ) {
			if ( callback != null ) {
				reloadedEntityMap.forEach(
						(entityKey, loadingEntityEntry) -> {
							callback.invokeAfterLoadActions(
									loadingEntityEntry.getEntityInstance(),
									loadingEntityEntry.getDescriptor(),
									getSession()
							);
						}
				);
			}
			reloadedEntityMap = null;
		}
	}

	@SuppressWarnings("SimplifiableIfStatement")
	private boolean isReadOnly() {
		if ( getQueryOptions().isReadOnly() != null ) {
			return getQueryOptions().isReadOnly();
		}

		if ( executionContext.getSession() instanceof EventSource ) {
			return executionContext.getSession().isDefaultReadOnly();
		}

		return false;
	}


	private void finishLoadingCollections() {
		if ( loadingCollectionMap != null ) {
			for ( LoadingCollectionEntry loadingCollectionEntry : loadingCollectionMap.values() ) {
				loadingCollectionEntry.finishLoading( getExecutionContext() );
			}

			loadingCollectionMap = null;
		}
	}

}
