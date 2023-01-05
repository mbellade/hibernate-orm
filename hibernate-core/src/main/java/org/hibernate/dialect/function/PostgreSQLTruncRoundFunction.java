/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.dialect.function;

import java.util.List;

import org.hibernate.query.sqm.function.AbstractSqmSelfRenderingFunctionDescriptor;
import org.hibernate.query.sqm.produce.function.ArgumentTypesValidator;
import org.hibernate.query.sqm.produce.function.StandardArgumentsValidators;
import org.hibernate.query.sqm.produce.function.StandardFunctionArgumentTypeResolvers;
import org.hibernate.query.sqm.produce.function.StandardFunctionReturnTypeResolvers;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Literal;
import org.hibernate.type.descriptor.jdbc.JdbcType;

import static org.hibernate.query.sqm.produce.function.FunctionParameterType.INTEGER;
import static org.hibernate.query.sqm.produce.function.FunctionParameterType.NUMERIC;

/**
 * PostgreSQL only supports the two-argument {@code trunc} and {@code round} functions
 * with the following signatures:
 * <ul>
 *     <li>{@code trunc(numeric, integer)}</li>
 *     <li>{@code round(numeric, integer)}</li>
 * </ul>
 * <p>
 * This custom function falls back to using {@code floor} as a workaround only when necessary,
 * e.g. when:
 * <ul>
 *     <li>There are 2 arguments to the function</li>
 *     <li>The first argument is not of type {@code numeric}</li>
 *     <li>The dialect doesn't support the two-argument {@code trunc} function</li>
 * </ul>
 *
 * @author Marco Belladelli
 * @see <a href="https://www.postgresql.org/docs/current/functions-math.html">PostgreSQL documentation</a>
 */
public class PostgreSQLTruncRoundFunction extends AbstractSqmSelfRenderingFunctionDescriptor {
	private final boolean supportsTwoArguments;

	public PostgreSQLTruncRoundFunction(String name, boolean supportsTwoArguments) {
		super(
				name,
				new ArgumentTypesValidator( StandardArgumentsValidators.between( 1, 2 ), NUMERIC, INTEGER ),
				StandardFunctionReturnTypeResolvers.useArgType( 1 ),
				StandardFunctionArgumentTypeResolvers.invariant( NUMERIC, INTEGER )
		);
		assert name.equals( "trunc" ) || name.equals( "round" ); // todo marco : remove ?
		this.supportsTwoArguments = supportsTwoArguments;
	}

	@Override
	public void render(
			SqlAppender sqlAppender,
			List<? extends SqlAstNode> arguments,
			SqlAstTranslator<?> walker) {
		final int numberOfArguments = arguments.size();
		final Expression firstArg = (Expression) arguments.get( 0 );
		if ( numberOfArguments == 1 || supportsTwoArguments && (firstArg instanceof Literal || // todo marco : tenere per literal ?
				firstArg.getExpressionType().getJdbcMappings().get( 0 ).getJdbcType().isDecimal()) ) {
			// use native two-argument function
			sqlAppender.appendSql( getName() );
			sqlAppender.appendSql( "(" );
			firstArg.accept( walker );
			if ( numberOfArguments > 1 ) {
				sqlAppender.appendSql( ", " );
				arguments.get( 1 ).accept( walker );
			}
			sqlAppender.appendSql( ")" );
		}
		else {
			// workaround using floor
			if ( getName().equals( "trunc" ) ) {
				sqlAppender.appendSql( "sign(" );
				firstArg.accept( walker );
				sqlAppender.appendSql( ")*floor(abs(" );
				firstArg.accept( walker );
				sqlAppender.appendSql( ")*1e" );
				arguments.get( 1 ).accept( walker );
			}
			else {
				sqlAppender.appendSql( "floor(" );
				firstArg.accept( walker );
				sqlAppender.appendSql( "*1e" );
				arguments.get( 1 ).accept( walker );
				sqlAppender.appendSql( "+0.5" );
			}
			sqlAppender.appendSql( ")/1e" );
			arguments.get( 1 ).accept( walker );
		}
	}

	@Override
	public String getArgumentListSignature() {
		return "(NUMERIC number[, INTEGER places])";
	}
}
