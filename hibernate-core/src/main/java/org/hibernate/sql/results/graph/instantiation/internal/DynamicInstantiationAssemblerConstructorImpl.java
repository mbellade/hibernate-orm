/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.results.graph.instantiation.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.hibernate.query.sqm.sql.internal.InstantiationException;
import org.hibernate.sql.results.graph.DomainResultAssembler;
import org.hibernate.sql.results.jdbc.spi.JdbcValuesSourceProcessingOptions;
import org.hibernate.sql.results.jdbc.spi.RowProcessingState;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * @author Steve Ebersole
 */
public class DynamicInstantiationAssemblerConstructorImpl<R> implements DomainResultAssembler<R> {
	private final Constructor<R> targetConstructor;
	private final JavaType<R> resultType;
	private final List<ArgumentReader<?>> argumentReaders;

	public DynamicInstantiationAssemblerConstructorImpl(
			Constructor<R> targetConstructor,
			JavaType<R> resultType,
			List<ArgumentReader<?>> argumentReaders) {
		this.targetConstructor = targetConstructor;
		this.resultType = resultType;
		this.argumentReaders = argumentReaders;
	}

	@Override
	public JavaType<R> getAssembledJavaType() {
		return resultType;
	}

	@Override
	public R assemble(
			RowProcessingState rowProcessingState,
			JdbcValuesSourceProcessingOptions options) {
		final int numberOfArgs = argumentReaders.size();
		Object[] args = new Object[ numberOfArgs ];
		for ( int i = 0; i < numberOfArgs; i++ ) {
			args[i] = argumentReaders.get( i ).assemble( rowProcessingState, options );
		}

		//noinspection unchecked
		return (R) new DynamicInstantiationDelayedResult<>( () -> {
			try {
				return targetConstructor.newInstance( args );
			}
			catch (InvocationTargetException e) {
				throw new InstantiationException( "Error performing dynamic instantiation : " + targetConstructor.getDeclaringClass()
						.getName(), e.getCause() );
			}
			catch (Exception e) {
				throw new InstantiationException( "Error performing dynamic instantiation : " + targetConstructor.getDeclaringClass()
						.getName(), e );
			}
		} );
	}
}
