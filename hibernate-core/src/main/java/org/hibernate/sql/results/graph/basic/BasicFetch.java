/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.results.graph.basic;

import org.hibernate.engine.FetchTiming;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.type.descriptor.converter.spi.BasicValueConverter;
import org.hibernate.spi.NavigablePath;
import org.hibernate.sql.results.graph.AssemblerCreationState;
import org.hibernate.sql.results.graph.UnfetchedBasicPartResultAssembler;
import org.hibernate.sql.results.graph.UnfetchedResultAssembler;
import org.hibernate.sql.results.graph.DomainResultAssembler;
import org.hibernate.sql.results.graph.DomainResultCreationState;
import org.hibernate.sql.results.graph.Fetch;
import org.hibernate.sql.results.graph.FetchParent;
import org.hibernate.sql.results.graph.FetchParentAccess;
import org.hibernate.sql.results.graph.Fetchable;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * Fetch for a basic-value
 *
 * @author Steve Ebersole
 */
public class BasicFetch<T> implements Fetch, BasicResultGraphNode<T> {
	private final NavigablePath navigablePath;
	private final FetchParent fetchParent;
	private final BasicValuedModelPart valuedMapping;

	private final DomainResultAssembler<T> assembler;

	private final FetchTiming fetchTiming;

	public BasicFetch(
			int valuesArrayPosition,
			FetchParent fetchParent,
			NavigablePath fetchablePath,
			BasicValuedModelPart valuedMapping,
			FetchTiming fetchTiming,
			DomainResultCreationState creationState) {
		//noinspection unchecked
		this(
				valuesArrayPosition,
				fetchParent,
				fetchablePath,
				valuedMapping,
				(BasicValueConverter<T, ?>) valuedMapping.getJdbcMapping().getValueConverter(),
				fetchTiming,
				true,
				creationState
		);
	}

	public BasicFetch(
			int valuesArrayPosition,
			FetchParent fetchParent,
			NavigablePath fetchablePath,
			BasicValuedModelPart valuedMapping,
			FetchTiming fetchTiming,
			DomainResultCreationState creationState,
			boolean coerceResultType) {
		//noinspection unchecked
		this(
				valuesArrayPosition,
				fetchParent,
				fetchablePath,
				valuedMapping,
				(BasicValueConverter<T, ?>) valuedMapping.getJdbcMapping().getValueConverter(),
				fetchTiming,
				true,
				creationState,
				coerceResultType
		);
	}

	public BasicFetch(
			int valuesArrayPosition,
			FetchParent fetchParent,
			NavigablePath fetchablePath,
			BasicValuedModelPart valuedMapping,
			BasicValueConverter<T, ?> valueConverter,
			FetchTiming fetchTiming,
			DomainResultCreationState creationState) {
		this(
				valuesArrayPosition,
				fetchParent,
				fetchablePath,
				valuedMapping,
				valueConverter,
				fetchTiming,
				true,
				creationState
		);
	}

	public BasicFetch(
			int valuesArrayPosition,
			FetchParent fetchParent,
			NavigablePath fetchablePath,
			BasicValuedModelPart valuedMapping,
			BasicValueConverter<T, ?> valueConverter,
			FetchTiming fetchTiming,
			boolean canBasicPartFetchBeDelayed,
			DomainResultCreationState creationState) {
		this(
				valuesArrayPosition,
				fetchParent,
				fetchablePath,
				valuedMapping,
				valueConverter,
				fetchTiming,
				canBasicPartFetchBeDelayed,
				creationState,
				false
		);
	}

	public BasicFetch(
			int valuesArrayPosition,
			FetchParent fetchParent,
			NavigablePath fetchablePath,
			BasicValuedModelPart valuedMapping,
			BasicValueConverter<T, ?> valueConverter,
			FetchTiming fetchTiming,
			boolean canBasicPartFetchBeDelayed,
			DomainResultCreationState creationState,
			boolean coerceResultType) {
		this.navigablePath = fetchablePath;

		this.fetchParent = fetchParent;
		this.valuedMapping = valuedMapping;
		this.fetchTiming = fetchTiming;
		@SuppressWarnings("unchecked") final JavaType<T> javaType = (JavaType<T>) valuedMapping.getJavaType();
		// lazy basic attribute
		if ( fetchTiming == FetchTiming.DELAYED && valuesArrayPosition == -1 ) {
			if ( canBasicPartFetchBeDelayed ) {
				this.assembler = new UnfetchedResultAssembler<>( javaType );
			}
			else {
				this.assembler = new UnfetchedBasicPartResultAssembler( javaType );
			}
		}
		else {
			if (coerceResultType) {
				this.assembler = new CoercingResultAssembler<>( valuesArrayPosition, javaType, valueConverter );
			}
			else {
				this.assembler = new BasicResultAssembler<>( valuesArrayPosition, javaType, valueConverter );
			}
		}
	}

	@Override
	public FetchTiming getTiming() {
		return fetchTiming;
	}

	@Override
	public boolean hasTableGroup() {
		return fetchTiming == FetchTiming.IMMEDIATE;
	}

	@Override
	public FetchParent getFetchParent() {
		return fetchParent;
	}

	@Override
	public Fetchable getFetchedMapping() {
		return valuedMapping;
	}

	@Override
	public JavaType<?> getResultJavaType() {
		return null;
	}

	@Override
	public NavigablePath getNavigablePath() {
		return navigablePath;
	}

	@Override
	public DomainResultAssembler createAssembler(
			FetchParentAccess parentAccess,
			AssemblerCreationState creationState) {
		return assembler;
	}

	@Override
	public DomainResultAssembler<T> createResultAssembler(
			FetchParentAccess parentAccess,
			AssemblerCreationState creationState) {
		return assembler;
	}

	@Override
	public String getResultVariable() {
		// a basic value used as a fetch will never have a result variable in the domain result
		return null;
	}
}
