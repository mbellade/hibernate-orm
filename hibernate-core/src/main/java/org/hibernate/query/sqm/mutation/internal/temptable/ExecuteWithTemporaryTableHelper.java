/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.query.sqm.mutation.internal.temptable;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.temptable.TemporaryTable;
import org.hibernate.dialect.temptable.TemporaryTableColumn;
import org.hibernate.dialect.temptable.TemporaryTableHelper;
import org.hibernate.dialect.temptable.TemporaryTableHelper.TemporaryTableCreationWork;
import org.hibernate.dialect.temptable.TemporaryTableHelper.TemporaryTableDropWork;
import org.hibernate.dialect.temptable.TemporaryTableSessionUidColumn;
import org.hibernate.dialect.temptable.TemporaryTableStrategy;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.mapping.BasicValuedMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.ModelPart;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.query.sqm.mutation.internal.MultiTableSqmMutationConverter;
import org.hibernate.query.sqm.mutation.spi.AfterUseAction;
import org.hibernate.query.sqm.mutation.spi.BeforeUseAction;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.SimpleSelect;
import org.hibernate.sql.ast.SqlAstJoinType;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.StandardTableGroup;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableReference;
import org.hibernate.sql.ast.tree.insert.InsertSelectStatement;
import org.hibernate.sql.ast.tree.predicate.ComparisonPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.exec.spi.ExecutionContext;
import org.hibernate.sql.exec.spi.JdbcParameterBindings;
import org.hibernate.sql.results.internal.SqlSelectionImpl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Function;

import static org.hibernate.boot.TempTableDdlTransactionHandling.ISOLATE_AND_TRANSACT;
import static org.hibernate.boot.TempTableDdlTransactionHandling.NONE;

/**
 * @author Steve Ebersole
 */
public final class ExecuteWithTemporaryTableHelper {
	private ExecuteWithTemporaryTableHelper() {
	}

	public static int saveMatchingIdsIntoIdTable(
			MultiTableSqmMutationConverter sqmConverter,
			Predicate suppliedPredicate,
			TemporaryTable idTable,
			Function<SharedSessionContractImplementor, String> sessionUidAccess,
			JdbcParameterBindings jdbcParameterBindings,
			ExecutionContext executionContext) {
		final TableGroup mutatingTableGroup = sqmConverter.getMutatingTableGroup();
		final var mutatingEntityDescriptor = (EntityMappingType) mutatingTableGroup.getModelPart();

		final NamedTableReference idTableReference = new NamedTableReference(
				idTable.getTableExpression(),
				InsertSelectStatement.DEFAULT_ALIAS
		);
		final InsertSelectStatement idTableInsert = new InsertSelectStatement( idTableReference );

		for ( int i = 0; i < idTable.getColumns().size(); i++ ) {
			final TemporaryTableColumn column = idTable.getColumns().get( i );
			idTableInsert.addTargetColumnReferences(
					new ColumnReference(
							idTableReference,
							column.getColumnName(),
							// id columns cannot be formulas and cannot have custom read and write expressions
							false,
							null,
							column.getJdbcMapping()
					)
			);
		}

		final QuerySpec matchingIdSelection = new QuerySpec( true, 1 );
		idTableInsert.setSourceSelectStatement( matchingIdSelection );

		matchingIdSelection.getFromClause().addRoot( mutatingTableGroup );

		mutatingEntityDescriptor.getIdentifierMapping().forEachSelectable(
				(selectionIndex, selection) -> {
					matchingIdSelection.getSelectClause().addSqlSelection(
							new SqlSelectionImpl(
									selectionIndex,
									sqmConverter.getSqlExpressionResolver().resolveSqlExpression(
											mutatingTableGroup.resolveTableReference(
													mutatingTableGroup.getNavigablePath(),
													selection.getContainingTableExpression()
											),
											selection
									)
							)
					);
				}
		);

		if ( idTable.getSessionUidColumn() != null ) {
			final int jdbcPosition = matchingIdSelection.getSelectClause().getSqlSelections().size();
			matchingIdSelection.getSelectClause().addSqlSelection(
					new SqlSelectionImpl(
							jdbcPosition,
							new QueryLiteral<>(
									UUID.fromString( sessionUidAccess.apply( executionContext.getSession() ) ),
									(BasicValuedMapping) idTable.getSessionUidColumn().getJdbcMapping()
							)
					)
			);
		}

		matchingIdSelection.applyPredicate( suppliedPredicate );
		return saveIntoTemporaryTable( idTableInsert, jdbcParameterBindings, executionContext );
	}

