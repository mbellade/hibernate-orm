/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests bidirectional @OneToMany (mappedBy) auditing.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditBidirectionalOneToManyTest.Parent.class,
		AuditBidirectionalOneToManyTest.Child.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.AuditBidirectionalOneToManyTest$TxIdSupplier"))
class AuditBidirectionalOneToManyTest {
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

		// REV 1: parent + one child
		scope.inTransaction( session -> {
			var parent = new Parent( 1L, "Parent" );
			var child = new Child( 1L, "Child A", parent );
			parent.children.add( child );
			session.persist( parent );
			session.persist( child );
		} );

		// REV 2: add second child
		scope.inTransaction( session -> {
			var parent = session.find( Parent.class, 1L );
			var child = new Child( 2L, "Child B", parent );
			parent.children.add( child );
			session.persist( child );
		} );

		// REV 3: remove first child
		scope.inTransaction( session -> {
			var parent = session.find( Parent.class, 1L );
			var child = session.find( Child.class, 1L );
			parent.children.remove( child );
			session.remove( child );
		} );

		// Verify audit revisions exist for parent and children
		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			// Parent: REV 1 only (not modified in REV 2/3 since it's the inverse side)
			var parentRevs = auditLog.getRevisions( Parent.class, 1L );
			assertEquals( 1, parentRevs.size(), "Parent should have 1 revision (initial persist)" );

			// Child A: REV 1 (add) + REV 3 (delete)
			var childARevs = auditLog.getRevisions( Child.class, 1L );
			assertEquals( 2, childARevs.size(), "Child A should have 2 revisions (add + delete)" );

