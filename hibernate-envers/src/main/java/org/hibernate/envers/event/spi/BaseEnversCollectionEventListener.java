/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.envers.event.spi;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.CollectionEntry;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.envers.internal.entities.RelationDescription;
import org.hibernate.envers.internal.entities.RelationType;
import org.hibernate.envers.internal.synchronization.AuditProcess;
import org.hibernate.envers.internal.synchronization.work.AuditWorkUnit;
import org.hibernate.envers.internal.synchronization.work.CollectionChangeWorkUnit;
import org.hibernate.envers.internal.synchronization.work.FakeBidirectionalRelationWorkUnit;
import org.hibernate.envers.internal.synchronization.work.PersistentCollectionChangeWorkUnit;
import org.hibernate.event.spi.AbstractCollectionEvent;
import org.hibernate.persister.collection.AbstractCollectionPersister;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.collection.OneToManyPersister;

import java.io.Serializable;

/**
 * Base class for Envers' collection event related listeners
 *
 * @author Adam Warski (adam at warski dot org)
 * @author HernпїЅn Chanfreau
 * @author Steve Ebersole
 * @author Michal Skowronek (mskowr at o2 dot pl)
 * @author Lukasz Antoniak (lukasz dot antoniak at gmail dot com)
 * @author Chris Cranford
 */
public abstract class BaseEnversCollectionEventListener extends BaseEnversEventListener {
	protected BaseEnversCollectionEventListener(EnversService enversService) {
		super( enversService );
	}

	/**
	 * @deprecated Since 7.2, this event can be triggered from {@link org.hibernate.StatelessSession}s, in which
	 * case the session is not available. Use {@link AbstractCollectionEvent#getSession()} directly.
	 */
	@Deprecated(since = "7.2", forRemoval = true)
	protected final CollectionEntry getCollectionEntry(AbstractCollectionEvent event) {
		return event.getSession().getPersistenceContextInternal().getCollectionEntry( event.getCollection() );
	}

	/**
	 * @deprecated Use {@link #onCollectionAction(AbstractCollectionEvent, PersistentCollection, Serializable, CollectionPersister)}
	 */
	@Deprecated(since = "7.2", forRemoval = true)
	protected final void onCollectionAction(
			AbstractCollectionEvent event,
			PersistentCollection newColl,
			Serializable oldColl,
			CollectionEntry collectionEntry) {
		onCollectionAction( event, newColl, oldColl, collectionEntry.getLoadedPersister() );
	}

	protected final void onCollectionAction(
			AbstractCollectionEvent event,
			PersistentCollection<?> newColl,
			Serializable oldColl,
			CollectionPersister persister) {
		if ( shouldGenerateRevision( event ) ) {
			checkIfTransactionInProgress( event.getSession() );

			final var auditProcess = getEnversService().getAuditProcessManager().get( event.getSession() );

			final var entityName = event.getAffectedOwnerEntityName();
			final var ownerEntityName = ((AbstractCollectionPersister) persister).getOwnerEntityName();
			final var role = persister.getRole();
			final var referencingPropertyName = role.substring( ownerEntityName.length() + 1 );

			// Checking if this is not a "fake" many-to-one bidirectional relation. The relation description may be
			// null in case of collections of non-entities.
			final var rd = searchForRelationDescription( entityName, referencingPropertyName );
			if ( rd != null && rd.getMappedByPropertyName() != null ) {
				generateFakeBidirecationalRelationWorkUnits(
						auditProcess,
						newColl,
						oldColl,
						entityName,
						referencingPropertyName,
						event,
						rd
				);
			}
			else {
				final var workUnit = new PersistentCollectionChangeWorkUnit(
						event.getSession(),
						entityName,
						getEnversService(),
						newColl,
						role,
						oldColl,
						event.getAffectedOwnerIdOrNull(),
						referencingPropertyName
				);
				auditProcess.addWorkUnit( workUnit );

				if ( workUnit.containsWork() ) {
					// There are some changes: a revision needs also be generated for the collection owner
					auditProcess.addWorkUnit(
							new CollectionChangeWorkUnit(
									event.getSession(),
									event.getAffectedOwnerEntityName(),
									referencingPropertyName,
									getEnversService(),
									event.getAffectedOwnerIdOrNull(),
									event.getAffectedOwnerOrNull()
							)
					);

					generateBidirectionalCollectionChangeWorkUnits( auditProcess, event, workUnit, rd );
				}
			}
		}
	}