	public static int saveIntoTemporaryTable(
			InsertSelectStatement temporaryTableInsert,
			JdbcParameterBindings jdbcParameterBindings,
			ExecutionContext executionContext) {
		final var factory = executionContext.getSession().getFactory();
		final JdbcServices jdbcServices = factory.getJdbcServices();
		final JdbcEnvironment jdbcEnvironment = jdbcServices.getJdbcEnvironment();
		final LockOptions lockOptions = executionContext.getQueryOptions().getLockOptions();
		final LockMode lockMode = lockOptions.getLockMode();
		// Acquire a WRITE lock for the rows that are about to be modified
		lockOptions.setLockMode( LockMode.WRITE );
		// Visit the table joins and reset the lock mode if we encounter OUTER joins that are not supported
		final QueryPart sourceSelectStatement = temporaryTableInsert.getSourceSelectStatement();
		if ( sourceSelectStatement != null
				&& !jdbcEnvironment.getDialect().supportsOuterJoinForUpdate() ) {
			sourceSelectStatement.visitQuerySpecs(
					querySpec -> {
						querySpec.getFromClause().visitTableJoins(
								tableJoin -> {
									if ( tableJoin.isInitialized()
											&& tableJoin.getJoinType() != SqlAstJoinType.INNER ) {
										lockOptions.setLockMode( lockMode );
									}
								}
						);
					}
			);
		}
		final var jdbcInsert =
				jdbcEnvironment.getSqlAstTranslatorFactory()
						.buildMutationTranslator( factory, temporaryTableInsert )
						.translate( jdbcParameterBindings, executionContext.getQueryOptions() );
		lockOptions.setLockMode( lockMode );

		return jdbcServices.getJdbcMutationExecutor().execute(
				jdbcInsert,
				jdbcParameterBindings,
				sql -> executionContext.getSession().getJdbcCoordinator()
						.getStatementPreparer().prepareStatement( sql ),
				(integer, preparedStatement) -> {},
				executionContext
		);
	}

	public static QuerySpec createIdTableSelectQuerySpec(
			TemporaryTable idTable,
			Function<SharedSessionContractImplementor, String> sessionUidAccess,
			EntityMappingType entityDescriptor,
			ExecutionContext executionContext) {
		return createIdTableSelectQuerySpec( idTable, null, sessionUidAccess, entityDescriptor, executionContext );
	}

	public static QuerySpec createIdTableSelectQuerySpec(
			TemporaryTable idTable,
			ModelPart fkModelPart,
			Function<SharedSessionContractImplementor, String> sessionUidAccess,
			EntityMappingType entityDescriptor,
			ExecutionContext executionContext) {
		final QuerySpec querySpec = new QuerySpec( false );

		final NamedTableReference idTableReference = new NamedTableReference(
				idTable.getTableExpression(),
				TemporaryTable.DEFAULT_ALIAS,
				true
		);
		final TableGroup idTableGroup = new StandardTableGroup(
				true,
				new NavigablePath( idTableReference.getTableExpression() ),
				entityDescriptor,
				null,
				idTableReference,
				null,
				executionContext.getSession().getFactory()
		);

		querySpec.getFromClause().addRoot( idTableGroup );

		applyIdTableSelections( querySpec, idTableReference, idTable, fkModelPart, entityDescriptor );
		applyIdTableRestrictions( querySpec, idTableReference, idTable, sessionUidAccess, executionContext );

		return querySpec;
	}

