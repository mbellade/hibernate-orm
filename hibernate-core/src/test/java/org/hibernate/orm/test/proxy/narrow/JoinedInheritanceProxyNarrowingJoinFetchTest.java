/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.proxy.narrow;

import java.util.List;

import org.hibernate.Hibernate;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrimaryKeyJoinColumn;

import static org.assertj.core.api.Assertions.assertThat;

@DomainModel(annotatedClasses = {
		JoinedInheritanceProxyNarrowingJoinFetchTest.Foo.class,
		JoinedInheritanceProxyNarrowingJoinFetchTest.Bar.class,
		JoinedInheritanceProxyNarrowingJoinFetchTest.Baz.class,
})
@SessionFactory
@Jira("https://hibernate.atlassian.net/browse/HHH-20621")
public class JoinedInheritanceProxyNarrowingJoinFetchTest {

	@BeforeAll
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			var bar = new Bar( 1L );
			session.persist( bar );
			session.persist( new Baz( 1L, bar ) );
		} );
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	public void testFindAfterJoinFetchNarrowing(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			// Step 1: load Baz#1, creates a lazy proxy for Foo#1 typed as Foo (not Bar)
			var baz = session.find( Baz.class, 1L );
			assertThat( baz ).isNotNull();

			// Step 2: get the lazy proxy reference without initializing
			var foo = baz.getFoo();
			assertThat( Hibernate.isInitialized( foo ) ).isFalse();

			// Step 3: load Bar#1 with join fetch on bazList - creates the actual Bar entity
			var bar = session.createQuery(
					"select bar from Bar bar join fetch bar.bazList baz where bar.id = :id",
					Bar.class
			).setParameter( "id", foo.getId() ).getSingleResult();
			assertThat( bar ).isNotNull();

			// Step 4: load Baz#1 with join fetch on foo - discriminator resolves foo as Bar
			var bazFetched = session.createQuery(
					"select baz from Baz baz join fetch baz.foo foo where baz.id = :id",
					Baz.class
			).setParameter( "id", baz.getId() ).getSingleResult();
			assertThat( bazFetched ).isNotNull();

			// After step 4, the proxy should be initialized and wrap the same Bar instance
			assertThat( Hibernate.isInitialized( foo ) ).isTrue();
			assertThat( Hibernate.unproxy( foo ) ).isSameAs( bar );

			// Step 5: find Bar#1 - should not throw NPE
			var barFound = session.find( Bar.class, 1L );
			assertThat( barFound ).isNotNull();
			assertThat( barFound.getId() ).isEqualTo( 1L );
		} );
	}

	@Entity(name = "Foo")
	@Inheritance(strategy = InheritanceType.JOINED)
	public static class Foo {
		@Id
		private Long id;

		@OneToMany(mappedBy = "foo")
		@OrderBy("id")
		private List<Baz> bazList;

		public Foo() {
		}

		public Foo(Long id) {
			this.id = id;
		}

		public Long getId() {
			return id;
		}

		public List<Baz> getBazList() {
			return bazList;
		}
	}

	@Entity(name = "Bar")
	@PrimaryKeyJoinColumn(name = "id")
	public static class Bar extends Foo {
		public Bar() {
		}

		public Bar(Long id) {
			super( id );
		}
	}

	@Entity(name = "Baz")
	public static class Baz {
		@Id
		private Long id;

		@ManyToOne(fetch = FetchType.LAZY)
		@JoinColumn(name = "IDFOO")
		private Foo foo;

		public Baz() {
		}

		public Baz(Long id, Foo foo) {
			this.id = id;
			this.foo = foo;
		}

		public Long getId() {
			return id;
		}

		public Foo getFoo() {
			return foo;
		}
	}
}
