/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.state.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.hibernate.audit.ModificationType;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.RootClass;
import org.hibernate.mapping.Stateful;
import org.hibernate.mapping.Table;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.metamodel.mapping.internal.SelectableMappingImpl;
import org.hibernate.type.spi.TypeConfiguration;

import static org.hibernate.boot.model.internal.AuditHelper.MODIFICATION_TYPE;
import static org.hibernate.boot.model.internal.AuditHelper.TRANSACTION_ID;
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
import org.hibernate.persister.entity.UnionSubclassEntityPersister;
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
					StandardStateManagement.INSTANCE.createInsertRowsCoordinator( persister ),
					mutationTarget.hasPhysicalIndexColumn(),
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
					StandardStateManagement.INSTANCE.createDeleteRowsCoordinator( persister )
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
		// Subclasses inherit the root's multi-table mapping
		if ( bootDescriptor != rootClass ) {
			final var superType = persister.getSuperMappingType();
			return superType != null ? superType.getAuditMapping() : null;
		}
		final var aep = (AbstractEntityPersister) persister;
		final var tableAuditInfoMap = buildTableAuditInfoMap( rootClass, aep, creationProcess );
		return new AuditMappingImpl( tableAuditInfoMap, creationProcess );
	}

	private static Map<String, AuditMappingImpl.TableAuditInfo> buildTableAuditInfoMap(
			RootClass rootClass,
			AbstractEntityPersister persister,
			MappingModelCreationProcess creationProcess) {
		final var typeConfiguration = creationProcess.getCreationContext().getTypeConfiguration();
		final var sessionFactory = creationProcess.getCreationContext().getSessionFactory();
		final var txIdJdbcMapping = resolveJdbcMapping( typeConfiguration,
				sessionFactory.getTransactionIdentifierService().getIdentifierType() );
		final var modTypeJdbcMapping = resolveJdbcMapping( typeConfiguration, ModificationType.class );

		final var map = new HashMap<String, AuditMappingImpl.TableAuditInfo>();

		// Root table entry
		addTableAuditInfo( map, rootClass.getTable(), rootClass.getAuxiliaryTable(),
				rootClass, persister, txIdJdbcMapping, modTypeJdbcMapping, creationProcess );

		// For TABLE_PER_CLASS, prepare audit subquery generation context
		// (the tableNameResolver lambda captures the map, so it resolves lazily)
		final Function<String, String> tableNameResolver;
		final List<String> extraColumns;
		if ( persister instanceof UnionSubclassEntityPersister ) {
			tableNameResolver = originalName -> {
				final var info = map.get( originalName );
				return info != null ? info.auditTableName() : originalName;
			};
			final var rootInfo = map.values().iterator().next();
			extraColumns = List.of(
					rootInfo.transactionIdMapping().getSelectionExpression(),
					rootInfo.modificationTypeMapping().getSelectionExpression()
			);
		}
		else {
			tableNameResolver = null;
			extraColumns = null;
		}

		// Subclass table entries (JOINED / TABLE_PER_CLASS)
		for ( var subclass : rootClass.getSubclasses() ) {
			if ( subclass.getAuxiliaryTable() != null ) {
				addTableAuditInfo( map, subclass.getTable(), subclass.getAuxiliaryTable(),
						rootClass, persister, txIdJdbcMapping, modTypeJdbcMapping, creationProcess );
			}
			// For TABLE_PER_CLASS intermediate classes, build audit subquery inline
			// (getSubclasses() is depth-first, so subtypes' entries are already in the map)
			if ( tableNameResolver != null && subclass.hasSubclasses() ) {
				addAuditSubquery( map, subclass, tableNameResolver, extraColumns, creationProcess );
			}
		}

		// Root's audit subquery (needs all subclass entries, so must come last)
		if ( tableNameResolver != null && rootClass.hasSubclasses() ) {
			addAuditSubquery( map, rootClass, tableNameResolver, extraColumns, creationProcess );
		}

		return map;
	}

	private static void addAuditSubquery(
			Map<String, AuditMappingImpl.TableAuditInfo> map,
			PersistentClass bootClass,
			Function<String, String> tableNameResolver,
			List<String> extraColumns,
			MappingModelCreationProcess creationProcess) {
		assert !bootClass.getSubclasses().isEmpty();
		final var unionPersister = (UnionSubclassEntityPersister)
				creationProcess.getEntityPersister( bootClass.getEntityName() );
		final var rootInfo = map.values().iterator().next();
		final String originalSubquery = unionPersister.getTableName();
		final String auditSubquery = unionPersister.generateSubquery(
				bootClass,
				tableNameResolver,
				extraColumns
		);
		map.put( originalSubquery, new AuditMappingImpl.TableAuditInfo(
				auditSubquery,
				rootInfo.transactionIdMapping(),
				rootInfo.modificationTypeMapping()
		) );
	}

	private static void addTableAuditInfo(
			Map<String, AuditMappingImpl.TableAuditInfo> map,
			Table originalTable,
			Table auditTable,
			Stateful auditable,
			AbstractEntityPersister persister,
			JdbcMapping txIdJdbcMapping,
			JdbcMapping modTypeJdbcMapping,
			MappingModelCreationProcess creationProcess) {
		final String originalTableName = persister.determineTableName( originalTable );
		final String auditTableName = persister.determineTableName( auditTable );
		map.put( originalTableName, createTableAuditInfo(
				auditTableName, auditable, txIdJdbcMapping, modTypeJdbcMapping, creationProcess ) );
	}

	private static AuditMappingImpl.TableAuditInfo createTableAuditInfo(
			String auditTableName,
			Stateful auditable,
			JdbcMapping txIdJdbcMapping,
			JdbcMapping modTypeJdbcMapping,
			MappingModelCreationProcess creationProcess) {
		final var creationContext = creationProcess.getCreationContext();
		final var typeConfiguration = creationContext.getTypeConfiguration();
		final var dialect = creationContext.getDialect();
		final var sqmFunctionRegistry =
				creationContext.getSessionFactory().getQueryEngine().getSqmFunctionRegistry();
		return new AuditMappingImpl.TableAuditInfo(
				auditTableName,
				SelectableMappingImpl.from(
						auditTableName, auditable.getAuxiliaryColumn( TRANSACTION_ID ),
						txIdJdbcMapping, typeConfiguration, true, false, false,
						dialect, sqmFunctionRegistry, creationContext
				),
				SelectableMappingImpl.from(
						auditTableName, auditable.getAuxiliaryColumn( MODIFICATION_TYPE ),
						modTypeJdbcMapping, typeConfiguration, true, false, false,
						dialect, sqmFunctionRegistry, creationContext
				)
		);
	}

	private static JdbcMapping resolveJdbcMapping(TypeConfiguration typeConfiguration, Class<?> javaType) {
		final var basicType = typeConfiguration.getBasicTypeForJavaType( javaType );
		return basicType != null ? basicType : typeConfiguration.standardBasicTypeForJavaType( javaType );
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
		final String originalTableName = getTableIdentifierExpression(
				bootDescriptor.getCollectionTable(), creationProcess );
		final String auditTableName = getTableIdentifierExpression( auditTable, creationProcess );
		final var typeConfiguration = creationProcess.getCreationContext().getTypeConfiguration();
		final var sessionFactory = creationProcess.getCreationContext().getSessionFactory();
		final var txIdJdbcMapping = resolveJdbcMapping( typeConfiguration,
				sessionFactory.getTransactionIdentifierService().getIdentifierType() );
		final var modTypeJdbcMapping = resolveJdbcMapping( typeConfiguration, ModificationType.class );
		return new AuditMappingImpl(
				Map.of( originalTableName, createTableAuditInfo(
						auditTableName, bootDescriptor,
						txIdJdbcMapping, modTypeJdbcMapping, creationProcess ) ),
				creationProcess );
	}

}
