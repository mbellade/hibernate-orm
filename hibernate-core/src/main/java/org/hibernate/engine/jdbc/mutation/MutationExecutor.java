/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.engine.jdbc.mutation;

import org.hibernate.Incubating;
import org.hibernate.engine.jdbc.batch.spi.BatchKey;
import org.hibernate.engine.jdbc.mutation.group.PreparedStatementDetails;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.sql.model.ValuesAnalysis;

/**
 * Main contract for performing the mutation.  Accounts for various
 * moving parts such as:<ul>
 *     <li>Should the statements be batched or not?</li>
 *     <li>Should we "logically" group logging of the parameter bindings?</li>
 *     <li>...</li>
 * </ul>
 *
 * @author Steve Ebersole
 */
@Incubating
public interface MutationExecutor {
	/**
	 * Get the delegate to be used to coordinate JDBC parameter binding.
	 */
	JdbcValueBindings getJdbcValueBindings();

	/**
	 * Details about the {@link java.sql.PreparedStatement} for mutating
	 * the given table.
	 */
	PreparedStatementDetails getPreparedStatementDetails(String tableName);

	/**
	 * Perform the execution, returning any generated value.
	 *
	 * @param inclusionChecker The ability to skip the execution for a
	 * 		specific table; passing {@code null} indicates no filtering
	 * @param resultChecker Custom result checking; pass {@code null} to perform
	 * 		the standard check using the statement's {@linkplain org.hibernate.jdbc.Expectation expectation}
	 */
	Object execute(
			Object modelReference,
			ValuesAnalysis valuesAnalysis,
			TableInclusionChecker inclusionChecker,
			OperationResultChecker resultChecker,
			SharedSessionContractImplementor session);

	void release();

	default void prepareForNonBatchedWork(BatchKey batchKey, SharedSessionContractImplementor session) {
		// if there is a current batch, make sure to execute it first
		session.getJdbcCoordinator().conditionallyExecuteBatch( batchKey );
	}
}
