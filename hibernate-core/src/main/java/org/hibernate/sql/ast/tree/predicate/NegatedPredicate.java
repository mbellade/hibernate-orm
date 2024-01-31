/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.ast.tree.predicate;

import org.hibernate.metamodel.mapping.JdbcMappingContainer;
import org.hibernate.sql.ast.SqlAstWalker;

/**
 * @author Steve Ebersole
 */
public class NegatedPredicate extends AbstractPredicate {
	private final Predicate predicate;

	public NegatedPredicate(Predicate predicate) {
		this( predicate, false );
	}

	public NegatedPredicate(Predicate predicate, boolean negated) {
		super (predicate.getExpressionType(), negated );
		this.predicate = predicate;
	}

	public Predicate getPredicate() {
		return predicate;
	}

	@Override
	public boolean isEmpty() {
		return predicate.isEmpty();
	}

	@Override
	public void accept(SqlAstWalker sqlTreeWalker) {
		sqlTreeWalker.visitNegatedPredicate( this );
	}
}
