/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import org.hibernate.engine.jdbc.batch.internal.BasicBatchKey;
import org.hibernate.engine.jdbc.mutation.JdbcValueBindings;
import org.hibernate.engine.jdbc.mutation.ParameterUsage;
import org.hibernate.engine.jdbc.mutation.spi.MutationExecutorService;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.mapping.AuditMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.persister.entity.mutation.AbstractMutationCoordinator;
import org.hibernate.audit.ModificationType;
import org.hibernate.sql.model.MutationOperationGroup;
import org.hibernate.sql.model.MutationType;
import org.hibernate.sql.model.ast.builder.TableInsertBuilderStandard;

import org.checkerframework.checker.nullness.qual.Nullable;

import static org.hibernate.sql.model.internal.MutationOperationGroupFactory.singleOperation;

/**
 * Base class for one-to-many audit coordinators (insert and delete rows).
 * <p>
 * Builds and caches an audit INSERT operation group targeting the child
 * entity's audit table, including entity attributes, the collection FK
 * column, and audit columns (REV/REVTYPE).
 */
abstract class AbstractOneToManyAuditCoordinator {
	private final CollectionMutationTarget mutationTarget;
	private final SessionFactoryImplementor sessionFactory;
	final BasicBatchKey auditBatchKey;
	final MutationExecutorService mutationExecutorService;

	private MutationOperationGroup operationGroup;

	AbstractOneToManyAuditCoordinator(
			CollectionMutationTarget mutationTarget,
			SessionFactoryImplementor sessionFactory,
			String batchKeySuffix) {
		this.mutationTarget = mutationTarget;
		this.sessionFactory = sessionFactory;
		this.auditBatchKey = new BasicBatchKey( mutationTarget.getRolePath() + batchKeySuffix );
		this.mutationExecutorService = sessionFactory.getServiceRegistry()
				.getService( MutationExecutorService.class );
	}

	public CollectionMutationTarget getMutationTarget() {
		return mutationTarget;
	}

	MutationOperationGroup getOrBuildOperationGroup(
			EntityPersister elementPersister,
			AuditMapping auditMapping) {
		if ( operationGroup == null ) {
			operationGroup = buildOperationGroup( elementPersister, auditMapping );
		}
		return operationGroup;
	}

	void bindAuditValues(
			Object childEntity,
			@Nullable Object collectionKeyValue,
			EntityPersister elementPersister,
			AuditMapping auditMapping,
			ModificationType modificationType,
			SharedSessionContractImplementor session,
			JdbcValueBindings jdbcValueBindings) {
		final String auditTableName = auditMapping.getTableName();
		final var sourceTableMapping = elementPersister.getTableMappings()[0];
		final Object childId = elementPersister.getIdentifier( childEntity, session );
		final Object[] values = elementPersister.getValues( childEntity );

		// Entity key
		sourceTableMapping.getKeyMapping().breakDownKeyJdbcValues(
				childId,
				(jdbcValue, columnMapping) -> jdbcValueBindings.bindValue(
						jdbcValue, auditTableName, columnMapping.getColumnName(), ParameterUsage.SET
				),
				session
		);

		// Entity attribute values
		for ( final int attributeIndex : sourceTableMapping.getAttributeIndexes() ) {
			final var attributeMapping = elementPersister.getAttributeMappings().get( attributeIndex );
			attributeMapping.decompose(
					values[attributeIndex], 0, jdbcValueBindings, null,
					(valueIndex, bindings, noop, jdbcValue, selectableMapping) -> {
						if ( selectableMapping.isInsertable() && !selectableMapping.isFormula() ) {
							bindings.bindValue( jdbcValue, auditTableName,
									selectableMapping.getSelectionExpression(), ParameterUsage.SET );
						}
					},
					session
			);
		}

		// Collection FK value (null when removing from collection)
		mutationTarget.getTargetPart().getKeyDescriptor().getKeyPart().decompose(
				collectionKeyValue, 0, jdbcValueBindings, null,
				(valueIndex, bindings, noop, jdbcValue, selectableMapping) -> {
					if ( !selectableMapping.isFormula() ) {
						bindings.bindValue( jdbcValue, auditTableName,
								selectableMapping.getSelectionExpression(), ParameterUsage.SET );
					}
				},
				session
		);

		// Audit columns
		if ( !sessionFactory.getTransactionIdentifierService().isDisabled() ) {
			jdbcValueBindings.bindValue(
					session.getCurrentTransactionIdentifier(), auditTableName,
					auditMapping.getTransactionIdMapping().getSelectionExpression(), ParameterUsage.SET
			);
		}
		jdbcValueBindings.bindValue(
				modificationType, auditTableName,
				auditMapping.getModificationTypeMapping().getSelectionExpression(), ParameterUsage.SET
		);
	}

	private MutationOperationGroup buildOperationGroup(
			EntityPersister elementPersister,
			AuditMapping auditMapping) {
		final String auditTableName = auditMapping.getTableName();
		final var sourceTableMapping = elementPersister.getTableMappings()[0];
		final var auditTableMapping = AbstractMutationCoordinator.createAuxiliaryTableMapping(
				sourceTableMapping, elementPersister, auditTableName );
		final var insertBuilder = new TableInsertBuilderStandard(
				elementPersister, auditTableMapping, sessionFactory );

		// Entity attribute columns
		for ( final int attributeIndex : sourceTableMapping.getAttributeIndexes() ) {
			elementPersister.getAttributeMappings().get( attributeIndex )
					.forEachInsertable( insertBuilder );
		}

		// Collection FK column
		mutationTarget.getTargetPart().getKeyDescriptor().getKeyPart()
				.forEachInsertable( insertBuilder );

		// Audit columns
		if ( sessionFactory.getTransactionIdentifierService().isDisabled() ) {
			insertBuilder.addValueColumn(
					sessionFactory.getJdbcServices().getDialect().currentTimestamp(),
					auditMapping.getTransactionIdMapping() );
		}
		else {
			insertBuilder.addValueColumn( "?", auditMapping.getTransactionIdMapping() );
		}
		insertBuilder.addValueColumn( "?", auditMapping.getModificationTypeMapping() );

		// Entity key columns
		sourceTableMapping.getKeyMapping().forEachKeyColumn( insertBuilder::addKeyColumn );

		final var mutation = insertBuilder.buildMutation();
		return singleOperation( MutationType.INSERT, elementPersister,
				mutation.createMutationOperation( null, sessionFactory ) );
	}
}