	private static void applyIdTableSelections(
			QuerySpec querySpec,
			TableReference tableReference,
			TemporaryTable idTable,
			ModelPart fkModelPart,
			EntityMappingType entityDescriptor) {
		if ( fkModelPart == null ) {
			final int size = entityDescriptor.getIdentifierMapping().getJdbcTypeCount();
			for ( int i = 0; i < size; i++ ) {
				final var temporaryTableColumn = idTable.getColumns().get( i );
				if ( temporaryTableColumn != idTable.getSessionUidColumn() ) {
					querySpec.getSelectClause().addSqlSelection(
							new SqlSelectionImpl(
									i,
									new ColumnReference(
											tableReference,
											temporaryTableColumn.getColumnName(),
											false,
											null,
											temporaryTableColumn.getJdbcMapping()
									)
							)
					);
				}
			}
		}
		else {
			fkModelPart.forEachSelectable(
					(i, selectableMapping) -> {
						querySpec.getSelectClause().addSqlSelection(
								new SqlSelectionImpl(
										i,
										new ColumnReference(
												tableReference,
												selectableMapping.getSelectionExpression(),
												false,
												null,
												selectableMapping.getJdbcMapping()
										)
								)
						);
					}
			);
		}
	}

	private static void applyIdTableRestrictions(
			QuerySpec querySpec,
			TableReference idTableReference,
			TemporaryTable idTable,
			Function<SharedSessionContractImplementor, String> sessionUidAccess,
			ExecutionContext executionContext) {
		if ( idTable.getSessionUidColumn() != null ) {
			querySpec.applyPredicate(
					new ComparisonPredicate(
							new ColumnReference(
									idTableReference,
									idTable.getSessionUidColumn().getColumnName(),
									false,
									null,
									idTable.getSessionUidColumn().getJdbcMapping()
							),
							ComparisonOperator.EQUAL,
							new QueryLiteral<>(
									UUID.fromString( sessionUidAccess.apply( executionContext.getSession() ) ),
									(BasicValuedMapping) idTable.getSessionUidColumn().getJdbcMapping()
							)
					)
			);
		}
	}

	@Deprecated(forRemoval = true, since = "7.1")
	public static void performBeforeTemporaryTableUseActions(
			TemporaryTable temporaryTable,
			ExecutionContext executionContext) {
		performBeforeTemporaryTableUseActions(
				temporaryTable,
				executionContext.getSession().getDialect().getTemporaryTableBeforeUseAction(),
				executionContext
		);
	}

	public static boolean performBeforeTemporaryTableUseActions(
			TemporaryTable temporaryTable,
			TemporaryTableStrategy temporaryTableStrategy,
			ExecutionContext executionContext) {
		return performBeforeTemporaryTableUseActions(
				temporaryTable,
				temporaryTableStrategy.getTemporaryTableBeforeUseAction(),
				executionContext
		);
	}

	private static boolean performBeforeTemporaryTableUseActions(
			TemporaryTable temporaryTable,
			BeforeUseAction beforeUseAction,
			ExecutionContext executionContext) {
		final var factory = executionContext.getSession().getFactory();
		final Dialect dialect = factory.getJdbcServices().getDialect();
		if ( beforeUseAction == BeforeUseAction.CREATE ) {
			final var temporaryTableCreationWork =
					new TemporaryTableCreationWork( temporaryTable, factory );
			final var ddlTransactionHandling = dialect.getTemporaryTableDdlTransactionHandling();
			if ( ddlTransactionHandling == NONE ) {
				return executionContext.getSession().doReturningWork( temporaryTableCreationWork );
			}
			else {
				final var isolationDelegate =
						executionContext.getSession().getJdbcCoordinator().getJdbcSessionOwner()
								.getTransactionCoordinator().createIsolationDelegate();
				return isolationDelegate.delegateWork( temporaryTableCreationWork,
						ddlTransactionHandling == ISOLATE_AND_TRANSACT );
			}
		}
		else {
			return false;
		}
	}

