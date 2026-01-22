/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.stateless;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DomainModel(annotatedClasses = {
		StatelessSessionMultipleOpsTest.Person.class
})
@SessionFactory(useCollectingStatementInspector = true)
public class StatelessSessionMultipleOpsTest {

	@AfterEach
	public void tearDown(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncate();
	}

	@Test
	public void testInsertMultiple(SessionFactoryScope scope) {
		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		final var count = 5;
		initPeople( count, scope );

		assertThat( inspector.getSqlQueries() )
				.as( "Should have batched insert into single statement preparation" )
				.hasSize( 1 );

		scope.inStatelessSession( session -> {
			for ( int i = 0; i < count; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNotNull().extracting( "name" ).isEqualTo( "person_ " + i );
			}
		} );
	}

	@Test
	public void testUpdateMultiple(SessionFactoryScope scope) {
		final var count = 5;
		final var people = initPeople( count, scope );

		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		for ( final var p : people ) {
			p.name = "Updated " + p.id;
		}

		scope.inStatelessTransaction( session -> session.updateMultiple( people ) );

		assertThat( inspector.getSqlQueries() )
				.as( "Should have batched update into single statement preparation" )
				.hasSize( 1 );

		scope.inStatelessSession( session -> {
			for ( int i = 0; i < count; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNotNull().extracting( "name" ).isEqualTo( "Updated " + i );
			}
		} );
	}

	@Test
	public void testDeleteMultiple(SessionFactoryScope scope) {
		final var count = 5;
		final var people = initPeople( count, scope );

		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();
		scope.inStatelessTransaction( session -> session.deleteMultiple( people ) );

		assertThat( inspector.getSqlQueries() )
				.as( "Should have batched delete into single statement preparation" )
				.hasSize( 1 );

		scope.inStatelessSession( session -> {
			for ( int i = 0; i < count; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNull();
			}
		} );
	}

	@Test
	public void testUpsertMultiple(SessionFactoryScope scope) {
		final var initialCount = 5;
		final var people = initPeople( initialCount, scope );

		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		final var finalCount = 8;
		{
			// Update existing
			var i = 0;
			for ( final var p : people ) {
				p.name = "updated_" + i++;
			}

			// Add new
			for ( ; i < finalCount; i++ ) {
				people.add( new Person( i, "new_" + i ) );
			}
		}

		scope.inStatelessTransaction( session -> session.upsertMultiple( people ) );

		scope.inStatelessSession( session -> {
			for ( int i = 0; i < finalCount; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNotNull().extracting( "name" )
						.isEqualTo( i < initialCount ? "updated_" + i : "new_" + i );
			}
		} );
	}

	@Test
	public void testInsertMultipleSubsequentBatch(SessionFactoryScope scope) {
		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		// First batch: 5 entities
		final var firstBatch = new ArrayList<Person>();
		for ( int i = 0; i < 5; i++ ) {
			firstBatch.add( new Person( i, "first_" + i ) );
		}

		// Second batch: 3 entities (different count)
		final var secondBatch = new ArrayList<Person>();
		for ( int i = 5; i < 8; i++ ) {
			secondBatch.add( new Person( i, "second_" + i ) );
		}

		scope.inStatelessTransaction( session -> {
			session.insertMultiple( firstBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "First batch should be executed immediately after insertMultiple call in a single query" )
					.hasSize( 1 );

			session.insertMultiple( secondBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "Second batch should be executed immediately, not deferred" )
					.hasSize( 2 );

			// Verify all entities were inserted correctly
			for ( int i = 0; i < 8; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNotNull();
				assertThat( p.name ).isEqualTo( i < 5 ? "first_" + i : "second_" + i );
			}
		} );
	}

