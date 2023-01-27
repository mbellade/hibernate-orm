/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.results.graph.basic;

import java.util.Locale;

import org.hibernate.HibernateException;
import org.hibernate.Internal;
import org.hibernate.type.descriptor.converter.spi.BasicValueConverter;
import org.hibernate.sql.ast.spi.SqlSelection;
import org.hibernate.sql.results.ResultsLogger;
import org.hibernate.sql.results.graph.DomainResultAssembler;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesSourceProcessingOptions;
import org.hibernate.sql.results.jdbc.spi.RowProcessingState;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * @author Steve Ebersole
 */
public class BasicResultAssembler<J> implements DomainResultAssembler<J> {
	public static <X> BasicResultAssembler<X> from(SqlSelection selection, JavaType<X> javaType) {
		return new BasicResultAssembler<>( selection.getValuesArrayPosition(), javaType );
	}

	protected final int valuesArrayPosition;
	protected final JavaType<J> assembledJavaType;
	private final BasicValueConverter<J,?> valueConverter;

	public BasicResultAssembler(
			int valuesArrayPosition,
			JavaType<J> assembledJavaType) {
		this( valuesArrayPosition, assembledJavaType, null );
	}

	public BasicResultAssembler(
			int valuesArrayPosition,
			JavaType<J> assembledJavaType,
			BasicValueConverter<J, ?> valueConverter) {
		this.valuesArrayPosition = valuesArrayPosition;
		this.assembledJavaType = assembledJavaType;
		this.valueConverter = valueConverter;
	}

	/**
	 * Access to the raw value (unconverted, if a converter applied)
	 */
	public Object extractRawValue(RowProcessingState rowProcessingState) {
		return rowProcessingState.getJdbcValue( valuesArrayPosition );
	}

	@Override
	public J assemble(
			RowProcessingState rowProcessingState,
			JdbcValuesSourceProcessingOptions options) {
		final Object jdbcValue = extractRawValue( rowProcessingState );

		if ( ResultsLogger.DEBUG_ENABLED ) {
			ResultsLogger.RESULTS_LOGGER.debugf( "Extracted JDBC value [%d] - [%s]", valuesArrayPosition, jdbcValue );
		}

		if ( valueConverter != null ) {
			if ( jdbcValue != null ) {
				// the raw value type should be the converter's relational-JTD
				if ( ! valueConverter.getRelationalJavaType().getJavaTypeClass().isInstance( jdbcValue ) ) {
					throw new HibernateException(
							String.format(
									Locale.ROOT,
									"Expecting raw JDBC value of type `%s`, but found `%s` : [%s]",
									valueConverter.getRelationalJavaType().getJavaType().getTypeName(),
									jdbcValue.getClass().getName(),
									jdbcValue
							)
					);
				}
			}

			//noinspection unchecked,rawtypes
			return (J) ( (BasicValueConverter) valueConverter ).toDomainValue( jdbcValue );
		}

		//noinspection unchecked
		return (J) jdbcValue;
	}

	@Override
	public JavaType<J> getAssembledJavaType() {
		if ( valueConverter != null ) {
			return valueConverter.getDomainJavaType();
		}
		return assembledJavaType;
	}

	/**
	 * Exposed for testing purposes
	 */
	@Internal
	public BasicValueConverter<J, ?> getValueConverter() {
		return valueConverter;
	}
}
