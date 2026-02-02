/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.bytecode.enhancement.merge;

import org.hibernate.testing.bytecode.enhancement.EnhancementOptions;
import org.hibernate.testing.bytecode.enhancement.extension.BytecodeEnhanced;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import static org.assertj.core.api.Assertions.assertThat;


@DomainModel(annotatedClasses = { MergeUseTrackerTest.SimpleEntity.class })
@SessionFactory
@BytecodeEnhanced
@EnhancementOptions(
		inlineDirtyChecking = true,
		lazyLoading = true,
		biDirectionalAssociationManagement = false,
		extendedEnhancement = false
)
public class MergeUseTrackerTest {

	@Test
	public void testMergeNewInstanceIntoEntityWithDirtyAttributes(SessionFactoryScope scope) {
		// Persist an initial entity
		scope.inTransaction( session -> {
			SimpleEntity entity = new SimpleEntity( 1L, "initial", 100 );
			session.persist( entity );
		} );

		// In a new transaction:
		// 1. Load the entity to make it persistent
		// 2. Modify it so it has dirty attributes
		// 3. Create a new (untracked) instance with different values
		// 4. Merge the new instance
		// 5. Verify all values from the new instance are applied
		scope.inTransaction( session -> {
			// Load and make dirty
			SimpleEntity persistent = session.find( SimpleEntity.class, 1L );
			persistent.setName( "modified" );

			// Create a new instance with different values
			SimpleEntity newInstance = new SimpleEntity(  );
			newInstance.id = 1L;
			newInstance.name = "from-new-instance";
			newInstance.number = 200;

			// Merge the new instance
			SimpleEntity merged = session.merge( newInstance );

			// The merged result should be the same object as the persistent entity
			assertThat( merged ).isSameAs( persistent );

			// Verify the values were merged correctly
			assertThat( merged.getName() ).isEqualTo( "from-new-instance" );
			assertThat( merged.getNumber() ).isEqualTo( 200 );
		} );

		// Verify changes were persisted
		scope.inTransaction( session -> {
			SimpleEntity entity = session.find( SimpleEntity.class, 1L );
			assertThat( entity.getName() ).isEqualTo( "from-new-instance" );
			assertThat( entity.getNumber() ).isEqualTo( 200 );
		} );
	}

	@Test
	public void testMergeStaleDetachedInstance(SessionFactoryScope scope) {
		// Persist an initial entity
		scope.inTransaction( session -> {
			SimpleEntity entity = new SimpleEntity( 2L, "initial", 100 );
			session.persist( entity );
		} );

		// Load and detach the entity - this becomes our "stale" detached instance
		final SimpleEntity detached = scope.fromTransaction( session ->
			session.find( SimpleEntity.class, 2L )
		);

		// In a separate transaction, update the entity in the database
		// (simulating another user/process modifying it)
		scope.inTransaction( session -> {
			SimpleEntity entity = session.find( SimpleEntity.class, 2L );
			entity.setName( "updated-in-db" );
			entity.setNumber( 999 );
		} );

		// Verify the database has the updated values
		scope.inTransaction( session -> {
			SimpleEntity entity = session.find( SimpleEntity.class, 2L );
			assertThat( entity.getName() ).isEqualTo( "updated-in-db" );
			assertThat( entity.getNumber() ).isEqualTo( 999 );
		} );

		// Now merge the stale detached instance (which still has the old values)
		// Since there's no version column, the merge should succeed and
		// overwrite the database with the stale values
		scope.inTransaction( session -> {
			// Load the current state from DB
			SimpleEntity persistent = session.find( SimpleEntity.class, 2L );
			assertThat( persistent.getName() ).isEqualTo( "updated-in-db" );
			assertThat( persistent.getNumber() ).isEqualTo( 999 );

			// Merge the stale detached instance
			SimpleEntity merged = session.merge( detached );

			// The merged result should be the same object as the persistent entity
			assertThat( merged ).isSameAs( persistent );

			// The stale values from the detached instance overwrite the current state
			assertThat( merged.getName() ).isEqualTo( "initial" );
			assertThat( merged.getNumber() ).isEqualTo( 100 );
		} );

		// Verify the stale values were persisted (no optimistic locking without version)
		scope.inTransaction( session -> {
			SimpleEntity entity = session.find( SimpleEntity.class, 2L );
			assertThat( entity.getName() ).isEqualTo( "initial" );
			assertThat( entity.getNumber() ).isEqualTo( 100 );
		} );
	}

	@AfterEach
	public void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncate();
	}

	@Entity(name = "SimpleEntity")
	@Table(name = "SIMPLE_ENTITY")
	static class SimpleEntity {
		@Id
		Long id;

		@Column
		String name;

		@Column(name = "NUMBER_COLUMN")
		Integer number;

		SimpleEntity() {
		}

		SimpleEntity(Long id, String name, Integer number) {
			this.id = id;
			this.name = name;
			this.number = number;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Integer getNumber() {
			return number;
		}

		public void setNumber(Integer number) {
			this.number = number;
		}
	}
}