	/**
	 * @deprecated Use {@link #onCollectionActionInversed(AbstractCollectionEvent, PersistentCollection, Serializable, CollectionPersister)}
	 */
	@Deprecated(since = "7.2", forRemoval = true)
	protected final void onCollectionActionInversed(
			AbstractCollectionEvent event,
			PersistentCollection newColl,
			Serializable oldColl,
			CollectionEntry collectionEntry) {
		onCollectionActionInversed( event, newColl, oldColl, collectionEntry.getLoadedPersister() );
	}

	protected final void onCollectionActionInversed(
			AbstractCollectionEvent event,
			PersistentCollection<?> newColl,
			Serializable oldColl,
			CollectionPersister persister) {
		if ( shouldGenerateRevision( event ) ) {
			final var entityName = event.getAffectedOwnerEntityName();
			final var ownerEntityName = ((AbstractCollectionPersister) persister).getOwnerEntityName();
			final var role = persister.getRole();
			final var referencingPropertyName = role.substring( ownerEntityName.length() + 1 );

			final var rd = searchForRelationDescription( entityName, referencingPropertyName );
			if ( rd != null ) {
				if ( rd.getRelationType().equals( RelationType.TO_MANY_NOT_OWNING ) && rd.isIndexed() ) {
					onCollectionAction( event, newColl, oldColl, persister );
				}
			}
		}
	}

	/**
	 * Forces persistent collection initialization.
	 *
	 * @param event Collection event.
	 * @return Stored snapshot.
	 */
	protected Serializable initializeCollection(AbstractCollectionEvent event) {
		event.getCollection().forceInitialization();
		return event.getCollection().getStoredSnapshot();
	}

	/**
	 * Checks whether modification of not-owned relation field triggers new revision and owner entity is versioned.
	 *
	 * @param event Collection event.
	 * @return {@code true} if revision based on given event should be generated, {@code false} otherwise.
	 */
	protected boolean shouldGenerateRevision(AbstractCollectionEvent event) {
		final var entityName = event.getAffectedOwnerEntityName();
		if ( getEnversService().getEntitiesConfigurations().isVersioned( entityName ) ) {
			final var session = event.getCollection().getSession();
			final var persister = session.getFactory().getMappingMetamodel()
					.getCollectionDescriptor( event.getCollection().getRole() );
			final var isInverse = persister.isInverse();
			final var isOneToMany = persister instanceof OneToManyPersister;
			if ( isInverse || isOneToMany ) {
				return getEnversService().getConfig().isGenerateRevisionsForCollections();
			}
			return true;
		}
		// if the entity is not audited, we dont generate a revision.
		return false;
	}

	/**
	 * Looks up a relation description corresponding to the given property in the given entity. If no description is
	 * found in the given entity, the parent entity is checked (so that inherited relations work).
	 *
	 * @param entityName Name of the entity, in which to start looking.
	 * @param referencingPropertyName The name of the property.
	 * @return A found relation description corresponding to the given entity or {@code null}, if no description can
	 * be found.
	 */
	private RelationDescription searchForRelationDescription(String entityName, String referencingPropertyName) {
		final var configuration = getEnversService().getEntitiesConfigurations().get( entityName );
		final var propertyName = sanitizeReferencingPropertyName( referencingPropertyName );
		final var rd = configuration.getRelationDescription( propertyName );
		if ( rd == null && configuration.getParentEntityName() != null ) {
			return searchForRelationDescription( configuration.getParentEntityName(), propertyName );
		}

		return rd;
	}

	private String sanitizeReferencingPropertyName(String propertyName) {
		if ( propertyName != null && propertyName.indexOf( '.' ) != -1 ) {
			return propertyName.replaceAll( "\\.", "\\_" );
		}
		return propertyName;
	}

