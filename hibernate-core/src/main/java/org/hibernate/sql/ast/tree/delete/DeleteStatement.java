/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.ast.tree.delete;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.sql.ast.SqlAstWalker;
import org.hibernate.sql.ast.spi.SqlAstHelper;
import org.hibernate.sql.ast.tree.AbstractMutationStatement;
import org.hibernate.sql.ast.tree.cte.CteContainer;
import org.hibernate.sql.ast.tree.cte.CteStatement;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.Predicate;

/**
 * @author Steve Ebersole
 */
public class DeleteStatement extends AbstractMutationStatement {

	public static final String DEFAULT_ALIAS = "to_delete_";
	private final Predicate restriction;
	private final Predicate subqueryRestriction;

	public DeleteStatement(NamedTableReference targetTable, Predicate restriction) {
		super( targetTable );
		this.restriction = restriction;
		this.subqueryRestriction = null;
	}

	public DeleteStatement(
			NamedTableReference targetTable,
			Predicate restriction,
			List<ColumnReference> returningColumns) {
		super( new LinkedHashMap<>(), targetTable, returningColumns );
		this.restriction = restriction;
		this.subqueryRestriction = null;
	}

	public DeleteStatement(
			CteContainer cteContainer,
			NamedTableReference targetTable,
			Predicate restriction,
			Predicate subqueryRestriction,
			List<ColumnReference> returningColumns) {
		this(
				cteContainer.getCteStatements(),
				targetTable,
				restriction,
				subqueryRestriction,
				returningColumns
		);
	}

	public DeleteStatement(
			Map<String, CteStatement> cteStatements,
			NamedTableReference targetTable,
			Predicate restriction,
			Predicate subqueryRestriction,
			List<ColumnReference> returningColumns) {
		super( cteStatements, targetTable, returningColumns );
		this.restriction = restriction;
		this.subqueryRestriction = subqueryRestriction;
	}

	public Predicate getRestriction() {
		return restriction;
	}

	public Predicate getSubqueryRestriction() {
		return subqueryRestriction;
	}

	public static class DeleteStatementBuilder {
		private final NamedTableReference targetTable;
		private Predicate restriction;

		public DeleteStatementBuilder(NamedTableReference targetTable) {
			this.targetTable = targetTable;
		}

		public DeleteStatementBuilder addRestriction(Predicate restriction) {
			this.restriction = SqlAstHelper.combinePredicates( this.restriction, restriction );
			return this;
		}

		public DeleteStatementBuilder setRestriction(Predicate restriction) {
			this.restriction = restriction;
			return this;
		}

		public DeleteStatement createDeleteStatement() {
			return new DeleteStatement(
					targetTable,
					restriction != null ? restriction : new Junction( Junction.Nature.CONJUNCTION )
			);
		}
	}

	@Override
	public void accept(SqlAstWalker walker) {
		walker.visitDeleteStatement( this );
	}
}
