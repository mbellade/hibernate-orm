/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.sql.ast.spi.query.from;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.hibernate.persister.entity.UnionSubclassEntityPersister;
import org.hibernate.spi.NavigablePath;

import static java.util.Collections.emptyList;

/**
 * @author Andrea Boriero
 */
public class UnionTableGroup extends AbstractTableGroup {
	private final UnionTableReference tableReference;
	private final Predicate<String> tableReferenceJoinNameChecker;
	private final Function<String, TableReferenceJoin> tableReferenceJoinCreator;
	private List<TableReferenceJoin> tableReferenceJoins;

	public UnionTableGroup(
			boolean canUseInnerJoins,
			NavigablePath navigablePath,
			UnionTableReference tableReference,
			UnionSubclassEntityPersister modelPart,
			String sourceAlias) {
		this(
				canUseInnerJoins,
				navigablePath,
				tableReference,
				modelPart,
				sourceAlias,
				tableExpression -> false,
				tableExpression -> null
		);
	}

	public UnionTableGroup(
			boolean canUseInnerJoins,
			NavigablePath navigablePath,
			UnionTableReference tableReference,
			UnionSubclassEntityPersister modelPart,
			String sourceAlias,
			Predicate<String> tableReferenceJoinNameChecker,
			Function<String, TableReferenceJoin> tableReferenceJoinCreator) {
		super( canUseInnerJoins, navigablePath, modelPart, sourceAlias, null, null );
		this.tableReference = tableReference;
		this.tableReferenceJoinNameChecker = tableReferenceJoinNameChecker;
		this.tableReferenceJoinCreator = tableReferenceJoinCreator;
	}

	@Override
	public void applyAffectedTableNames(Consumer<String> nameCollector) {
	}

	@Override
	public UnionTableReference getPrimaryTableReference() {
		return tableReference;
	}

	@Override
	public List<TableReferenceJoin> getTableReferenceJoins() {
		return tableReferenceJoins == null ? emptyList() : tableReferenceJoins;
	}

	@Override
	public boolean isRealTableGroup() {
		return ( tableReferenceJoins != null && !tableReferenceJoins.isEmpty() ) || super.isRealTableGroup();
	}

	@Override
	public TableReference getTableReference(
			NavigablePath navigablePath,
			String tableExpression,
			boolean resolve) {
		if ( tableReference.getTableReference( navigablePath, tableExpression, resolve ) != null ) {
			return tableReference;
		}
		if ( tableReferenceJoinNameChecker.test( tableExpression ) ) {
			if ( tableReferenceJoins != null ) {
				for ( TableReferenceJoin join : tableReferenceJoins ) {
					final var tableReference = join.getJoinedTableReference()
							.getTableReference( navigablePath, tableExpression, resolve );
					if ( tableReference != null ) {
						return tableReference;
					}
				}
			}
			if ( resolve ) {
				final var join = tableReferenceJoinCreator.apply( tableExpression );
				if ( join != null ) {
					if ( tableReferenceJoins == null ) {
						tableReferenceJoins = new ArrayList<>();
					}
					tableReferenceJoins.add( join );
					return join.getJoinedTableReference();
				}
			}
			return null;
		}
		return super.getTableReference( navigablePath, tableExpression, resolve );
	}
}
