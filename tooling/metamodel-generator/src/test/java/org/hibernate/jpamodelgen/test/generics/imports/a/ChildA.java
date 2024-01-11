/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.jpamodelgen.test.generics.imports.a;

import java.util.HashSet;
import java.util.Set;

import org.hibernate.jpamodelgen.test.generics.imports.ChildB;
import org.hibernate.jpamodelgen.test.generics.imports.Parent;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;

/**
 * @author Marco Belladelli
 */
@MappedSuperclass
public abstract class ChildA<A extends Parent, B extends ChildB<A>> extends Parent {
	@OneToMany
	private Set<B> bs = new HashSet<>();

}