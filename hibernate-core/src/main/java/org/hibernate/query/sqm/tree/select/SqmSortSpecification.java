/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree.select;

import java.util.Objects;

import org.hibernate.query.NullPrecedence;
import org.hibernate.query.SortDirection;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaOrder;
import org.hibernate.query.sqm.tree.SqmCopyContext;
import org.hibernate.query.sqm.tree.expression.SqmExpression;

import jakarta.persistence.criteria.Nulls;

/**
 * @author Steve Ebersole
 */
public class SqmSortSpecification implements JpaOrder {
	@SuppressWarnings("rawtypes")
	private final SqmExpression sortExpression;
	private final SortDirection sortOrder;

	private NullPrecedence nullPrecedence;

	public SqmSortSpecification(
			@SuppressWarnings("rawtypes") SqmExpression sortExpression,
			SortDirection sortOrder,
			NullPrecedence nullPrecedence) {
		assert sortExpression != null;
		assert sortOrder != null;
		assert nullPrecedence != null;
		this.sortExpression = sortExpression;
		this.sortOrder = sortOrder;
		this.nullPrecedence = nullPrecedence;
	}

	@SuppressWarnings("rawtypes")
	public SqmSortSpecification(SqmExpression sortExpression) {
		this( sortExpression, SortDirection.ASCENDING, NullPrecedence.NONE );
	}

	@SuppressWarnings("rawtypes")
	public SqmSortSpecification(SqmExpression sortExpression, SortDirection sortOrder) {
		this( sortExpression, sortOrder, NullPrecedence.NONE );
	}

	public SqmSortSpecification copy(SqmCopyContext context) {
		return new SqmSortSpecification( sortExpression.copy( context ), sortOrder, nullPrecedence );
	}

	public SqmExpression<?> getSortExpression() {
		return sortExpression;
	}

	@Override
	public SortDirection getSortDirection() {
		return sortOrder;
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// JPA

	@Override
	public JpaOrder nullPrecedence(Nulls nullPrecedence) {
		this.nullPrecedence = NullPrecedence.fromJpaValue( nullPrecedence );
		return this;
	}

	@Override
	public Nulls getNullPrecedence() {
		return nullPrecedence.getJpaValue();
	}

	@Override
	public JpaOrder reverse() {
		SortDirection newSortOrder = this.sortOrder == null ? SortDirection.DESCENDING : sortOrder.reverse();
		return new SqmSortSpecification( sortExpression, newSortOrder, nullPrecedence );
	}

	@Override
	public JpaExpression<?> getExpression() {
		return getSortExpression();
	}

	@Override
	public boolean isAscending() {
		return sortOrder == SortDirection.ASCENDING;
	}

	public void appendHqlString(StringBuilder sb) {
		sortExpression.appendHqlString( sb );
		if ( sortOrder == SortDirection.DESCENDING ) {
			sb.append( " desc" );
			if ( nullPrecedence != null ) {
				if ( nullPrecedence == NullPrecedence.FIRST ) {
					sb.append( " nulls first" );
				}
				else {
					sb.append( " nulls last" );
				}
			}
		}
		else if ( nullPrecedence != null ) {
			sb.append( " asc" );
			if ( nullPrecedence == NullPrecedence.FIRST ) {
				sb.append( " nulls first" );
			}
			else {
				sb.append( " nulls last" );
			}
		}
	}

	@Override
	public boolean equals(Object o) {
		if ( this == o ) {
			return true;
		}
		else if ( !(o instanceof SqmSortSpecification) ) {
			return false;
		}
		else {
			// used in SqmInterpretationsKey.equals()
			SqmSortSpecification that = (SqmSortSpecification) o;
			return Objects.equals( sortExpression, that.sortExpression )
				&& sortOrder == that.sortOrder
				&& nullPrecedence == that.nullPrecedence;
		}
	}

	@Override
	public int hashCode() {
		return Objects.hash( sortExpression, sortOrder, nullPrecedence );
	}
}
