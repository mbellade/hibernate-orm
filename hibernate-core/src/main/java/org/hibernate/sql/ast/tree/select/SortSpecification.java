/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.ast.tree.select;

import org.hibernate.query.NullPrecedence;
import org.hibernate.query.SortDirection;
import org.hibernate.sql.ast.SqlAstWalker;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.expression.Expression;

import jakarta.persistence.criteria.Nulls;

/**
 * @author Steve Ebersole
 */
public class SortSpecification implements SqlAstNode {
	private final Expression sortExpression;
	private final SortDirection sortOrder;
	private final NullPrecedence nullPrecedence;

	public SortSpecification(Expression sortExpression, SortDirection sortOrder) {
		this( sortExpression, sortOrder, NullPrecedence.NONE );
	}

	public SortSpecification(Expression sortExpression, SortDirection sortOrder, NullPrecedence nullPrecedence) {
		assert sortExpression != null;
		assert sortOrder != null;
		assert nullPrecedence != null;
		this.sortExpression = sortExpression;
		this.sortOrder = sortOrder;
		this.nullPrecedence = nullPrecedence;
	}

	public SortSpecification(Expression sortExpression, SortDirection sortOrder, Nulls nullPrecedence) {
		this( sortExpression,sortOrder, NullPrecedence.fromJpaValue( nullPrecedence ) );
	}

	public Expression getSortExpression() {
		return sortExpression;
	}

	public SortDirection getSortOrder() {
		return sortOrder;
	}

	public NullPrecedence getHibernateNullPrecedence() {
		return nullPrecedence;
	}

	public Nulls getNullPrecedence() {
		return nullPrecedence.getJpaValue();
	}

	@Override
	public void accept(SqlAstWalker sqlTreeWalker) {
		sqlTreeWalker.visitSortSpecification( this );
	}
}
