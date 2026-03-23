/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.audit.internal;

import org.hibernate.audit.AuditLog;
import org.hibernate.audit.ModificationType;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.mapping.AuditMapping;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.SortDirection;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.StandardTableGroup;
import org.hibernate.sql.ast.tree.from.TableReference;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.ast.tree.select.SortSpecification;
import org.hibernate.sql.exec.internal.BaseExecutionContext;
import org.hibernate.sql.exec.internal.JdbcParameterBindingImpl;
import org.hibernate.sql.exec.internal.JdbcParameterBindingsImpl;
import org.hibernate.sql.exec.internal.SqlTypedMappingJdbcParameter;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.exec.spi.JdbcParametersList;
import org.hibernate.sql.results.graph.DomainResult;
import org.hibernate.sql.results.graph.basic.BasicResult;
import org.hibernate.sql.results.internal.RowTransformerSingularReturnImpl;
import org.hibernate.sql.results.internal.SqlSelectionImpl;
import org.hibernate.sql.results.spi.ListResultsConsumer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Default implementation of {@link AuditLog} that queries
 * audit tables using SQL AST.
 *
 * @author Marco Belladelli
 * @since envers-rewrite
 */
public class AuditLogImpl implements AuditLog {
	private final SessionFactoryImplementor sessionFactory;

