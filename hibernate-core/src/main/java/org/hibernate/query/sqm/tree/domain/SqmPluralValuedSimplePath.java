/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree.domain;

import org.hibernate.metamodel.mapping.CollectionPart;
import org.hibernate.metamodel.model.domain.EntityDomainType;
import org.hibernate.metamodel.model.domain.ListPersistentAttribute;
import org.hibernate.metamodel.model.domain.MapPersistentAttribute;
import org.hibernate.metamodel.model.domain.PluralPersistentAttribute;
import org.hibernate.spi.NavigablePath;
import org.hibernate.query.PathException;
import org.hibernate.query.SemanticException;
import org.hibernate.query.hql.spi.SqmCreationState;
import org.hibernate.query.hql.spi.SqmPathRegistry;
import org.hibernate.query.sqm.NodeBuilder;
import org.hibernate.query.sqm.SemanticQueryWalker;
import org.hibernate.query.sqm.tree.SqmCopyContext;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmQualifiedJoin;

/**
 * An SqmPath for plural attribute paths
 *
 * @param <E> The collection element type, which is the "bindable" type in the SQM tree
 *
 * @author Steve Ebersole
 */
public class SqmPluralValuedSimplePath<E> extends AbstractSqmSimplePath<E> {
	public SqmPluralValuedSimplePath(
			NavigablePath navigablePath,
			PluralPersistentAttribute<?, ?, E> referencedNavigable,
			SqmPath<?> lhs,
			NodeBuilder nodeBuilder) {
		this( navigablePath, referencedNavigable, lhs, null, nodeBuilder );
	}

	public SqmPluralValuedSimplePath(
			NavigablePath navigablePath,
			PluralPersistentAttribute<?, ?, E> referencedNavigable,
			SqmPath<?> lhs,
			String explicitAlias,
			NodeBuilder nodeBuilder) {
		super( navigablePath, referencedNavigable, lhs, explicitAlias, nodeBuilder );
	}

	@Override
	public SqmPluralValuedSimplePath<E> copy(SqmCopyContext context) {
		final SqmPluralValuedSimplePath<E> existing = context.getCopy( this );
		if ( existing != null ) {
			return existing;
		}

		final SqmPluralValuedSimplePath<E> path = context.registerCopy(
				this,
				new SqmPluralValuedSimplePath<>(
						getNavigablePath(),
						getReferencedPathSource(),
						getLhs().copy( context ),
						getExplicitAlias(),
						nodeBuilder()
				)
		);
		copyTo( path, context );
		return path;
	}

	@Override
	public PluralPersistentAttribute<?, ?, E> getReferencedPathSource() {
		return (PluralPersistentAttribute<?, ?, E>) super.getReferencedPathSource();
	}

	@Override
	public PluralPersistentAttribute<?,?,E> getNodeType() {
		return getReferencedPathSource();
	}

	@Override
	public <T> T accept(SemanticQueryWalker<T> walker) {
		return walker.visitPluralValuedPath( this );
	}

	@Override
	public SqmPath<?> resolvePathPart(
			String name,
			boolean isTerminal,
			SqmCreationState creationState) {
		// this is a reference to a collection outside of the from-clause...
		final CollectionPart.Nature nature = CollectionPart.Nature.fromNameExact( name );
		if ( nature == null ) {
			throw new SemanticException( "illegal attempt to dereference collection [" + getNavigablePath() + "] with element property reference [" + name + "]" );
		}
		final SqmPath<?> sqmPath = get( name );
		creationState.getProcessingStateStack().getCurrent().getPathRegistry().register( sqmPath );
		return sqmPath;
	}

	@Override
	public SqmPath<?> resolveIndexedAccess(
			SqmExpression<?> selector,
			boolean isTerminal,
			SqmCreationState creationState) {
		final SqmPathRegistry pathRegistry = creationState.getCurrentProcessingState().getPathRegistry();
		final String alias = selector.toHqlString();
		final NavigablePath navigablePath = getNavigablePath().getParent().append(
				getNavigablePath().getLocalName(),
				alias
		).append( CollectionPart.Nature.ELEMENT.getName() );
		final SqmFrom<?, ?> indexedPath = pathRegistry.findFromByPath( navigablePath );
		if ( indexedPath != null ) {
			return indexedPath;
		}
		SqmFrom<?, ?> path = pathRegistry.findFromByPath( navigablePath.getParent() );
		if ( path == null ) {
			final PluralPersistentAttribute<?, ?, E> referencedPathSource = getReferencedPathSource();
			final SqmFrom<?, Object> parent = pathRegistry.resolveFrom( getLhs() );
			final SqmQualifiedJoin<Object, ?> join;
			final SqmExpression<?> index;
			if ( referencedPathSource instanceof ListPersistentAttribute<?, ?> ) {
				//noinspection unchecked
				join = new SqmListJoin<>(
						parent,
						(ListPersistentAttribute<Object, ?>) referencedPathSource,
						alias,
						SqmJoinType.INNER,
						false,
						parent.nodeBuilder()
				);
				index = ( (SqmListJoin<?, ?>) join ).index();
			}
			else if ( referencedPathSource instanceof MapPersistentAttribute<?, ?, ?> ) {
				//noinspection unchecked
				join = new SqmMapJoin<>(
						parent,
						(MapPersistentAttribute<Object, ?, ?>) referencedPathSource,
						alias,
						SqmJoinType.INNER,
						false,
						parent.nodeBuilder()
				);
				index = ( (SqmMapJoin<?, ?, ?>) join ).key();
			}
			else {
				throw new SemanticException( "Index access is only supported on list or map attributes: " + getNavigablePath() );
			}
			join.setJoinPredicate( creationState.getCreationContext().getNodeBuilder().equal( index, selector ) );
			parent.addSqmJoin( join );
			pathRegistry.register( path = join );
		}
		final SqmIndexedCollectionAccessPath<Object> result = new SqmIndexedCollectionAccessPath<>(
				navigablePath,
				path,
				selector
		);
		pathRegistry.register( result );
		return result;
	}

	@Override
	public <S extends E> SqmTreatedSimplePath<E,S> treatAs(Class<S> treatJavaType) throws PathException {
		return (SqmTreatedSimplePath<E, S>) treatAs( nodeBuilder().getDomainModel().entity( treatJavaType ) );
	}

	@Override
	public <S extends E> SqmTreatedPath<E, S> treatAs(EntityDomainType<S> treatTarget) throws PathException {
		return getTreatedPath( treatTarget );
	}

//	@Override
//	public DomainResult createDomainResult(
//			String resultVariable,
//			DomainResultCreationState creationState,
//			DomainResultCreationContext creationContext) {
//		return new CollectionResultImpl(
//				getReferencedNavigable().getPluralAttribute().getDescribedAttribute(),
//				getNavigablePath(),
//				resultVariable,
//				LockMode.NONE,
//				getReferencedNavigable().getPluralAttribute().getCollectionKeyDescriptor().createDomainResult(
//						getNavigablePath().append( "{id}" ),
//						null,
//						creationState,
//						creationContext
//				),
//				initializerProducerCreator.createProducer( resultVariable, creationState, creationContext )
//		);
//	}

}
