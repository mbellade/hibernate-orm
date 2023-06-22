/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree;

import org.hibernate.query.sqm.internal.SimpleSqmCopyContext;

/**
 *
 */
public interface SqmCopyContext {

	<T> T getCopy(T original);

	<T> T registerCopy(T original, T copy);

	static SqmCopyContext simpleContext() {
		return new SimpleSqmCopyContext();
	}
}
