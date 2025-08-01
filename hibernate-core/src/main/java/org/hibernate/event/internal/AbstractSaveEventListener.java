/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.event.internal;

import java.util.Map;

import org.hibernate.LockMode;
import org.hibernate.NonUniqueObjectException;
import org.hibernate.action.internal.AbstractEntityInsertAction;
import org.hibernate.action.internal.EntityIdentityInsertAction;
import org.hibernate.action.internal.EntityInsertAction;
import org.hibernate.engine.internal.Cascade;
import org.hibernate.engine.internal.CascadePoint;
import org.hibernate.engine.spi.CascadingAction;
import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.EntityEntryExtraState;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.SelfDirtinessTracker;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.Status;
import org.hibernate.event.spi.EventSource;
import org.hibernate.id.CompositeNestedGeneratedValueGenerator;
import org.hibernate.id.IdentifierGenerationException;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.jpa.event.spi.CallbackRegistry;
import org.hibernate.jpa.event.spi.CallbackRegistryConsumer;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.generator.Generator;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.type.Type;
import org.hibernate.type.TypeHelper;

import static org.hibernate.engine.internal.ManagedTypeHelper.processIfSelfDirtinessTracker;
import static org.hibernate.engine.internal.ManagedTypeHelper.processIfManagedEntity;
import static org.hibernate.engine.internal.Versioning.getVersion;
import static org.hibernate.engine.internal.Versioning.seedVersion;
import static org.hibernate.generator.EventType.INSERT;
import static org.hibernate.id.IdentifierGeneratorHelper.SHORT_CIRCUIT_INDICATOR;
import static org.hibernate.pretty.MessageHelper.infoString;

/**
 * A convenience base class for listeners responding to persist or merge events.
 * <p>
 * This class contains common functionality for persisting new transient instances.
 *
 * @author Steve Ebersole.
 */
public abstract class AbstractSaveEventListener<C> implements CallbackRegistryConsumer {
	private static final CoreMessageLogger LOG = CoreLogging.messageLogger( AbstractSaveEventListener.class );

	private CallbackRegistry callbackRegistry;

	@Override
	public void injectCallbackRegistry(CallbackRegistry callbackRegistry) {
		this.callbackRegistry = callbackRegistry;
	}

	/**
	 * Prepares the persist call using the given requested id.
	 *
	 * @param entity The entity to be persisted
	 * @param requestedId The id with which to associate the entity
	 * @param entityName The name of the entity being persisted
	 * @param context Generally cascade-specific information
	 * @param source The session which is the source of this event
	 *
	 * @return The id used to save the entity.
	 */
	protected Object saveWithRequestedId(
			Object entity,
			Object requestedId,
			String entityName,
			C context,
			EventSource source) {
		final EntityPersister persister = source.getEntityPersister( entityName, entity );
		return performSave( entity, requestedId, persister, false, context, source, false );
	}

	/**
	 * Prepares the persist call using a newly generated id.
	 *
	 * @param entity The entity to be persisted
	 * @param entityName The entity-name for the entity to be persisted
	 * @param context Generally cascade-specific information
	 * @param source The session which is the source of this persist event
	 * @param requiresImmediateIdAccess does the event context require
	 * access to the identifier immediately after execution of this method
	 * (if not, post-insert style id generators may be postponed if we are
	 * outside a transaction).
	 *
	 * @return The id used to persist the entity; may be null depending on the
	 *         type of id generator used and the requiresImmediateIdAccess value
	 */
	protected Object saveWithGeneratedId(
			Object entity,
			String entityName,
			C context,
			EventSource source,
			boolean requiresImmediateIdAccess) {
		final EntityPersister persister = source.getEntityPersister( entityName, entity );
		final Generator generator = persister.getGenerator();
		final boolean generatedOnExecution = generator.generatedOnExecution( entity, source );
		final boolean generatedBeforeExecution = generator.generatedBeforeExecution( entity, source );
		final Object generatedId;
		if ( generatedOnExecution ) {
			// the id gets generated by the database
			// and is not yet available
			generatedId = null;
		}
		else if ( !generator.generatesOnInsert() ) {
			// get it from the entity later, since we need
			// the @PrePersist callback to happen first
			generatedId = null;
		}
		else if ( generatedBeforeExecution ) {
			// go ahead and generate id, and then set it to
			// the entity instance, so it will be available
			// to the entity in the @PrePersist callback
			generatedId = generateId( entity, source, (BeforeExecutionGenerator) generator, persister );
			if ( generatedId == SHORT_CIRCUIT_INDICATOR ) {
				return source.getIdentifier( entity );
			}
			persister.setIdentifier( entity, generatedId, source );
		}
		else {
			// the generator is refusing to generate anything
			// so use the identifier currently assigned
			generatedId = persister.getIdentifier( entity, source );
		}
		final boolean delayIdentityInserts =
				!source.isTransactionInProgress()
						&& !requiresImmediateIdAccess
						&& generatedOnExecution;
		return performSave( entity, generatedId, persister, generatedOnExecution, context, source, delayIdentityInserts );
	}

