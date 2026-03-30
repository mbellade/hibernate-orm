/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.state.internal;

import org.hibernate.mapping.Collection;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.metamodel.mapping.AuditMapping;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.mapping.internal.AuditMappingImpl;
import org.hibernate.metamodel.mapping.internal.MappingModelCreationProcess;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.persister.collection.mutation.DeleteRowsCoordinator;
import org.hibernate.persister.collection.mutation.DeleteRowsCoordinatorNoOp;
import org.hibernate.persister.collection.mutation.DeleteRowsCoordinatorAudit;
import org.hibernate.persister.collection.mutation.InsertRowsCoordinator;
import org.hibernate.persister.collection.mutation.InsertRowsCoordinatorAudit;
import org.hibernate.persister.collection.mutation.InsertRowsCoordinatorNoOp;
import org.hibernate.persister.collection.mutation.RemoveCoordinator;
import org.hibernate.persister.collection.mutation.RemoveCoordinatorAudit;
import org.hibernate.persister.collection.mutation.RemoveCoordinatorNoOp;
import org.hibernate.persister.collection.mutation.UpdateRowsCoordinator;
import org.hibernate.persister.collection.mutation.UpdateRowsCoordinatorAudit;
import org.hibernate.persister.collection.mutation.UpdateRowsCoordinatorNoOp;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.mutation.DeleteCoordinator;
import org.hibernate.persister.entity.mutation.DeleteCoordinatorAudit;
import org.hibernate.persister.entity.mutation.InsertCoordinator;
import org.hibernate.persister.entity.mutation.InsertCoordinatorAudit;
import org.hibernate.persister.entity.mutation.MergeCoordinatorAudit;
import org.hibernate.persister.entity.mutation.UpdateCoordinator;
import org.hibernate.persister.entity.mutation.UpdateCoordinatorAudit;
import org.hibernate.persister.state.spi.StateManagement;

import static org.hibernate.metamodel.mapping.internal.MappingModelCreationHelper.getTableIdentifierExpression;
import static org.hibernate.persister.state.internal.AbstractStateManagement.resolveMutationTarget;

/**
 * State management for {@linkplain org.hibernate.annotations.Audited audited}
 * entities and collections.
 *
 * @author Gavin King
 *
 * @since 7.4
 */
public class AuditStateManagement implements StateManagement {
	public static final AuditStateManagement INSTANCE = new AuditStateManagement();

	private AuditStateManagement() {
	}

	@Override
	public InsertCoordinator createInsertCoordinator(EntityPersister persister) {
		return new InsertCoordinatorAudit( persister, persister.getFactory(),
				StandardStateManagement.INSTANCE.createInsertCoordinator( persister ) );
	}

	@Override
	public UpdateCoordinator createUpdateCoordinator(EntityPersister persister) {
		return new UpdateCoordinatorAudit( persister, persister.getFactory(),
				StandardStateManagement.INSTANCE.createUpdateCoordinator( persister ) );
	}

	@Override
	public UpdateCoordinator createMergeCoordinator(EntityPersister persister) {
		return new MergeCoordinatorAudit( persister, persister.getFactory(),
				StandardStateManagement.INSTANCE.createMergeCoordinator( persister ) );
	}

	@Override
	public DeleteCoordinator createDeleteCoordinator(EntityPersister persister) {
		return new DeleteCoordinatorAudit( persister, persister.getFactory(),
				StandardStateManagement.INSTANCE.createDeleteCoordinator( persister ) );
	}

	@Override
	public InsertRowsCoordinator createInsertRowsCoordinator(CollectionPersister persister) {
		final var mutationTarget = resolveMutationTarget( persister );
		if ( !AbstractStateManagement.isInsertAllowed( persister ) ) {
			return new InsertRowsCoordinatorNoOp( mutationTarget );
		}
		else {
			return new InsertRowsCoordinatorAudit(
					mutationTarget,
					persister.getRowMutationOperations(),
					StandardStateManagement.INSTANCE.createInsertRowsCoordinator( persister ),
					persister.getIndexColumnIsSettable(),
					persister.getElementColumnIsSettable(),
					persister.getIndexIncrementer(),
					persister.getFactory()
			);
		}
	}

	@Override
	public UpdateRowsCoordinator createUpdateRowsCoordinator(CollectionPersister persister) {
		final var mutationTarget = resolveMutationTarget( persister );
		if ( !AbstractStateManagement.isUpdatePossible( persister ) ) {
			return new UpdateRowsCoordinatorNoOp( mutationTarget );
		}
		else if ( persister.isOneToMany() ) {
			return StandardStateManagement.INSTANCE.createUpdateRowsCoordinator( persister );
		}
		else {
			return new UpdateRowsCoordinatorAudit(
					mutationTarget,
					StandardStateManagement.INSTANCE.createUpdateRowsCoordinator( persister ),
					persister.getIndexColumnIsSettable(),
					persister.getElementColumnIsSettable(),
					persister.getIndexIncrementer(),
					persister.getFactory()
			);
		}
	}

	@Override
	public DeleteRowsCoordinator createDeleteRowsCoordinator(CollectionPersister persister) {
		final var mutationTarget = resolveMutationTarget( persister );
		if ( !persister.needsRemove() ) {
			return new DeleteRowsCoordinatorNoOp( mutationTarget );
		}
		else {
			return new DeleteRowsCoordinatorAudit(
					mutationTarget,
					StandardStateManagement.INSTANCE.createDeleteRowsCoordinator( persister ),
					mutationTarget.hasPhysicalIndexColumn(),
					persister.getIndexColumnIsSettable(),
					persister.getElementColumnIsSettable(),
					persister.getIndexIncrementer(),
					persister.getFactory()
			);
		}
	}

	@Override
	public RemoveCoordinator createRemoveCoordinator(CollectionPersister persister) {
		if ( !persister.needsRemove() ) {
			return new RemoveCoordinatorNoOp( resolveMutationTarget( persister ) );
		}
		return new RemoveCoordinatorAudit(
				resolveMutationTarget( persister ),
				StandardStateManagement.INSTANCE.createRemoveCoordinator( persister ),
				persister.getIndexColumnIsSettable(),
				persister.getElementColumnIsSettable(),
				persister.getIndexIncrementer(),
				persister.getFactory()
		);
	}

	@Override
	public AuditMapping createAuxiliaryMapping(
			EntityPersister persister,
			PersistentClass bootDescriptor,
			MappingModelCreationProcess creationProcess) {
		final var rootClass = bootDescriptor.getRootClass();
		final var auditTable = bootDescriptor.getAuxiliaryTable() != null
				? bootDescriptor.getAuxiliaryTable()
				: rootClass.getAuxiliaryTable();
		final String tableName = auditTable == null
				? persister.getIdentifierTableName()
				: ( (AbstractEntityPersister) persister )
						.determineTableName( auditTable );
		return new AuditMappingImpl( rootClass, tableName, creationProcess );
	}

	@Override
	public AuditMapping createAuxiliaryMapping(
			PluralAttributeMapping pluralAttributeMapping,
			Collection bootDescriptor,
			MappingModelCreationProcess creationProcess) {
		final var auditTable = bootDescriptor.getAuxiliaryTable();
		if ( auditTable == null ) {
			// No audit table for this collection (e.g. @OneToMany @JoinColumn —
			// the child entity's audit table handles FK auditing)
			return null;
		}
		final String tableName = getTableIdentifierExpression( auditTable, creationProcess );
		return new AuditMappingImpl( bootDescriptor, tableName, creationProcess );
	}

}