	public AuditLogImpl(SessionFactoryImplementor sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	@Override
	public List<Object> getRevisions(Class<?> entityClass, Object id) {
		final var persister = getEntityPersister( entityClass );
		final var auditMapping = requireAuditMapping( persister );

		// Build: SELECT REV FROM entity_aud WHERE id = ? ORDER BY REV
		final var querySpec = new QuerySpec( false );
		final var tableReference = createAuditTableReference( auditMapping );
		addAuditTableGroup( querySpec, tableReference );

		// SELECT REV
		final var revMapping = auditMapping.getTransactionIdMapping();
		final var revColumnRef = new ColumnReference( tableReference, revMapping );
		querySpec.getSelectClause().addSqlSelection( new SqlSelectionImpl( 0, revColumnRef ) );

		// WHERE id = ?
		final var jdbcParametersBuilder = JdbcParametersList.newBuilder();
		applyIdPredicate( querySpec, tableReference, persister, jdbcParametersBuilder );
		final var jdbcParameters = jdbcParametersBuilder.build();

		// ORDER BY REV
		querySpec.addSortSpecification( new SortSpecification( revColumnRef, SortDirection.ASCENDING ) );

		// Domain result
		final List<DomainResult<?>> domainResults = List.of(
				new BasicResult<>( 0, "rev", revMapping.getJdbcMapping() )
		);

		return executeQuery(
				querySpec,
				domainResults,
				(bindings, session) -> bindings.registerParametersForEachJdbcValue(
						id,
						persister.getIdentifierMapping(),
						jdbcParameters,
						session
				)
		);
	}

	@Override
	public ModificationType getModificationType(Class<?> entityClass, Object id, Object transactionId) {
		final var persister = getEntityPersister( entityClass );
		final var auditMapping = requireAuditMapping( persister );

		// Build: SELECT REVTYPE FROM entity_aud WHERE id = ? AND REV = ?
		final var querySpec = new QuerySpec( false );
		final var tableReference = createAuditTableReference( auditMapping );
		addAuditTableGroup( querySpec, tableReference );

		// SELECT REVTYPE
		final var revTypeMapping = auditMapping.getModificationTypeMapping();
		final var revTypeColumnRef = new ColumnReference( tableReference, revTypeMapping );
		querySpec.getSelectClause().addSqlSelection( new SqlSelectionImpl( 0, revTypeColumnRef ) );

		// WHERE id = ?
		final var jdbcParametersBuilder = JdbcParametersList.newBuilder();
		applyIdPredicate( querySpec, tableReference, persister, jdbcParametersBuilder );

		// AND REV = ?
		final var revMapping = auditMapping.getTransactionIdMapping();
		final var revParam = new SqlTypedMappingJdbcParameter( revMapping );
		jdbcParametersBuilder.add( revParam );
		querySpec.applyPredicate( new ComparisonPredicate(
				new ColumnReference( tableReference, revMapping ),
				ComparisonOperator.EQUAL,
				revParam
		) );
		final var jdbcParameters = jdbcParametersBuilder.build();

		// Domain result
		final List<DomainResult<?>> domainResults = List.of(
				new BasicResult<>( 0, "revtype", revTypeMapping.getJdbcMapping() )
		);

		final List<Object> results = executeQuery(
				querySpec, domainResults,
				(bindings, session) -> {
					bindings.registerParametersForEachJdbcValue(
							id,
							persister.getIdentifierMapping(),
							jdbcParameters,
							session
					);
					bindings.addBinding(
							revParam,
							new JdbcParameterBindingImpl( revMapping.getJdbcMapping(), transactionId )
					);
				}
		);

		if ( results.isEmpty() ) {
			return null;
		}
		final int ordinal = ((Number) results.get( 0 )).intValue();
		return ModificationType.values()[ordinal];
	}

	@Override
	public List<Object> getEntitiesModifiedAt(Class<?> entityClass, Object transactionId) {
		final var persister = getEntityPersister( entityClass );
		final var auditMapping = requireAuditMapping( persister );

		// Build: SELECT id FROM entity_aud WHERE REV = ?
		final var querySpec = new QuerySpec( false );
		final var tableReference = createAuditTableReference( auditMapping );
		addAuditTableGroup( querySpec, tableReference );

		// SELECT id column(s)
		final List<DomainResult<?>> domainResults = new ArrayList<>();
		persister.getIdentifierMapping().forEachSelectable( (index, selectable) -> {
			final var columnRef = new ColumnReference( tableReference, selectable );
			querySpec.getSelectClause().addSqlSelection( new SqlSelectionImpl( index, columnRef ) );
			domainResults.add(
					new BasicResult<>( index, selectable.getSelectableName(), selectable.getJdbcMapping() ) );
		} );

		// WHERE REV = ?
		final var revMapping = auditMapping.getTransactionIdMapping();
		final var revParam = new SqlTypedMappingJdbcParameter( revMapping );
		querySpec.applyPredicate( new ComparisonPredicate(
				new ColumnReference( tableReference, revMapping ),
				ComparisonOperator.EQUAL,
				revParam
		) );

		return executeQuery(
				querySpec, domainResults,
				(bindings, session) -> bindings.addBinding(
						revParam,
						new JdbcParameterBindingImpl( revMapping.getJdbcMapping(), transactionId )
				)
		);
	}

	@Override
	public boolean isAudited(Class<?> entityClass) {
		return getEntityPersister( entityClass ).getAuditMapping() != null;
	}

	@Override
	public <T> T find(Class<T> entityClass, Object id, Object transactionId) {
		try ( var session = sessionFactory.withStatelessOptions()
				.atTransaction( transactionId ).open() ) {
			return session.get( entityClass, id );
		}
	}

	@Override
	public <T> List<T> findEntitiesModifiedAt(Class<T> entityClass, Object transactionId) {
		final var ids = getEntitiesModifiedAt( entityClass, transactionId );
		if ( ids.isEmpty() ) {
			return List.of();
		}
		try ( var session = sessionFactory.withStatelessOptions()
				.atTransaction( transactionId ).open() ) {
			// getMultiple returns null for deleted entities — filter them out
			return session.getMultiple( entityClass, ids ).stream()
					.filter( java.util.Objects::nonNull )
					.toList();
		}
	}

	// --- helpers ---

	private EntityPersister getEntityPersister(Class<?> entityClass) {
		return sessionFactory.getMappingMetamodel().getEntityDescriptor( entityClass );
	}

	private static AuditMapping requireAuditMapping(EntityPersister persister) {
		final var auditMapping = persister.getAuditMapping();
		if ( auditMapping == null ) {
			throw new IllegalArgumentException(
					"Entity '" + persister.getEntityName() + "' is not audited"
			);
		}
		return auditMapping;
	}

	private static NamedTableReference createAuditTableReference(AuditMapping auditMapping) {
		return new NamedTableReference( auditMapping.getTableName(), "aud0_" );
	}

	private static void addAuditTableGroup(QuerySpec querySpec, NamedTableReference tableReference) {
		final var tableGroup = new StandardTableGroup(
				true,
				new NavigablePath( "audit-query" ),
				null,
				tableReference.getIdentificationVariable(),
				tableReference,
				null,
				null
		);
		querySpec.getFromClause().addRoot( tableGroup );
	}

	private static void applyIdPredicate(
			QuerySpec querySpec,
			TableReference tableReference,
			EntityPersister persister,
			JdbcParametersList.Builder jdbcParametersBuilder) {
		persister.getIdentifierMapping().forEachSelectable( (index, selectable) -> {
			final var param = new SqlTypedMappingJdbcParameter( selectable );
			jdbcParametersBuilder.add( param );
			querySpec.applyPredicate( new ComparisonPredicate(
					new ColumnReference( tableReference, selectable ),
					ComparisonOperator.EQUAL,
					param
			) );
		} );
	}

	private List<Object> executeQuery(
			QuerySpec querySpec,
			List<DomainResult<?>> domainResults,
			BiConsumer<JdbcParameterBindings, SharedSessionContractImplementor> parameterBinder) {
		final var selectStatement = new SelectStatement( querySpec, domainResults );

		final var jdbcSelect = sessionFactory.getJdbcServices()
				.getJdbcEnvironment()
				.getSqlAstTranslatorFactory()
				.buildSelectTranslator( sessionFactory, selectStatement )
				.translate( null, QueryOptions.NONE );

		try (var statelessSession = sessionFactory.openStatelessSession()) {
			final var session = (SharedSessionContractImplementor) statelessSession;
			final var bindings = new JdbcParameterBindingsImpl(
					jdbcSelect.getParameterBinders().size()
			);
			parameterBinder.accept( bindings, session );

			return session.getJdbcServices().getJdbcSelectExecutor().list(
					jdbcSelect,
					bindings,
					new BaseExecutionContext( session ),
					RowTransformerSingularReturnImpl.instance(),
					null,
					ListResultsConsumer.UniqueSemantic.NONE,
					1
			);
		}
	}
}
