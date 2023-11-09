/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html.
 */
package org.hibernate.boot.model.internal;

import java.lang.annotation.Annotation;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.MapKeyColumn;

/**
 * @author Emmanuel Bernard
 */
@SuppressWarnings({ "ClassExplicitlyAnnotation" })
public class MapKeyColumnDelegator implements Column {
	private final MapKeyColumn column;

	public MapKeyColumnDelegator(MapKeyColumn column) {
		this.column = column;
	}

	@Override
	public String name() {
		return column.name();
	}

	@Override
	public boolean unique() {
		return column.unique();
	}

	@Override
	public boolean nullable() {
		return column.nullable();
	}

	@Override
	public boolean insertable() {
		return column.insertable();
	}

	@Override
	public boolean updatable() {
		return column.updatable();
	}

	@Override
	public String columnDefinition() {
		return column.columnDefinition();
	}

	@Override
	public String options() {
		return column.options();
	}

	@Override
	public String table() {
		return column.table();
	}

	@Override
	public int length() {
		return column.length();
	}

	@Override
	public int precision() {
		return column.precision();
	}

	@Override
	public int scale() {
		return column.scale();
	}

	@Override
	public CheckConstraint[] check() {
		return new CheckConstraint[0];
	}

	@Override
	public String comment() {
		return "";
	}

	@Override
	public Class<? extends Annotation> annotationType() {
		return Column.class;
	}
}
