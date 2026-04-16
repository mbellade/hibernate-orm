/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Audited;
import org.hibernate.annotations.RevisionEntity;
import org.hibernate.audit.legacy.AuditReader;
import org.hibernate.audit.legacy.AuditReaderFactory;
import org.hibernate.audit.ModificationType;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.BeforeClassTemplate;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the legacy {@link AuditReader} bridge API, verifying
 * that envers-style code works with just an import change.
 */
@AuditedTest
@SessionFactory
@DomainModel(annotatedClasses = {
		LegacyAuditReaderTest.LegacyEntity.class,
		LegacyAuditReaderTest.LegacyRevInfo.class
})
class LegacyAuditReaderTest {

	@BeforeClassTemplate
	void setupData(SessionFactoryScope scope) {
		// REV 1: create
		scope.getSessionFactory().inTransaction( session -> {
			var entity = new LegacyEntity();
			entity.id = 1L;
			entity.name = "original";
			session.persist( entity );
		} );

		// REV 2: update
		scope.getSessionFactory().inTransaction( session ->
				session.find( LegacyEntity.class, 1L ).name = "updated"
		);

		// REV 3: delete
		scope.getSessionFactory().inTransaction( session ->
				session.remove( session.find( LegacyEntity.class, 1L ) )
		);
	}

	@AfterAll
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void testFind(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			final var revisions = reader.getRevisions( LegacyEntity.class, 1L );
			assertEquals( 3, revisions.size() );

			final int rev1 = ((Number) revisions.get( 0 )).intValue();
			final int rev2 = ((Number) revisions.get( 1 )).intValue();
			final int rev3 = ((Number) revisions.get( 2 )).intValue();

			// find at rev 1
			var entity = reader.find( LegacyEntity.class, 1L, rev1 );
			assertNotNull( entity );
			assertEquals( "original", entity.name );

			// find at rev 2
			entity = reader.find( LegacyEntity.class, 1L, rev2 );
			assertNotNull( entity );
			assertEquals( "updated", entity.name );

			// find at rev 3 (deleted)
			entity = reader.find( LegacyEntity.class, 1L, rev3 );
			assertNull( entity );
		} );
	}

	@Test
	void testGetHistory(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			final var history = reader.getHistory( LegacyEntity.class, 1L );
			assertEquals( 3, history.size() );

			assertEquals( ModificationType.ADD, history.get( 0 ).modificationType() );
			assertEquals( "original", history.get( 0 ).entity().name );

			assertEquals( ModificationType.MOD, history.get( 1 ).modificationType() );
			assertEquals( "updated", history.get( 1 ).entity().name );

			assertEquals( ModificationType.DEL, history.get( 2 ).modificationType() );
		} );
	}

	@Test
	void testFindRevision(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			final var revisions = reader.getRevisions( LegacyEntity.class, 1L );
			final int rev1 = ((Number) revisions.get( 0 )).intValue();

			var revInfo = reader.findRevision( LegacyRevInfo.class, rev1 );
			assertNotNull( revInfo );
			assertEquals( rev1, revInfo.id );
			assertTrue( revInfo.timestamp > 0 );
		} );
	}

	@Test
	void testFindRevisions(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			final var revisions = reader.getRevisions( LegacyEntity.class, 1L );

			final var revMap = reader.findRevisions( LegacyRevInfo.class,
					Set.of( ((Number) revisions.get( 0 )).intValue(), ((Number) revisions.get( 1 )).intValue() ) );
			assertEquals( 2, revMap.size() );
		} );
	}

	@Test
	void testIsEntityClassAudited(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			assertTrue( reader.isEntityClassAudited( LegacyEntity.class ) );
		} );
	}

	@Test
	void testGetEntityNameThrows(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			org.junit.jupiter.api.Assertions.assertThrows(
					UnsupportedOperationException.class,
					() -> reader.getEntityName( 1L, 1, new Object() )
			);
		} );
	}

	@Test
	void testCreateQueryThrows(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			var ex = org.junit.jupiter.api.Assertions.assertThrows(
					UnsupportedOperationException.class,
					reader::createQuery
			);
			assertTrue( ex.getMessage().contains( "HQL" ) );
		} );
	}

	@Test
	void testGetCrossTypeReaderThrows(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			org.junit.jupiter.api.Assertions.assertThrows(
					UnsupportedOperationException.class,
					reader::getCrossTypeRevisionChangesReader
			);
		} );
	}

	@Test
	void testGetRevisionDate(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			final var revisions = reader.getRevisions( LegacyEntity.class, 1L );
			final int rev1 = ((Number) revisions.get( 0 )).intValue();
			final int rev2 = ((Number) revisions.get( 1 )).intValue();

			final var date1 = reader.getRevisionDate( rev1 );
			final var date2 = reader.getRevisionDate( rev2 );
			assertNotNull( date1 );
			assertNotNull( date2 );
			// rev2 should be at same time or later than rev1
			assertTrue( date2.getTime() >= date1.getTime() );
		} );
	}

	@Test
	void testFindByDate(SessionFactoryScope scope) throws InterruptedException {
		// Need entities for this test - re-persist since @BeforeClassTemplate
		// may have deleted them
		scope.getSessionFactory().inTransaction( session -> {
			if ( session.find( LegacyEntity.class, 50L ) == null ) {
				var entity = new LegacyEntity();
				entity.id = 50L;
				entity.name = "dateTest";
				session.persist( entity );
			}
		} );

		// Small delay to ensure timestamp advances
		Thread.sleep( 50 );

		scope.inSession( session -> {
			final AuditReader reader = AuditReaderFactory.get( session );
			// Find by current date should return the entity
			var entity = reader.find( LegacyEntity.class, 50L, new Date() );
			assertNotNull( entity );
			assertEquals( "dateTest", entity.name );
		} );
	}


	// ---- Entities ----

	@Audited
	@Entity(name = "LegacyEntity")
	static class LegacyEntity {
		@Id
		long id;
		String name;
	}

	@RevisionEntity
	@Entity(name = "LegacyRevInfo")
	@Table(name = "REVINFO")
	static class LegacyRevInfo {
		@Id
		@GeneratedValue
		@RevisionEntity.TransactionId
		@Column(name = "REV")
		int id;

		@RevisionEntity.Timestamp
		@Column(name = "REVTSTMP")
		long timestamp;
	}
}
