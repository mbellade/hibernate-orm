/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import org.hibernate.annotations.Audited;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.temporal.spi.TransactionIdentifierSupplier;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests bidirectional @ManyToMany auditing.
 * The owning side's collection changes are tracked; the inverse (mappedBy) side
 * does NOT get extra MOD revisions for relationship changes.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditBidirectionalManyToManyTest.OwningEntity.class,
		AuditBidirectionalManyToManyTest.OwnedEntity.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.AuditBidirectionalManyToManyTest$TxIdSupplier"))
class AuditBidirectionalManyToManyTest {
	private static int currentTxId;

	public static class TxIdSupplier implements TransactionIdentifierSupplier<Integer> {
		@Override
		public Integer getTransactionIdentifier(SharedSessionContractImplementor session) {
			return ++currentTxId;
		}

		@Override
		public Class<Integer> getIdentifierType() {
			return Integer.class;
		}
	}

	@Test
	void testWriteSide(SessionFactoryScope scope) {
		currentTxId = 0;

		// REV 1: create all entities, no collection changes
		scope.inTransaction( session -> {
			session.persist( new OwnedEntity( 1, "ed1" ) );
			session.persist( new OwnedEntity( 2, "ed2" ) );
			session.persist( new OwningEntity( 3, "ing1" ) );
			session.persist( new OwningEntity( 4, "ing2" ) );
		} );

		// REV 2: ing1.references = {ed1}, ing2.references = {ed1, ed2}
		scope.inTransaction( session -> {
			var ing1 = session.find( OwningEntity.class, 3 );
			var ing2 = session.find( OwningEntity.class, 4 );
			var ed1 = session.find( OwnedEntity.class, 1 );
			var ed2 = session.find( OwnedEntity.class, 2 );
			ing1.references.add( ed1 );
			ing2.references.add( ed1 );
			ing2.references.add( ed2 );
		} );

		// REV 3: ing1.references.add(ed2)
		scope.inTransaction( session -> {
			var ing1 = session.find( OwningEntity.class, 3 );
			var ed2 = session.find( OwnedEntity.class, 2 );
			ing1.references.add( ed2 );
		} );

		// REV 4: ing1.references.remove(ed1)
		scope.inTransaction( session -> {
			var ing1 = session.find( OwningEntity.class, 3 );
			ing1.references.removeIf( e -> e.id == 1 );
		} );

		// REV 5: ing1 clears all references
		scope.inTransaction( session -> {
			var ing1 = session.find( OwningEntity.class, 3 );
			ing1.references.clear();
		} );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			// Owning side: ing1 at [1, 2, 3, 4, 5], ing2 at [1, 2]
			assertEquals( 5, auditLog.getRevisions( OwningEntity.class, 3 ).size(),
					"ing1 should have 5 revisions" );
			assertEquals( 2, auditLog.getRevisions( OwningEntity.class, 4 ).size(),
					"ing2 should have 2 revisions" );

			// Inverse side: only ADD revisions, no MOD from relationship changes
			assertEquals( 1, auditLog.getRevisions( OwnedEntity.class, 1 ).size(),
					"ed1 should have 1 revision (ADD only)" );
			assertEquals( 1, auditLog.getRevisions( OwnedEntity.class, 2 ).size(),
					"ed2 should have 1 revision (ADD only)" );
		} );
	}

	@Test
	void testGetHistory(SessionFactoryScope scope) {
		currentTxId = 200;

		scope.inTransaction( session -> {
			var ed1 = new OwnedEntity( 20, "hist ed1" );
			session.persist( ed1 );
			var ing1 = new OwningEntity( 21, "hist ing1" );
			ing1.references.add( ed1 );
			session.persist( ing1 );
		} );

		scope.inTransaction( session -> {
			var ed2 = new OwnedEntity( 22, "hist ed2" );
			session.persist( ed2 );
			session.find( OwningEntity.class, 21 ).references.add( ed2 );
		} );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			// Owning entity should have 2 revisions (ADD + collection change MOD)
			var history = auditLog.getHistory( OwningEntity.class, 21 );
			assertEquals( 2, history.size() );
			assertEquals( "hist ing1", history.get( 0 ).entity().data );

			// Owned entities: only 1 revision each (ADD)
			assertEquals( 1, auditLog.getHistory( OwnedEntity.class, 20 ).size() );
			assertEquals( 1, auditLog.getHistory( OwnedEntity.class, 22 ).size() );
		} );
	}

	@Test
	void testPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		// REV 1: create entities
		scope.inTransaction( session -> {
			session.persist( new OwnedEntity( 10, "pit ed1" ) );
			session.persist( new OwnedEntity( 11, "pit ed2" ) );
			var ing = new OwningEntity( 12, "pit ing" );
			ing.references.add( session.find( OwnedEntity.class, 10 ) );
			session.persist( ing );
		} );

		// REV 2: add ed2
		scope.inTransaction( session -> {
			var ing = session.find( OwningEntity.class, 12 );
			ing.references.add( session.find( OwnedEntity.class, 11 ) );
		} );

		// REV 3: remove ed1
		scope.inTransaction( session -> {
			var ing = session.find( OwningEntity.class, 12 );
			ing.references.removeIf( e -> e.id == 10 );
		} );

		// At REV 1: 1 reference (ed1)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 101 ).openSession() ) {
			var ing = s.find( OwningEntity.class, 12 );
			assertNotNull( ing );
			assertEquals( 1, ing.references.size(), "At REV 1, should have 1 reference" );
		}

		// At REV 2: 2 references
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 102 ).openSession() ) {
			var ing = s.find( OwningEntity.class, 12 );
			assertNotNull( ing );
			assertEquals( 2, ing.references.size(), "At REV 2, should have 2 references" );
		}

		// At REV 3: 1 reference (ed2 only)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 103 ).openSession() ) {
			var ing = s.find( OwningEntity.class, 12 );
			assertNotNull( ing );
			assertEquals( 1, ing.references.size(), "At REV 3, should have 1 reference" );
			assertEquals( "pit ed2", ing.references.iterator().next().data );
		}
	}

	/**
	 * Bulk recreation (clear + re-add): only diff audit rows should be written.
	 */
	@Test
	void testPointInTimeReadAfterRecreate(SessionFactoryScope scope) {
		currentTxId = 300;

		// REV 1: ing with ed1 + ed2
		scope.inTransaction( session -> {
			session.persist( new OwnedEntity( 30, "rec ed1" ) );
			session.persist( new OwnedEntity( 31, "rec ed2" ) );
			var ing = new OwningEntity( 32, "rec ing" );
			ing.references.add( session.find( OwnedEntity.class, 30 ) );
			ing.references.add( session.find( OwnedEntity.class, 31 ) );
			session.persist( ing );
		} );

		// REV 2: recreate — clear and re-add ed2 + new ed3
		scope.inTransaction( session -> {
			session.persist( new OwnedEntity( 33, "rec ed3" ) );
			var ing = session.find( OwningEntity.class, 32 );
			ing.references.clear();
			ing.references.add( session.find( OwnedEntity.class, 31 ) );
			ing.references.add( session.find( OwnedEntity.class, 33 ) );
		} );

		// Owning entity: ADD + recreate = 2 revisions (not more)
		scope.inSession( session -> {
			assertEquals( 2, session.getAuditLog().getRevisions( OwningEntity.class, 32 ).size(),
					"Owning entity should have exactly 2 revisions (ADD + recreate)" );
		} );

		// At REV 1: 2 references
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 301 ).openSession() ) {
			var ing = s.find( OwningEntity.class, 32 );
			assertNotNull( ing );
			assertEquals( 2, ing.references.size(), "At REV 1, should have 2 references" );
		}

		// At REV 2: 2 references (ed2 + ed3 — ed1 dropped)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 302 ).openSession() ) {
			var ing = s.find( OwningEntity.class, 32 );
			assertNotNull( ing );
			assertEquals( 2, ing.references.size(), "At REV 2, should have 2 references" );
			var names = ing.references.stream().map( e -> e.data ).sorted().toList();
			assertEquals( List.of( "rec ed2", "rec ed3" ), names );
		}
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "OwningEntity")
	static class OwningEntity {
		@Id
		int id;
		String data;
		@ManyToMany
		Set<OwnedEntity> references = new HashSet<>();

		OwningEntity() {}

		OwningEntity(int id, String data) {
			this.id = id;
			this.data = data;
		}
	}

	@Audited
	@Entity(name = "OwnedEntity")
	static class OwnedEntity {
		@Id
		int id;
		String data;
		@ManyToMany(mappedBy = "references")
		Set<OwningEntity> referencing = new HashSet<>();

		OwnedEntity() {}

		OwnedEntity(int id, String data) {
			this.id = id;
			this.data = data;
		}
	}
}
