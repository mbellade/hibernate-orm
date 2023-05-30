/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.sql.ast.tree;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.sql.ast.tree.cte.CteStatement;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.from.NamedTableReference;
import org.hibernate.sql.ast.tree.from.TableGroup;

/**
 * @author Christian Beikov
 */
public abstract class AbstractMutationStatement extends AbstractStatement implements MutationStatement {

	private final NamedTableReference targetTable;
	private final List<ColumnReference> returningColumns;
	private TableGroup tableGroup;

	public AbstractMutationStatement(NamedTableReference targetTable) {
		super( new LinkedHashMap<>() );
		this.targetTable = targetTable;
		this.returningColumns = Collections.emptyList();
	}

	public AbstractMutationStatement(Map<String, CteStatement> cteStatements, NamedTableReference targetTable, List<ColumnReference> returningColumns) {
		super( cteStatements );
		this.targetTable = targetTable;
		this.returningColumns = returningColumns;
	}

	public AbstractMutationStatement(Map<String, CteStatement> cteStatements, TableGroup tableGroup, List<ColumnReference> returningColumns) {
		super( cteStatements );
		this.targetTable = (NamedTableReference) tableGroup.getPrimaryTableReference();
		this.returningColumns = returningColumns;
		this.tableGroup = tableGroup;
	}


	@Override
	public TableGroup getTargetTableGroup() {
		return tableGroup;
	}

	@Override
	public NamedTableReference getTargetTable() {
		return targetTable;
	}

	@Override
	public List<ColumnReference> getReturningColumns() {
		return returningColumns;
	}
}
