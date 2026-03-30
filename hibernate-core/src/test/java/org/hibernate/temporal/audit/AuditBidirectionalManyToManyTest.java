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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests bidirectional @ManyToMany auditing.
 * <p>
 * The owning side's collection changes are tracked; the inverse (mappedBy) side
 * does NOT get extra MOD revisions for relationship changes.
 * <p>
 * Note: envers' {@code org.hibernate.envers.revision_on_collection_change} setting
 * (default: true) would write MOD rows for the inverse entity as well.
 * We don't replicate that behavior for now — it requires a work-unit buffering/merging
 * layer for deduplication that doesn't exist in core's audit coordinators.
 *
 * @see org.hibernate.orm.test.envers.integration.manytomany.BasicSet
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

	// todo (envers-rewrite) : point-in-time read for bidirectional M2M collections
	//  requires fixing the overarching collection read-side table reference resolution gap

	// ---- Entity classes ----

	@Audited
	@Entity(name = "BiM2mOwning")
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
	@Entity(name = "BiM2mOwned")
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