	/**
	 * Generate an id before execution of the insert statements,
	 * using the given {@link BeforeExecutionGenerator}.
	 *
	 * @param entity The entity instance to be persisted
	 * @param source The session which is the source of this persist event
	 * @param generator The generator for the entity id
	 * @param persister The persister for the entity
	 *
	 * @return The generated id
	 */
	private static Object generateId(
			Object entity,
			EventSource source,
			BeforeExecutionGenerator generator,
			EntityPersister persister) {
		final Object currentValue = generator.allowAssignedIdentifiers() ? persister.getIdentifier( entity ) : null;
		final Object id = generator.generate( source, entity, currentValue, INSERT );
		if ( id == null ) {
			throw new IdentifierGenerationException( "Null id generated for entity '" + persister.getEntityName() + "'" );
		}
		else {
			if ( LOG.isTraceEnabled() ) {
				// TODO: define toString()s for generators
				LOG.tracef(
						"Generated identifier [%s] using generator '%s'",
						persister.getIdentifierType().toLoggableString( id, source.getFactory() ),
						generator.getClass().getName()
				);
			}
			return id;
		}
	}

	/**
	 * Prepares the persist call by checking the session caches for a pre-existing
	 * entity and performing any lifecycle callbacks.
	 *
	 * @param entity The entity to be persisted
	 * @param id The id by which to persist the entity
	 * @param persister The entity's persister instance
	 * @param useIdentityColumn Is an identity column being used?
	 * @param context Generally cascade-specific information
	 * @param source The session from which the event originated
	 * @param delayIdentityInserts Should the identity insert be delayed?
	 *
	 * @return The id used to persist the entity; may be null depending on the
	 *         type of id generator used and on delayIdentityInserts
	 */
	protected Object performSave(
			Object entity,
			Object id,
			EntityPersister persister,
			boolean useIdentityColumn,
			C context,
			EventSource source,
			boolean delayIdentityInserts) {

		// call this after generation of an id,
		// but before we retrieve an assigned id
		callbackRegistry.preCreate( entity );

		processIfSelfDirtinessTracker( entity, SelfDirtinessTracker::$$_hibernate_clearDirtyAttributes );
		processIfManagedEntity( entity, (managedEntity) -> managedEntity.$$_hibernate_setUseTracker( true ) );

		final Generator generator = persister.getGenerator();
		if ( !generator.generatesOnInsert() || generator instanceof CompositeNestedGeneratedValueGenerator ) {
			id = persister.getIdentifier( entity, source );
			if ( id == null ) {
				throw new IdentifierGenerationException( "Identifier of entity '" + persister.getEntityName()
						+ "' must be manually assigned before calling 'persist()'" );
			}
		}

		if ( LOG.isTraceEnabled() ) {
			LOG.trace( "Persisting " + infoString( persister, id, source.getFactory() ) );
		}

		final EntityKey key = useIdentityColumn ? null : entityKey( id, persister, source );
		return performSaveOrReplicate( entity, key, persister, useIdentityColumn, context, source, delayIdentityInserts );
	}