	private void generateFakeBidirecationalRelationWorkUnits(
			AuditProcess auditProcess,
			PersistentCollection newColl,
			Serializable oldColl,
			String collectionEntityName,
			String referencingPropertyName,
			AbstractCollectionEvent event,
			RelationDescription rd) {
		// First computing the relation changes
		final var propertyMapper = getEnversService()
				.getEntitiesConfigurations()
				.get( collectionEntityName )
				.getPropertyMapper();
		final var collectionChanges = propertyMapper.mapCollectionChanges(
				event.getSession(),
				referencingPropertyName,
				newColl,
				oldColl,
				event.getAffectedOwnerIdOrNull()
		);

		// Getting the id mapper for the related entity, as the work units generated will correspond to the related
		// entities.
		final var relatedEntityName = rd.getToEntityName();
		final var relatedIdMapper = getEnversService().getEntitiesConfigurations().get( relatedEntityName )
				.getIdMapper();

		// For each collection change, generating the bidirectional work unit.
		for ( var changeData : collectionChanges ) {
			final var relatedObj = changeData.getChangedElement();
			final var relatedId = (Serializable) relatedIdMapper.mapToIdFromEntity( relatedObj );
			final var revType = (RevisionType) changeData.getData().get(
					getEnversService().getConfig().getRevisionTypePropertyName()
			);

			// This can be different from relatedEntityName, in case of inheritance (the real entity may be a subclass
			// of relatedEntityName).
			final var realRelatedEntityName = event.getSession().bestGuessEntityName( relatedObj );

			// By default, the nested work unit is a collection change work unit.
			final AuditWorkUnit nestedWorkUnit = new CollectionChangeWorkUnit(
					event.getSession(),
					realRelatedEntityName,
					rd.getMappedByPropertyName(),
					getEnversService(),
					relatedId,
					relatedObj
			);

			auditProcess.addWorkUnit(
					new FakeBidirectionalRelationWorkUnit(
							event.getSession(),
							realRelatedEntityName,
							getEnversService(),
							relatedId,
							referencingPropertyName,
							event.getAffectedOwnerOrNull(),
							rd,
							revType,
							changeData.getChangedElementIndex(),
							nestedWorkUnit
					)
			);
		}

		// We also have to generate a collection change work unit for the owning entity.
		auditProcess.addWorkUnit(
				new CollectionChangeWorkUnit(
						event.getSession(),
						collectionEntityName,
						referencingPropertyName,
						getEnversService(),
						event.getAffectedOwnerIdOrNull(),
						event.getAffectedOwnerOrNull()
				)
		);
	}

	private void generateBidirectionalCollectionChangeWorkUnits(
			AuditProcess auditProcess,
			AbstractCollectionEvent event,
			PersistentCollectionChangeWorkUnit workUnit,
			RelationDescription rd) {
		// Checking if this is enabled in configuration ...
		if ( !getEnversService().getConfig().isGenerateRevisionsForCollections() ) {
			return;
		}

		// Checking if this is not a bidirectional relation - then, a revision needs also be generated for
		// the other side of the relation.
		// relDesc can be null if this is a collection of simple values (not a relation).
		if ( rd != null && rd.isBidirectional() ) {
			final var relatedEntityName = rd.getToEntityName();
			final var relatedIdMapper = getEnversService().getEntitiesConfigurations().get( relatedEntityName )
					.getIdMapper();

			final var toPropertyNames = getEnversService().getEntitiesConfigurations().getToPropertyNames(
					event.getAffectedOwnerEntityName(),
					rd.getFromPropertyName(),
					relatedEntityName
			);
			final var toPropertyName = toPropertyNames.iterator().next();

			for ( var changeData : workUnit.getCollectionChanges() ) {
				final var relatedObj = changeData.getChangedElement();
				final var relatedId = (Serializable) relatedIdMapper.mapToIdFromEntity( relatedObj );

				auditProcess.addWorkUnit(
						new CollectionChangeWorkUnit(
								event.getSession(),
								event.getSession().bestGuessEntityName( relatedObj ),
								toPropertyName,
								getEnversService(),
								relatedId,
								relatedObj
						)
				);
			}
		}
	}
}
