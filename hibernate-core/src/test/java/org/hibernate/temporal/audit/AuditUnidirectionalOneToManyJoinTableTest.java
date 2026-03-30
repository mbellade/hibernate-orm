/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.temporal.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
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
 * Tests for unidirectional @OneToMany with @JoinTable.
 * Uses a join table (like @ElementCollection / @ManyToMany), so the
 * collection persister should have isOneToMany()=false.
 */
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditUnidirectionalOneToManyJoinTableTest.Team.class,
		AuditUnidirectionalOneToManyJoinTableTest.Player.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.TRANSACTION_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.AuditUnidirectionalOneToManyJoinTableTest$TxIdSupplier"))
class AuditUnidirectionalOneToManyJoinTableTest {
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

		// REV 1: team + one player
		scope.inTransaction( session -> {
			var player = new Player( 1L, "Alice" );
			session.persist( player );
			var team = new Team( 1L, "Red Team" );
			team.players.add( player );
			session.persist( team );
		} );

		// REV 2: add second player
		scope.inTransaction( session -> {
			var player = new Player( 2L, "Bob" );
			session.persist( player );
			var team = session.find( Team.class, 1L );
			team.players.add( player );
		} );

		// REV 3: remove first player from team
		scope.inTransaction( session -> {
			var team = session.find( Team.class, 1L );
			team.players.removeIf( p -> p.id == 1L );
		} );

		// REV 4: delete team (bulk removal of remaining players from collection)
		scope.inTransaction( session -> {
			var team = session.find( Team.class, 1L );
			session.remove( team );
		} );

		scope.inSession( session -> {
			var auditLog = session.getAuditLog();
			// Team: ADD + 2 collection changes + DEL = 4 revisions
			assertEquals( 4, auditLog.getRevisions( Team.class, 1L ).size(),
					"Team should have 4 revisions (ADD + 2 collection changes + DEL)" );
			assertEquals( 1, auditLog.getRevisions( Player.class, 1L ).size() );
			assertEquals( 1, auditLog.getRevisions( Player.class, 2L ).size() );
		} );
	}

	@Test
	void testPointInTimeRead(SessionFactoryScope scope) {
		currentTxId = 100;

		// REV 1: team + player A
		scope.inTransaction( session -> {
			var player = new Player( 10L, "PIT Alice" );
			session.persist( player );
			var team = new Team( 10L, "PIT Red Team" );
			team.players.add( player );
			session.persist( team );
		} );

		// REV 2: add player B
		scope.inTransaction( session -> {
			var player = new Player( 11L, "PIT Bob" );
			session.persist( player );
			var team = session.find( Team.class, 10L );
			team.players.add( player );
		} );

		// At REV 1: team should have 1 player (Alice)
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 101 ).openSession() ) {
			var team = s.find( Team.class, 10L );
			assertNotNull( team );
			assertEquals( 1, team.players.size(), "At REV 1, team should have 1 player" );
			assertEquals( "PIT Alice", team.players.get( 0 ).name );
		}

		// At REV 2: team should have 2 players
		try ( var s = scope.getSessionFactory().withOptions()
				.atTransaction( 102 ).openSession() ) {
			var team = s.find( Team.class, 10L );
			assertNotNull( team );
			assertEquals( 2, team.players.size(), "At REV 2, team should have 2 players" );
		}

		// todo (envers-rewrite) : collection-level point-in-time reads for join-table collections
		//  don't yet reflect removals — the join table audit table has DEL rows but the
		//  collection loading doesn't apply temporal predicates to the join table
	}

	@Test
	void testGetHistory(SessionFactoryScope scope) {
		currentTxId = 200;

		scope.inTransaction( session -> {
			var player = new Player( 20L, "Hist Alice" );
			session.persist( player );
			var team = new Team( 20L, "Hist Team" );
			team.players.add( player );
			session.persist( team );
		} );

		scope.inTransaction( session -> {
			var player = new Player( 21L, "Hist Bob" );
			session.persist( player );
			session.find( Team.class, 20L ).players.add( player );
		} );

		scope.inSession( session -> {
			var history = session.getAuditLog().getHistory( Team.class, 20L );
			assertEquals( 2, history.size(), "Team has ADD + MOD (collection change)" );
			assertEquals( "Hist Team", history.get( 0 ).entity().name );
		} );
	}

	// ---- Entity classes ----

	@Audited
	@Entity(name = "JtOtmTeam")
	static class Team {
		@Id
		long id;
		String name;
		@OneToMany
		@JoinTable
		List<Player> players = new ArrayList<>();

		Team() {}

		Team(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	@Audited
	@Entity(name = "JtOtmPlayer")
	static class Player {
		@Id
		long id;
		String name;

		Player() {}

		Player(long id, String name) {
			this.id = id;
			this.name = name;
		}
	}
}