	@Test
	public void testUpdateMultipleSubsequentBatch(SessionFactoryScope scope) {
		// Initialize 8 entities
		final var people = initPeople( 8, scope );

		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		// First batch: update 5 entities
		final var firstBatch = people.subList( 0, 5 );
		for ( final var p : firstBatch ) {
			p.name = "first_" + p.id;
		}

		// Second batch: update 3 entities (different count)
		final var secondBatch = people.subList( 5, 8 );
		for ( final var p : secondBatch ) {
			p.name = "second_" + p.id;
		}

		scope.inStatelessTransaction( session -> {
			session.updateMultiple( firstBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "First batch should be executed immediately after updateMultiple call in a single query" )
					.hasSize( 1 );

			session.updateMultiple( secondBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "Second batch should be executed immediately, not deferred" )
					.hasSize( 2 );
		} );

		// Verify all entities were updated correctly
		scope.inStatelessSession( session -> {
			for ( int i = 0; i < 8; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNotNull();
				assertThat( p.name ).isEqualTo( i < 5 ? "first_" + i : "second_" + i );
			}
		} );
	}

	@Test
	public void testDeleteMultipleSubsequentBatch(SessionFactoryScope scope) {
		// Initialize 8 entities
		final var people = initPeople( 8, scope );

		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		// First batch: delete 5 entities
		final var firstBatch = people.subList( 0, 5 );

		// Second batch: delete 3 entities (different count)
		final var secondBatch = people.subList( 5, 8 );

		scope.inStatelessTransaction( session -> {
			session.deleteMultiple( firstBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "First batch should be executed immediately after deleteMultiple call in a single query" )
					.hasSize( 1 );

			session.deleteMultiple( secondBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "Second batch should be executed immediately, not deferred" )
					.hasSize( 2 );
		} );

		// Verify all entities were deleted
		scope.inStatelessSession( session -> {
			for ( int i = 0; i < 8; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNull();
			}
		} );
	}

	@Test
	public void testUpsertMultipleSubsequentBatch(SessionFactoryScope scope) {
		// Initialize 5 entities
		initPeople( 5, scope );

		final var inspector = scope.getCollectingStatementInspector();
		inspector.clear();

		// First batch: 5 entities (mix of update and insert)
		final var firstBatch = new ArrayList<Person>();
		for ( int i = 3; i < 8; i++ ) {
			firstBatch.add( new Person( i, "first_" + i ) );
		}

		// Second batch: 3 entities (different count, mix of update and insert)
		final var secondBatch = new ArrayList<Person>();
		for ( int i = 6; i < 9; i++ ) {
			secondBatch.add( new Person( i, "second_" + i ) );
		}

		scope.inStatelessTransaction( session -> {
			session.upsertMultiple( firstBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "First batch should be executed immediately after upsertMultiple call in a single query" )
					.hasSize( 1 );

			session.upsertMultiple( secondBatch );

			assertThat( inspector.getSqlQueries() )
					.as( "Second batch should be executed immediately, not deferred" )
					.hasSize( 2 );
		} );

		// Verify final state: ids 0-2 original, 3-5 from first batch, 6-8 from second batch
		scope.inStatelessSession( session -> {
			for ( int i = 0; i < 9; i++ ) {
				final var p = session.get( Person.class, i );
				assertThat( p ).isNotNull();
				if ( i < 3 ) {
					assertThat( p.name ).isEqualTo( "person_ " + i );
				}
				else if ( i < 6 ) {
					assertThat( p.name ).isEqualTo( "first_" + i );
				}
				else {
					assertThat( p.name ).isEqualTo( "second_" + i );
				}
			}
		} );
	}

	private static List<Person> initPeople(int count, SessionFactoryScope scope) {
		final var people = new ArrayList<Person>();
		for ( int i = 0; i < count; i++ ) {
			final var p = new Person( i, "person_ " + i );
			people.add( p );
		}
		scope.inStatelessTransaction( session -> session.insertMultiple( people ) );
		return people;
	}

	@Entity(name = "Person")
	public static class Person {
		@Id
		public Integer id;
		public String name;

		public Person() {
		}

		public Person(Integer id, String name) {
			this.id = id;
			this.name = name;
		}
	}
}
