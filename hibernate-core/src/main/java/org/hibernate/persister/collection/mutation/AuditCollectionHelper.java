/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.persister.collection.mutation;

import java.util.function.UnaryOperator;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.AuditMapping;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.metamodel.mapping.internal.OneToManyCollectionPart;
import org.hibernate.sql.model.MutationOperationGroup;
import org.hibernate.sql.model.MutationType;
import org.hibernate.sql.model.ast.builder.TableInsertBuilderStandard;

import static org.hibernate.sql.model.internal.MutationOperationGroupFactory.singleOperation;

/**
 * Support for building audit log mutations for collections.
 */
public final class AuditCollectionHelper {
	private final CollectionMutationTarget mutationTarget;
	private final SessionFactoryImplementor sessionFactory;
	private final CollectionTableMapping auditTableMapping;
	private final SelectableMapping transactionIdMapping;
	private final SelectableMapping modificationTypeMapping;
	private final boolean useServerTransactionTimestamps;
	private final String currentTimestampFunctionName;
	private final boolean[] indexColumnIsSettable;
	private final boolean[] elementColumnIsSettable;
	private final UnaryOperator<Object> indexIncrementer;

	private MutationOperationGroup auditInsertOperationGroup;
	private AuditCollectionRowMutationHelper rowMutationHelper;

	AuditCollectionHelper(
			CollectionMutationTarget mutationTarget,
			SessionFactoryImplementor sessionFactory,
			boolean[] indexColumnIsSettable,
			boolean[] elementColumnIsSettable,
			UnaryOperator<Object> indexIncrementer,
			AuditMapping auditMapping) {
		this.mutationTarget = mutationTarget;
		this.sessionFactory = sessionFactory;
		this.indexColumnIsSettable = indexColumnIsSettable;
		this.elementColumnIsSettable = elementColumnIsSettable;
		this.indexIncrementer = indexIncrementer;
		final String collectionTableName = mutationTarget.getCollectionTableMapping().getTableName();
		this.auditTableMapping = new CollectionTableMapping(
				mutationTarget.getCollectionTableMapping(),
				auditMapping.resolveTableName( collectionTableName )
		);
		this.transactionIdMapping = auditMapping.getTransactionIdMapping( collectionTableName );
		this.modificationTypeMapping = auditMapping.getModificationTypeMapping( collectionTableName );
		this.useServerTransactionTimestamps =
				sessionFactory.getTransactionIdentifierService().isDisabled();
		this.currentTimestampFunctionName = useServerTransactionTimestamps
				? sessionFactory.getJdbcServices().getDialect().currentTimestamp()
				: null;
	}

	CollectionTableMapping getAuditTableMapping() {
		return auditTableMapping;
	}

	MutationOperationGroup getAuditInsertOperationGroup() {
		if ( auditInsertOperationGroup == null ) {
			auditInsertOperationGroup = buildAuditInsertOperationGroup();
		}
		return auditInsertOperationGroup;
	}

	AuditCollectionRowMutationHelper getRowMutationHelper() {
		if ( rowMutationHelper == null ) {
			rowMutationHelper = new AuditCollectionRowMutationHelper(
					mutationTarget,
					auditTableMapping.getTableName(),
					transactionIdMapping,
					modificationTypeMapping,
					indexColumnIsSettable,
					elementColumnIsSettable,
					indexIncrementer,
					useServerTransactionTimestamps
			);
		}
		return rowMutationHelper;
	}

	private MutationOperationGroup buildAuditInsertOperationGroup() {
		final var insertBuilder =
				new TableInsertBuilderStandard( mutationTarget, auditTableMapping, sessionFactory );
		applyAuditInsertDetails( insertBuilder );
		final var tableInsert = insertBuilder.buildMutation();
		final var operation = tableInsert.createMutationOperation( null, sessionFactory );
		return operation == null ? null : singleOperation( MutationType.INSERT, mutationTarget, operation );
	}

	private void applyAuditInsertDetails(TableInsertBuilderStandard insertBuilder) {
		final var attributeMapping = mutationTarget.getTargetPart();
		attributeMapping.getKeyDescriptor().getKeyPart().forEachSelectable( insertBuilder );

		final var identifierDescriptor = attributeMapping.getIdentifierDescriptor();
		if ( identifierDescriptor != null ) {
			identifierDescriptor.forEachSelectable( insertBuilder );
		}
		else {
			final var indexDescriptor = attributeMapping.getIndexDescriptor();
			if ( indexDescriptor != null ) {
				indexDescriptor.forEachInsertable( insertBuilder );
			}
		}

		final var elementDescriptor = attributeMapping.getElementDescriptor();
		if ( elementDescriptor instanceof OneToManyCollectionPart oneToMany ) {
			// For @OneToMany @JoinColumn, the middle audit table stores the child entity's ID,
			// not the FK columns (which are the element's selectables for OneToManyCollectionPart)
			oneToMany.getAssociatedEntityMappingType().getIdentifierMapping().forEachInsertable( insertBuilder );
		}
		else {
			elementDescriptor.forEachInsertable( insertBuilder );
		}

		if ( useServerTransactionTimestamps ) {
			insertBuilder.addValueColumn( currentTimestampFunctionName, transactionIdMapping );
		}
		else {
			insertBuilder.addValueColumn( "?", transactionIdMapping );
		}
		insertBuilder.addValueColumn( "?", modificationTypeMapping );
	}
}