	private static EntityKey entityKey(Object id, EntityPersister persister, EventSource source) {
		final EntityKey key = source.generateEntityKey( id, persister );
		final PersistenceContext persistenceContext = source.getPersistenceContextInternal();
		final Object old = persistenceContext.getEntity( key );
		if ( old != null ) {
			if ( persistenceContext.getEntry( old ).getStatus() == Status.DELETED ) {
				source.forceFlush( persistenceContext.getEntry( old ) );
			}
			else {
				throw new NonUniqueObjectException( id, persister.getEntityName() );
			}
		}
		else if ( persistenceContext.containsDeletedUnloadedEntityKey( key ) ) {
			source.forceFlush( key );
		}
		return key;
	}

	/**
	 * Performs all the actual work needed to persist an entity
	 * (well to get the persist action moved to the execution queue).
	 *
	 * @param entity The entity to be persisted
	 * @param key The id to be used for saving the entity (or null, in the case of identity columns)
	 * @param persister The persister for the entity
	 * @param useIdentityColumn Should an identity column be used for id generation?
	 * @param context Generally cascade-specific information
	 * @param source The session which is the source of the current event
	 * @param delayIdentityInserts Should the identity insert be delayed?
	 *
	 * @return The id used to persist the entity; may be null depending on the
	 *         type of id generator used and the requiresImmediateIdAccess value
	 */
	protected Object performSaveOrReplicate(
			Object entity,
			EntityKey key,
			EntityPersister persister,
			boolean useIdentityColumn,
			C context,
			EventSource source,
			boolean delayIdentityInserts) {

		final Object id = key == null ? null : key.getIdentifier();

		final PersistenceContext persistenceContext = source.getPersistenceContextInternal();

		// Put a placeholder in entries, so we don't recurse back and try to save() the
		// same object again. QUESTION: should this be done before onSave() is called?
		// likewise, should it be done before onUpdate()?
		final EntityEntry original = persistenceContext.addEntry(
				entity,
				Status.SAVING,
				null,
				null,
				id,
				null,
				LockMode.WRITE,
				useIdentityColumn,
				persister,
				false
		);
		if ( original.getLoadedState() != null ) {
			persistenceContext.getEntityHolder( key ).setEntityEntry( original );
		}

		cascadeBeforeSave( source, persister, entity, context );

		final AbstractEntityInsertAction insert = addInsertAction(
				cloneAndSubstituteValues( entity, persister, context, source, id ),
				id,
				entity,
				persister,
				useIdentityColumn,
				source,
				delayIdentityInserts
		);

		// postpone initializing id in case the insert has non-nullable transient dependencies
		// that are not resolved until cascadeAfterSave() is executed
		cascadeAfterSave( source, persister, entity, context );

		final Object finalId = handleGeneratedId( useIdentityColumn, id, insert );

		final EntityEntry newEntry = persistenceContext.getEntry( entity );
		if ( newEntry != original ) {
			final EntityEntryExtraState extraState = newEntry.getExtraState( EntityEntryExtraState.class );
			if ( extraState == null ) {
				newEntry.addExtraState( original.getExtraState( EntityEntryExtraState.class ) );
			}
		}

		return finalId;
	}

	private static Object handleGeneratedId(boolean useIdentityColumn, Object id, AbstractEntityInsertAction insert) {
		if ( useIdentityColumn && insert.isEarlyInsert() ) {
			if ( insert instanceof EntityIdentityInsertAction entityIdentityInsertAction ) {
				final Object generatedId = entityIdentityInsertAction.getGeneratedId();
				insert.handleNaturalIdPostSaveNotifications( generatedId );
				return generatedId;
			}
			else {
				throw new IllegalStateException(
						"Insert should be using an identity column, but action is of unexpected type: "
								+ insert.getClass().getName()
				);
			}
		}
		else {
			return id;
		}
	}

	private Object[] cloneAndSubstituteValues(Object entity, EntityPersister persister, C context, EventSource source, Object id) {
		final Object[] values = persister.getPropertyValuesToInsert( entity, getMergeMap( context ), source );
		final Type[] types = persister.getPropertyTypes();

		boolean substitute = substituteValuesIfNecessary( entity, id, values, persister, source );
		if ( persister.hasCollections() ) {
			substitute = visitCollectionsBeforeSave( entity, id, values, types, source ) || substitute;
		}

		if ( substitute ) {
			persister.setValues( entity, values );
		}

		TypeHelper.deepCopy(
				values,
				types,
				persister.getPropertyUpdateability(),
				values,
				source
		);
		return values;
	}