	public static int[] loadInsertedRowNumbers(
			TemporaryTable temporaryTable,
			Function<SharedSessionContractImplementor, String> sessionUidAccess,
			int rows,
			ExecutionContext executionContext) {
		final TemporaryTableSessionUidColumn sessionUidColumn = temporaryTable.getSessionUidColumn();

		final TemporaryTableColumn rowNumberColumn = temporaryTable.getColumns()
				.get( temporaryTable.getColumns().size() - (sessionUidColumn == null ? 1 : 2 ) );
		assert rowNumberColumn != null;

		final SharedSessionContractImplementor session = executionContext.getSession();
		final SimpleSelect simpleSelect = new SimpleSelect( session.getFactory() )
				.setTableName( temporaryTable.getQualifiedTableName() )
				.addColumn( rowNumberColumn.getColumnName() );
		if ( sessionUidColumn != null ) {
			simpleSelect.addRestriction( sessionUidColumn.getColumnName() );
		}
		final String sqlSelect = simpleSelect.toStatementString();

		final JdbcCoordinator jdbcCoordinator = session.getJdbcCoordinator();
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = jdbcCoordinator.getStatementPreparer().prepareStatement( sqlSelect );
			if ( sessionUidColumn != null ) {
				//noinspection unchecked
				sessionUidColumn.getJdbcMapping().getJdbcValueBinder().bind(
						preparedStatement,
						UUID.fromString( sessionUidAccess.apply( session ) ),
						1,
						session
				);
			}
			final ResultSet resultSet = jdbcCoordinator.getResultSetReturn().execute( preparedStatement, sqlSelect );
			final int[] rowNumbers = new int[rows];
			try {
				int rowIndex = 0;
				while (resultSet.next()) {
					rowNumbers[rowIndex++] = resultSet.getInt( 1 );
				}
				return rowNumbers;
			}
			catch ( IndexOutOfBoundsException e ) {
				throw new IllegalArgumentException( "Expected " + rows + " to be inserted but found more", e );
			}
		}
		catch( SQLException ex ) {
			throw new IllegalStateException( ex );
		}
		finally {
			if ( preparedStatement != null ) {
				try {
					jdbcCoordinator.getLogicalConnection().getResourceRegistry().release( preparedStatement );
				}
				catch( Throwable ignore ) {
					// ignore
				}
				jdbcCoordinator.afterStatementExecution();
			}
		}
	}

	public static void performAfterTemporaryTableUseActions(
			TemporaryTable temporaryTable,
			Function<SharedSessionContractImplementor, String> sessionUidAccess,
			AfterUseAction afterUseAction,
			ExecutionContext executionContext) {
		final var factory = executionContext.getSession().getFactory();
		final Dialect dialect = factory.getJdbcServices().getDialect();
		switch ( afterUseAction ) {
			case CLEAN:
				TemporaryTableHelper.cleanTemporaryTableRows(
						temporaryTable,
						dialect.getTemporaryTableExporter(),
						sessionUidAccess,
						executionContext.getSession()
				);
				break;
			case DROP:
				final var temporaryTableDropWork = new TemporaryTableDropWork( temporaryTable, factory );
				final var ddlTransactionHandling = dialect.getTemporaryTableDdlTransactionHandling();
				if ( ddlTransactionHandling == NONE ) {
					executionContext.getSession().doWork( temporaryTableDropWork );
				}
				else {
					final var isolationDelegate =
							executionContext.getSession().getJdbcCoordinator().getJdbcSessionOwner()
									.getTransactionCoordinator().createIsolationDelegate();
					isolationDelegate.delegateWork( temporaryTableDropWork,
							ddlTransactionHandling == ISOLATE_AND_TRANSACT );
				}
		}
	}
}
