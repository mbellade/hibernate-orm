/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.batchfetch;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.*;
import java.util.*;

import org.hibernate.Hibernate;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.annotations.BatchSize;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


@DomainModel(annotatedClasses = {
		BatchNestedDeletedEntityTest.ParentEntity.class,
		BatchNestedDeletedEntityTest.ChildEntity.class
})
@SessionFactory(useCollectingStatementInspector = true)
public class BatchNestedDeletedEntityTest {


	@Test
	public void test(SessionFactoryScope scope) {
		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		scope.inSession( session -> {
			final var children = session.createSelectionQuery( "from ChildEntity order by id", ChildEntity.class ).getResultList();

			session.getTransaction().begin();
			session.createMutationQuery( "delete from ParentEntity where id = 3" ).executeUpdate();
			session.flush();
			session.getTransaction().commit();

			// Only one select for the children, and one delete for the parent
			inspector.assertExecutedCount( 2 );
			inspector.clear();

			assertThat( children ).extracting( ChildEntity::getParent ).allMatch( p -> !Hibernate.isInitialized( p ) );

			final var c1 = children.get( 0 );
			// this triggers the batch fetch
			assertThat( c1.getParent().getName() ).isEqualTo( "parent1" );
			assertThat( c1.getParent() ).matches( Hibernate::isInitialized );

			// only one batch select for parents
			inspector.assertExecutedCount( 1 );

			final var c2 = children.get( 1 );
			final var p2 = c2.getParent();
			assertThat( p2 ).matches( Hibernate::isInitialized ).extracting( ParentEntity::getName ).isEqualTo( "parent2" );

			// no additional queries should be executed
			inspector.assertExecutedCount( 1 );

			final var c3 = children.get( 2 );
			try {
				c3.getParent().getName();
				fail( "Expected ObjectNotFoundException" );
			} catch (Exception e) {
				assertThat( e ).isInstanceOf( ObjectNotFoundException.class ).hasMessageContaining( "No row with the given identifier exists" );
			}
		} );
	}

	@BeforeAll
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final var p1 = new ParentEntity( 1L, "parent1" );
			final var p2 = new ParentEntity( 2L, "parent2" );
			final var p3 = new ParentEntity( 3L, "parent3" );
			final var c1 = new ChildEntity( 1L, "child1", p1 );
			session.persist( c1 );
			final var c2 = new ChildEntity( 2L, "child2", p2 );
			session.persist( c2 );
			final var c3 = new ChildEntity( 3L, "child3", p3 );
			session.persist( c3 );
//			p1.children.add( c1 );
//			p1.children.add( c2 );
			session.persist( p1 );
//			p2.children.add( c1 );
//			p2.children.add( c2 );
			session.persist( p2 );
			session.persist( p3 );
		} );
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Entity(name = "ParentEntity")
	@BatchSize(size = 10)
	static class ParentEntity {
		@Id
		Long id;

		String name;

//		@ManyToMany
//		@JoinTable(inverseForeignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
//		List<ChildEntity> children = new ArrayList<>();

		public ParentEntity() {
		}

		public ParentEntity(Long id, String name) {
			this.id = id;
			this.name = name;
		}

		public String getName() {
			return name;
		}
	}

	@Entity(name = "ChildEntity")
	static class ChildEntity {
		@Id
		Long id;

		String name;

		@ManyToOne(fetch = FetchType.LAZY)
		@JoinColumn(foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
		ParentEntity parent;

		public ChildEntity() {
		}

		public ChildEntity(Long id, String name, ParentEntity parent) {
			this.id = id;
			this.name = name;
			this.parent = parent;
		}

		public ParentEntity getParent() {
			return parent;
		}
	}
}