	private AbstractEntityInsertAction addInsertAction(
			Object[] values,
			Object id,
			Object entity,
			EntityPersister persister,
			boolean useIdentityColumn,
			EventSource source,
			boolean delayIdentityInserts) {
		if ( useIdentityColumn ) {
			final EntityIdentityInsertAction insert = new EntityIdentityInsertAction(
					values,
					entity,
					persister,
					isVersionIncrementDisabled(),
					source,
					delayIdentityInserts
			);
			source.getActionQueue().addAction( insert );
			return insert;
		}
		else {
			final EntityInsertAction insert = new EntityInsertAction(
					id,
					values,
					entity,
					getVersion( values, persister ),
					persister,
					isVersionIncrementDisabled(),
					source
			);
			source.getActionQueue().addAction( insert );
			return insert;
		}
	}

	protected Map<Object,Object> getMergeMap(C anything) {
		return null;
	}

	/**
	 * After the persist, will the version number be incremented
	 * if the instance is modified?
	 *
	 * @return True if the version will be incremented on an entity change after persist;
	 *         false otherwise.
	 */
	protected boolean isVersionIncrementDisabled() {
		return false;
	}

	protected boolean visitCollectionsBeforeSave(
			Object entity,
			Object id,
			Object[] values,
			Type[] types,
			EventSource source) {
		final WrapVisitor visitor = new WrapVisitor( entity, id, source );
		// substitutes into values by side effect
		visitor.processEntityPropertyValues( values, types );
		return visitor.isSubstitutionRequired();
	}

	/**
	 * Perform any property value substitution that is necessary
	 * (interceptor callback, version initialization...)
	 *
	 * @param entity The entity
	 * @param id The entity identifier
	 * @param values The snapshot entity state
	 * @param persister The entity persister
	 * @param source The originating session
	 *
	 * @return True if the snapshot state changed such that
	 *         reinjection of the values into the entity is required.
	 */
	protected boolean substituteValuesIfNecessary(
			Object entity,
			Object id,
			Object[] values,
			EntityPersister persister,
			SessionImplementor source) {
		boolean substitute = source.getInterceptor().onPersist(
				entity,
				id,
				values,
				persister.getPropertyNames(),
				persister.getPropertyTypes()
		);

		//keep the existing version number in the case of replicate!
		if ( persister.isVersioned() ) {
			substitute = seedVersion( entity, values, persister, source ) || substitute;
		}
		return substitute;
	}

	/**
	 * Handles the calls needed to perform pre-persist cascades for the given entity.
	 *
	 * @param source The session from which the persist event originated
	 * @param persister The persister for the entity
	 * @param entity The entity to be persisted
	 * @param context Generally cascade-specific data
	 */
	protected void cascadeBeforeSave(
			EventSource source,
			EntityPersister persister,
			Object entity,
			C context) {
		// cascade-save to many-to-one BEFORE the parent is saved
		final PersistenceContext persistenceContext = source.getPersistenceContextInternal();
		persistenceContext.incrementCascadeLevel();
		try {
			Cascade.cascade(
					getCascadeAction(),
					CascadePoint.BEFORE_INSERT_AFTER_DELETE,
					source,
					persister,
					entity,
					context
			);
		}
		finally {
			persistenceContext.decrementCascadeLevel();
		}
	}

	/**
	 * Handles calls needed to perform post-persist cascades.
	 *
	 * @param source The session from which the event originated
	 * @param persister The persister for the entity
	 * @param entity The entity being persisted
	 * @param context Generally cascade-specific data
	 */
	protected void cascadeAfterSave(
			EventSource source,
			EntityPersister persister,
			Object entity,
			C context) {
		// cascade-save to collections AFTER the collection owner was saved
		final PersistenceContext persistenceContext = source.getPersistenceContextInternal();
		persistenceContext.incrementCascadeLevel();
		try {
			Cascade.cascade(
					getCascadeAction(),
					CascadePoint.AFTER_INSERT_BEFORE_DELETE,
					source,
					persister,
					entity,
					context
			);
		}
		finally {
			persistenceContext.decrementCascadeLevel();
		}
	}

	protected abstract CascadingAction<C> getCascadeAction();

}