			// Child B: REV 2 (add)
			var childBRevs = auditLog.getRevisions( Child.class, 2L );
			assertEquals( 1, childBRevs.size(), "Child B should have 1 revision (add)" );
		} );
	}

	@Test
	void testPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		// REV 1: parent + child A
		scope.inTransaction( session -> {
			var parent = new Parent( 10L, "PIT Parent" );
			var child = new Child( 10L, "PIT Child A", parent );
			parent.children.add( child );
			session.persist( parent );
			session.persist( child );
		} );

		// REV 2: add child B
		scope.inTransaction( session -> {
			var parent = session.find( Parent.class, 10L );
			var child = new Child( 11L, "PIT Child B", parent );
			parent.children.add( child );
			session.persist( child );
		} );

		// REV 3: remove child A
		scope.inTransaction( session -> {
			var parent = session.find( Parent.class, 10L );
			var child = session.find( Child.class, 10L );
			parent.children.remove( child );
			session.remove( child );
		} );

		// At REV 1: parent should have 1 child (A)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 101 ).openSession() ) {
			var parent = s.find( Parent.class, 10L );
			assertNotNull( parent );
			assertEquals( 1, parent.children.size(), "At REV 1, parent should have 1 child" );
			assertEquals( "PIT Child A", parent.children.get( 0 ).name );
		}

		// At REV 2: parent should have 2 children
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 102 ).openSession() ) {
			var parent = s.find( Parent.class, 10L );
			assertNotNull( parent );
			assertEquals( 2, parent.children.size(), "At REV 2, parent should have 2 children" );
			var names = parent.children.stream().map( c -> c.name ).sorted().toList();
			assertEquals( List.of( "PIT Child A", "PIT Child B" ), names );
		}

		// At REV 3: parent should have 1 child (B only — A was removed)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 103 ).openSession() ) {
			var parent = s.find( Parent.class, 10L );
			assertNotNull( parent );
			assertEquals( 1, parent.children.size(), "At REV 3, parent should have 1 child" );
			assertEquals( "PIT Child B", parent.children.get( 0 ).name );
		}
	}

	@Test
	void testGetHistoryWithCollection(SessionFactoryScope scope) {
		currentTxId = 200;

		// REV 1: parent + child A
		scope.inTransaction( session -> {
			var parent = new Parent( 20L, "Hist Parent" );
			var child = new Child( 20L, "Hist Child A", parent );
			parent.children.add( child );
			session.persist( parent );
			session.persist( child );
		} );

		// REV 2: add child B, update child A
		scope.inTransaction( session -> {
			var parent = session.find( Parent.class, 20L );
			var childA = session.find( Child.class, 20L );
			childA.name = "Hist Child A v2";
			var childB = new Child( 21L, "Hist Child B", parent );
			parent.children.add( childB );
			session.persist( childB );
		} );

		// REV 3: remove child A
		scope.inTransaction( session -> {
			var parent = session.find( Parent.class, 20L );
			var childA = session.find( Child.class, 20L );
			parent.children.remove( childA );
			session.remove( childA );
		} );

		// getHistory on child A: should show 3 revisions (ADD, MOD, DEL)
		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( Child.class, 20L );
			assertEquals( 3, history.size(), "Child A should have 3 revisions" );

			assertEquals( "Hist Child A", history.get( 0 ).entity().name );
			assertNotNull( history.get( 0 ).entity().parent );
			assertEquals( "Hist Parent", history.get( 0 ).entity().parent.name );

			assertEquals( "Hist Child A v2", history.get( 1 ).entity().name );

			// DEL entry should still have the entity state
			assertNotNull( history.get( 2 ).entity() );
		} );

		// getHistory on parent: only 1 revision (initial persist)
		// but at each point in time, the collection should reflect the correct state
		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( Parent.class, 20L );
			assertEquals( 1, history.size(), "Parent should have 1 revision" );
		} );
	}

	/**
	 * Bulk recreation (clear + re-add): only diff audit rows should be written.
	 */
	@Test
	void testPointInTimeReadAfterRecreate(SessionFactoryScope scope) {
		currentTxId = 300;

		// REV 1: parent with child A + B
		scope.inTransaction( session -> {
			var parent = new Parent( 30L, "Rec Parent" );
			var childA = new Child( 30L, "Rec Child A", parent );
			var childB = new Child( 31L, "Rec Child B", parent );
			parent.children.add( childA );
			parent.children.add( childB );
			session.persist( parent );
			session.persist( childA );
			session.persist( childB );
		} );

		// REV 2: recreate — keep B, add C, drop A
		scope.inTransaction( session -> {
			var parent = session.find( Parent.class, 30L );
			var childC = new Child( 32L, "Rec Child C", parent );
			session.persist( childC );
			// remove A
			var childA = session.find( Child.class, 30L );
			parent.children.remove( childA );
			session.remove( childA );
			// add C
			parent.children.add( childC );
		} );

		// Parent is inverse side: only 1 revision (ADD, no collection-change MOD)
		scope.inSession( session -> {
			assertEquals( 1, session.getAuditLog().getRevisions( Parent.class, 30L ).size(),
					"Parent should have exactly 1 revision (inverse side)" );
		} );

		// At REV 1: 2 children
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 301 ).openSession() ) {
			var parent = s.find( Parent.class, 30L );
			assertNotNull( parent );
			assertEquals( 2, parent.children.size(), "At REV 1, parent should have 2 children" );
		}

		// At REV 2: 2 children (B + C — A removed)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 302 ).openSession() ) {
			var parent = s.find( Parent.class, 30L );
			assertNotNull( parent );
			assertEquals( 2, parent.children.size(), "At REV 2, parent should have 2 children" );
			var names = parent.children.stream().map( c -> c.name ).sorted().toList();
			assertEquals( List.of( "Rec Child B", "Rec Child C" ), names );
		}
	}

	@Test
	void testChildPropertyUpdate(SessionFactoryScope scope) {
		currentTxId = 400;

		// REV 1: parent + child A
		scope.inTransaction( session -> {
			var parent = new Parent( 40L, "Upd Parent" );
			var child = new Child( 40L, "Upd Child A", parent );
			parent.children.add( child );
			session.persist( parent );
			session.persist( child );
		} );

		// REV 2: update child name (no collection membership change)
		scope.inTransaction( session -> {
			session.find( Child.class, 40L ).name = "Upd Child A v2";
		} );

		// Child should have 2 revisions (ADD + MOD)
		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			assertEquals( 2, auditLog.getRevisions( Child.class, 40L ).size(),
					"Child should have 2 revisions (ADD + property update)" );
			// Parent: still only 1 revision (inverse side, no collection change)
			assertEquals( 1, auditLog.getRevisions( Parent.class, 40L ).size(),
					"Parent should still have 1 revision" );
		} );

		// Point-in-time: child name should reflect the update
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 402 ).openSession() ) {
			var parent = s.find( Parent.class, 40L );
			assertNotNull( parent );
			assertEquals( 1, parent.children.size() );
			assertEquals( "Upd Child A v2", parent.children.get( 0 ).name );
		}
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "Parent")
	static class Parent {
		@Id
		long id;
		String name;
		@OneToMany(mappedBy = "parent")
		List<Child> children = new ArrayList<>();

		Parent() {}

		Parent(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	@Audited
	@Entity(name = "Child")
	static class Child {
		@Id
		long id;
		String name;
		@ManyToOne
		Parent parent;

		Child() {}

		Child(long id, String name, Parent parent) {
			this.id = id;
			this.name = name;
			this.parent = parent;
		}
	}
}
