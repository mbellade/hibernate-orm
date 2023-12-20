/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.id.insert;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

import org.hibernate.MappingException;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.mutation.JdbcValueBindings;
import org.hibernate.engine.jdbc.mutation.group.PreparedStatementDetails;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.jdbc.spi.MutationStatementPreparer;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.EventType;
import org.hibernate.generator.OnExecutionGenerator;
import org.hibernate.generator.values.GeneratedValues;
import org.hibernate.id.PostInsertIdentityPersister;
import org.hibernate.jdbc.Expectation;
import org.hibernate.metamodel.mapping.BasicEntityIdentifierMapping;
import org.hibernate.sql.model.ast.builder.TableInsertBuilder;
import org.hibernate.sql.model.ast.builder.TableInsertBuilderStandard;
import org.hibernate.sql.model.ast.builder.TableMutationBuilder;

import static java.sql.Statement.RETURN_GENERATED_KEYS;
import static org.hibernate.generator.values.GeneratedValuesHelper.getGeneratedColumnNames;
import static org.hibernate.generator.values.GeneratedValuesHelper.getGeneratedValues;

/**
 * Delegate for dealing with {@code IDENTITY} columns using the JDBC3 method
 * {@link PreparedStatement#getGeneratedKeys()}.
 *
 * @author Andrea Boriero
 */
public class GetGeneratedKeysDelegate extends AbstractReturningDelegate {
	private final PostInsertIdentityPersister persister;
	private final Dialect dialect;
	private final boolean inferredKeys;
	private final String[] columnNames;

	public GetGeneratedKeysDelegate(
			PostInsertIdentityPersister persister,
			Dialect dialect,
			boolean inferredKeys,
			EventType timing) {
		super( persister, timing );
		this.persister = persister;
		this.dialect = dialect;
		this.inferredKeys = inferredKeys;

		if ( inferredKeys ) {
			columnNames = null;
		}
		else {
			columnNames = getGeneratedColumnNames( persister, dialect, EventType.INSERT ).toArray( new String[0] );
		}
	}

	@Override @Deprecated
	public IdentifierGeneratingInsert prepareIdentifierGeneratingInsert(SqlStringGenerationContext context) {
		IdentifierGeneratingInsert insert = new IdentifierGeneratingInsert( persister.getFactory() );
		insert.addGeneratedColumns( persister.getRootTableKeyColumnNames(), (OnExecutionGenerator) persister.getGenerator() );
		return insert;
	}

	@Override
	public TableMutationBuilder<?> createTableMutationBuilder(
			Expectation expectation,
			SessionFactoryImplementor factory) {
		final TableInsertBuilder builder =
				new TableInsertBuilderStandard( persister, persister.getIdentifierTableMapping(), factory );

		if ( persister.isIdentifierAssignedByInsert() ) {
			final OnExecutionGenerator generator = (OnExecutionGenerator) persister.getGenerator();
			if ( generator.referenceColumnsInSql( dialect ) ) {
				final BasicEntityIdentifierMapping identifierMapping = (BasicEntityIdentifierMapping) persister.getIdentifierMapping();
				final String[] columnNames = persister.getRootTableKeyColumnNames();
				final String[] columnValues = generator.getReferencedColumnValues( dialect );
				if ( columnValues.length != columnNames.length ) {
					throw new MappingException( "wrong number of generated columns" );
				}
				for ( int i = 0; i < columnValues.length; i++ ) {
					builder.addKeyColumn( columnNames[i], columnValues[i], identifierMapping.getJdbcMapping() );
				}
			}
		}

		return builder;
	}

	@Override
	public PreparedStatement prepareStatement(String insertSql, SharedSessionContractImplementor session) {
		MutationStatementPreparer preparer = session.getJdbcCoordinator().getMutationStatementPreparer();
		return inferredKeys
				? preparer.prepareStatement( insertSql, RETURN_GENERATED_KEYS )
				: preparer.prepareStatement( insertSql, columnNames );
	}

	@Override
	public boolean supportsRetrievingGeneratedValues() {
		return true;
	}

	@Override
	public GeneratedValues performMutation(
			PreparedStatementDetails statementDetails,
			JdbcValueBindings jdbcValueBindings,
			Object entity,
			SharedSessionContractImplementor session) {
		final JdbcServices jdbcServices = session.getJdbcServices();
		final JdbcCoordinator jdbcCoordinator = session.getJdbcCoordinator();

		final String insertSql = statementDetails.getSqlString();

		jdbcServices.getSqlStatementLogger().logStatement( insertSql );

		final PreparedStatement insertStatement = statementDetails.resolveStatement();
		jdbcValueBindings.beforeStatement( statementDetails );

		try {
			jdbcCoordinator.getResultSetReturn().executeUpdate( insertStatement, insertSql );

			try {
				final ResultSet resultSet = insertStatement.getGeneratedKeys();
				try {
					return getGeneratedValues( resultSet, persister, getTiming(), session );
				}
				catch (SQLException e) {
					throw jdbcServices.getSqlExceptionHelper().convert(
							e,
							() -> String.format(
									Locale.ROOT,
									"Unable to extract generated key from generated-key for `%s`",
									persister.getNavigableRole().getFullPath()
							),
							insertSql
					);
				}
				finally {
					if ( resultSet != null ) {
						jdbcCoordinator
								.getLogicalConnection()
								.getResourceRegistry()
								.release( resultSet, insertStatement );
					}
				}
			}
			finally {
				jdbcCoordinator.getLogicalConnection().getResourceRegistry().release( insertStatement );
			}
		}
		catch (SQLException e) {
			throw jdbcServices.getSqlExceptionHelper().convert(
					e,
					"Unable to extract generated-keys ResultSet",
					insertSql
			);
		}
	}

	@Override
	public GeneratedValues executeAndExtract(
			String sql,
			PreparedStatement insertStatement,
			SharedSessionContractImplementor session) {
		final JdbcCoordinator jdbcCoordinator = session.getJdbcCoordinator();
		final JdbcServices jdbcServices = session.getJdbcServices();

		jdbcCoordinator.getResultSetReturn().executeUpdate( insertStatement, sql );

		try {
			final ResultSet resultSet = insertStatement.getGeneratedKeys();
			try {
				return getGeneratedValues( resultSet, persister, getTiming(), session );
			}
			catch (SQLException e) {
				throw jdbcServices.getSqlExceptionHelper().convert(
						e,
						"Unable to extract generated key(s) from generated-keys ResultSet",
						sql
				);
			}
			finally {
				if ( resultSet != null ) {
					jdbcCoordinator.getLogicalConnection().getResourceRegistry().release( resultSet, insertStatement );
				}
			}
		}
		catch (SQLException e) {
			throw jdbcServices.getSqlExceptionHelper().convert(
					e,
					"Unable to extract generated-keys ResultSet",
					sql
			);
		}
	}
}
