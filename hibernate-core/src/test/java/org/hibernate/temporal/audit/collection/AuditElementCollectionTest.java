/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit.collection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Tuple;
import org.hibernate.annotations.Audited;
import org.hibernate.annotations.SortNatural;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.SharedSessionContract;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests @Audited element collections: indexed lists, maps, embeddable sets.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditElementCollectionTest.ListEntity.class,
		AuditElementCollectionTest.MapEntity.class,
		AuditElementCollectionTest.EmbeddableSetEntity.class,
		AuditElementCollectionTest.ArrayEntity.class,
		AuditElementCollectionTest.SortedSetEntity.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.collection.AuditElementCollectionTest$TxIdSupplier"))
class AuditElementCollectionTest {
	private static int currentTxId;

	public static class TxIdSupplier implements TransactionIdentifierSupplier<Integer> {
		@Override
		public Integer getTransactionIdentifier(SharedSessionContract session) {
			return ++currentTxId;
		}

	}

	// ---- List with @OrderColumn ----

	@Test
	void testIndexedList(SessionFactoryScope scope) {
		currentTxId = 0;

		// REV 1: create with 2 elements
		scope.inTransaction( session -> {
			var e = new ListEntity( 1L );
			e.strings.add( "alpha" );
			e.strings.add( "beta" );
			session.persist( e );
		} );

		// REV 2: add element, remove first
		scope.inTransaction( session -> {
			var e = session.find( ListEntity.class, 1L );
			e.strings.add( "gamma" );
			e.strings.remove( 0 );
		} );

		scope.inSession( session -> {
			assertThat( session.getAuditLog().getRevisions( ListEntity.class, 1L ) ).hasSize( 2 );
		} );

		// At REV 1: [alpha, beta]
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 1 ).openSession() ) {
			var e = s.find( ListEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.strings ).containsExactly( "alpha", "beta" );
		}

		// At REV 2: [beta, gamma] (alpha removed, gamma added)
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 2 ).openSession() ) {
			var e = s.find( ListEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.strings ).containsExactly( "beta", "gamma" );
		}

		// Verify DEL audit rows store both index and element value (matching envers)
		scope.inSession( session -> {
			var delRows = session.createNativeQuery(
					"select strings, strings_ORDER from ListEntity_strings_aud"
							+ " where REVTYPE = 2 order by strings_ORDER", Tuple.class
			).getResultList();
			assertThat( delRows ).hasSizeGreaterThanOrEqualTo( 1 );
			assertThat( delRows ).anySatisfy( row -> {
				assertThat( row.get( "strings" ) ).isEqualTo( "alpha" );
				assertThat( row.get( "strings_ORDER" ) ).isEqualTo( 0 );
			} );
		} );
	}

	// ---- Map<String, String> ----

	@Test
	void testStringMap(SessionFactoryScope scope) {
		currentTxId = 100;

		// REV 1: create with 2 entries
		scope.inTransaction( session -> {
			var e = new MapEntity( 1L );
			e.strings.put( "key1", "value1" );
			e.strings.put( "key2", "value2" );
			session.persist( e );
		} );

		// REV 2: update one value, add new entry, remove one
		scope.inTransaction( session -> {
			var e = session.find( MapEntity.class, 1L );
			e.strings.put( "key1", "updated1" );
			e.strings.put( "key3", "value3" );
			e.strings.remove( "key2" );
		} );

		scope.inSession( session -> {
			assertThat( session.getAuditLog().getRevisions( MapEntity.class, 1L ) ).hasSize( 2 );
		} );

		// At REV 1: {key1=value1, key2=value2}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 101 ).openSession() ) {
			var e = s.find( MapEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.strings ).containsEntry( "key1", "value1" )
					.containsEntry( "key2", "value2" )
					.hasSize( 2 );
		}

		// At REV 2: {key1=updated1, key3=value3}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 102 ).openSession() ) {
			var e = s.find( MapEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.strings ).containsEntry( "key1", "updated1" )
					.containsEntry( "key3", "value3" )
					.hasSize( 2 );
		}

		// Verify DEL audit rows store both key and value (matching envers)
		scope.inSession( session -> {
			var delRows = session.createNativeQuery(
					"select strings_KEY, strings from MapEntity_strings_aud"
							+ " where REVTYPE = 2 order by strings_KEY", Tuple.class
			).getResultList();
			// key1 value update -> DEL old + ADD new (envers behavior)
			assertThat( delRows ).anySatisfy( row -> {
				assertThat( row.get( "strings_KEY" ) ).isEqualTo( "key1" );
				assertThat( row.get( "strings" ) ).isEqualTo( "value1" );
			} );
			// key2 removal -> DEL with full entry
			assertThat( delRows ).anySatisfy( row -> {
				assertThat( row.get( "strings_KEY" ) ).isEqualTo( "key2" );
				assertThat( row.get( "strings" ) ).isEqualTo( "value2" );
			} );
		} );
	}

	// ---- Set<Embeddable> ----

	@Test
	void testEmbeddableSet(SessionFactoryScope scope) {
		currentTxId = 200;

		// REV 1: create with 2 components
		scope.inTransaction( session -> {
			var e = new EmbeddableSetEntity( 1L );
			e.components.add( new Component( "Alice", 90 ) );
			e.components.add( new Component( "Bob", 85 ) );
			session.persist( e );
		} );

		// REV 2: add one, remove one
		scope.inTransaction( session -> {
			var e = session.find( EmbeddableSetEntity.class, 1L );
			e.components.removeIf( c -> c.name.equals( "Alice" ) );
			e.components.add( new Component( "Charlie", 95 ) );
		} );

		scope.inSession( session -> {
			assertThat( session.getAuditLog().getRevisions( EmbeddableSetEntity.class, 1L ) ).hasSize( 2 );
		} );

		// At REV 1: {Alice/30, Bob/25}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 201 ).openSession() ) {
			var e = s.find( EmbeddableSetEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.components ).extracting( c -> c.name )
					.containsExactlyInAnyOrder( "Alice", "Bob" );
		}

		// At REV 2: {Bob/85, Charlie/95}
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 202 ).openSession() ) {
			var e = s.find( EmbeddableSetEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.components ).extracting( c -> c.name )
					.containsExactlyInAnyOrder( "Bob", "Charlie" );
		}

		// Verify DEL audit rows store the full embeddable (name + score)
		scope.inSession( session -> {
			var delRows = session.createNativeQuery(
					"select name, score from EmbeddableSetEntity_components_aud"
							+ " where REVTYPE = 2", Tuple.class
			).getResultList();
			assertThat( delRows ).hasSize( 1 );
			assertThat( delRows.get( 0 ).get( "name" ) ).isEqualTo( "Alice" );
			assertThat( delRows.get( 0 ).get( "score" ) ).isEqualTo( 90 );
		} );
	}

	// ---- String array with @OrderColumn ----

	@Test
	void testStringArray(SessionFactoryScope scope) {
		currentTxId = 300;

		// REV 1: create with 2 elements
		scope.inTransaction( session -> {
			var e = new ArrayEntity( 1L );
			e.strings = new String[] { "alpha", "beta" };
			session.persist( e );
		} );

		// REV 2: replace element at index 0, add element
		scope.inTransaction( session -> {
			var e = session.find( ArrayEntity.class, 1L );
			e.strings = new String[] { "gamma", "beta", "delta" };
		} );

		scope.inSession( session -> {
			assertThat( session.getAuditLog().getRevisions( ArrayEntity.class, 1L ) ).hasSize( 2 );
		} );

		// At REV 1: [alpha, beta]
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 301 ).openSession() ) {
			var e = s.find( ArrayEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.strings ).containsExactly( "alpha", "beta" );
		}

		// At REV 2: [gamma, beta, delta]
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 302 ).openSession() ) {
			var e = s.find( ArrayEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.strings ).containsExactly( "gamma", "beta", "delta" );
		}

		// Verify diff: "beta" unchanged at index 1 -- no audit rows for it in REV 2.
		scope.inSession( session -> {
			var rev2Rows = session.createNativeQuery(
					"select strings, strings_ORDER, REVTYPE from ArrayEntity_strings_aud"
							+ " where REV = 302 order by strings_ORDER, REVTYPE", Tuple.class
			).getResultList();
			assertThat( rev2Rows ).noneMatch( row -> "beta".equals( row.get( "strings" ) ) );
		} );
	}

	// ---- SortedSet<String> with @SortNatural ----

	@Test
	void testSortedSet(SessionFactoryScope scope) {
		currentTxId = 400;

		scope.inTransaction( session -> {
			var e = new SortedSetEntity( 1L );
			e.tags.add( "beta" );
			e.tags.add( "alpha" );
			session.persist( e );
		} );

		scope.inTransaction( session -> {
			var e = session.find( SortedSetEntity.class, 1L );
			e.tags.remove( "alpha" );
			e.tags.add( "gamma" );
		} );

		scope.inSession( session -> {
			assertThat( session.getAuditLog().getRevisions( SortedSetEntity.class, 1L ) ).hasSize( 2 );
		} );

		// At REV 1: {alpha, beta} (sorted)
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 401 ).openSession() ) {
			var e = s.find( SortedSetEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.tags ).containsExactly( "alpha", "beta" );
		}

		// At REV 2: {beta, gamma} (sorted)
		try ( var s = scope.getSessionFactory().withOptions().atTransaction( 402 ).openSession() ) {
			var e = s.find( SortedSetEntity.class, 1L );
			assertThat( e ).isNotNull();
			assertThat( e.tags ).containsExactly( "beta", "gamma" );
		}
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "ListEntity")
	static class ListEntity {
		@Id long id;
		@ElementCollection
		@OrderColumn
		List<String> strings = new ArrayList<>();
		ListEntity() {}
		ListEntity(long id) { this.id = id; }
	}

	@Audited
	@Entity(name = "MapEntity")
	static class MapEntity {
		@Id long id;
		@ElementCollection
		@MapKeyColumn(nullable = false)
		Map<String, String> strings = new HashMap<>();
		MapEntity() {}
		MapEntity(long id) { this.id = id; }
	}

	@Audited
	@Entity(name = "EmbeddableSetEntity")
	static class EmbeddableSetEntity {
		@Id long id;
		@ElementCollection
		Set<Component> components = new HashSet<>();
		EmbeddableSetEntity() {}
		EmbeddableSetEntity(long id) { this.id = id; }
	}

	@Audited
	@Entity(name = "ArrayEntity")
	static class ArrayEntity {
		@Id long id;
		@ElementCollection
		@OrderColumn
		String[] strings;
		ArrayEntity() {}
		ArrayEntity(long id) { this.id = id; }
	}

	@Audited
	@Entity(name = "SortedSetEntity")
	static class SortedSetEntity {
		@Id long id;
		@ElementCollection
		@SortNatural
		SortedSet<String> tags = new TreeSet<>();
		SortedSetEntity() {}
		SortedSetEntity(long id) { this.id = id; }
	}

	@Embeddable
	static class Component {
		String name;
		int score;
		Component() {}
		Component(String name, int score) { this.name = name; this.score = score; }

		@Override
		public boolean equals(Object o) {
			return o instanceof Component c && Objects.equals( name, c.name ) && score == c.score;
		}

		@Override
		public int hashCode() {
			return Objects.hash( name, score );
		}
	}
}
