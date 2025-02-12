/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.query.sqm.tree.predicate;

import org.hibernate.metamodel.model.domain.SimpleDomainType;
import org.hibernate.query.SemanticException;
import org.hibernate.query.sqm.NodeBuilder;
import org.hibernate.query.sqm.SemanticQueryWalker;
import org.hibernate.query.sqm.tree.SqmCopyContext;
import org.hibernate.query.sqm.tree.domain.SqmPluralElementValuedSimplePath;
import org.hibernate.query.sqm.tree.expression.SqmExpression;

import static org.hibernate.query.sqm.internal.TypecheckUtil.areTypesComparable;

/**
 * @author Steve Ebersole
 */
public class SqmMemberOfPredicate extends AbstractNegatableSqmPredicate {
	private final SqmExpression<?> leftHandExpression;
	private final SqmPluralElementValuedSimplePath<?> pluralPath;

	public SqmMemberOfPredicate(SqmExpression<?> leftHandExpression, SqmPluralElementValuedSimplePath<?> pluralPath, NodeBuilder nodeBuilder) {
		this( leftHandExpression, pluralPath, false, nodeBuilder );
	}

	public SqmMemberOfPredicate(
			SqmExpression<?> leftHandExpression,
			SqmPluralElementValuedSimplePath<?> pluralPath,
			boolean negated,
			NodeBuilder nodeBuilder) {
		super( negated, nodeBuilder );

		this.pluralPath = pluralPath;
		this.leftHandExpression = leftHandExpression;

		final SimpleDomainType<?> simpleDomainType = pluralPath.getReferencedPathSource().getElementType();

		if ( !areTypesComparable( leftHandExpression.getNodeType(), simpleDomainType, nodeBuilder ) ) {
			throw new SemanticException(
					String.format(
							"Cannot compare left expression of type '%s' with right expression of type '%s'",
							leftHandExpression.getNodeType().getTypeName(),
							pluralPath.getNodeType().getTypeName()
					)
			);
		}

		leftHandExpression.applyInferableType( simpleDomainType );
	}

	@Override
	public SqmMemberOfPredicate copy(SqmCopyContext context) {
		final SqmMemberOfPredicate existing = context.getCopy( this );
		if ( existing != null ) {
			return existing;
		}
		final SqmMemberOfPredicate predicate = context.registerCopy(
				this,
				new SqmMemberOfPredicate(
						leftHandExpression.copy( context ),
						pluralPath.copy( context ),
						isNegated(),
						nodeBuilder()
				)
		);
		copyTo( predicate, context );
		return predicate;
	}

	public SqmExpression<?> getLeftHandExpression() {
		return leftHandExpression;
	}

	public SqmPluralElementValuedSimplePath<?> getPluralPath() {
		return pluralPath;
	}

	@Override
	public <T> T accept(SemanticQueryWalker<T> walker) {
		return walker.visitMemberOfPredicate( this );
	}

	@Override
	public void appendHqlString(StringBuilder hql) {
		leftHandExpression.appendHqlString( hql );
		if ( isNegated() ) {
			hql.append( " not" );
		}
		hql.append( " member of " );
		pluralPath.appendHqlString( hql );
	}

	@Override
	protected SqmNegatablePredicate createNegatedNode() {
		return new SqmMemberOfPredicate( leftHandExpression, pluralPath, !isNegated(), nodeBuilder() );
	}
}
