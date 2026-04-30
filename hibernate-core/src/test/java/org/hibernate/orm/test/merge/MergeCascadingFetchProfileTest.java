/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.merge;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SessionImplementor;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that merging an entity with a non-cascade eager {@link ManyToOne}
 * does not trigger loading of cascade-eligible collections on the
 * transitively loaded associated entity. The {@code CascadingFetchProfile.MERGE}
 * should only affect associations reachable through a cascade-eligible path.
 *
 * @author Marco Belladelli
 */
@DomainModel( annotatedClasses = {
		MergeCascadingFetchProfileTest.UserPassword.class,
		MergeCascadingFetchProfileTest.PasswordPolicy.class,
		MergeCascadingFetchProfileTest.PolicyAuditLog.class,
} )
@SessionFactory( generateStatistics = true )
public class MergeCascadingFetchProfileTest {
	@BeforeEach
	public void setUp(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
		scope.inTransaction( session -> {
			var policy = new PasswordPolicy( 1L, "default" );
			session.persist( policy );
			for ( int i = 0; i < 10; i++ ) {
				session.persist( new PolicyAuditLog( (long) ( i + 1 ), policy, "event_" + i ) );
			}
			session.persist( new UserPassword( 1L, "hash_value", policy ) );
		} );
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	public void testMergeDoesNotLoadNonCascadeReachableCollections(SessionFactoryScope scope) {
		final var detached = new UserPassword( 1L, "updated_hash", new PasswordPolicy( 1L, "default" ) );
		scope.inTransaction( session -> {
			var stats = session.getSessionFactory().getStatistics();
			stats.clear();

			var merged = session.merge( detached );

			assertThat( merged.getHash() ).isEqualTo( "updated_hash" );

			// The policy should be loaded (it's an eager ManyToOne),
			// but its auditLogs collection should NOT be initialized:
			// there is no cascade path from UserPassword to PasswordPolicy
			var managedPolicy = merged.getPolicy();
			assertThat( managedPolicy ).isNotNull();
			assertThat( Hibernate.isInitialized( managedPolicy.getAuditLogs() ) )
					.as( "PasswordPolicy.auditLogs should not be initialized during merge "
							+ "because UserPassword.policy has no cascade" )
					.isFalse();
		} );
	}

	@Test
	public void testMergeDoesNotCascadeThroughNonCascadeAssociation(SessionFactoryScope scope) {
		// Build a detached UserPassword whose policy has a modified auditLogs collection
		var detachedPolicy = new PasswordPolicy( 1L, "default" );
		var newLog = new PolicyAuditLog( 99L, detachedPolicy, "new_event" );
		detachedPolicy.getAuditLogs().add( newLog );
		var detached = new UserPassword( 1L, "updated_hash", detachedPolicy );

		scope.inTransaction( session -> session.merge( detached ) );

		// The new audit log should NOT have been persisted,
		// because UserPassword.policy has no cascade
		scope.inTransaction( session -> {
			var log = session.find( PolicyAuditLog.class, 99L );
			assertThat( log )
					.as( "PolicyAuditLog added to detached policy should not be persisted "
							+ "because UserPassword.policy has no cascade" )
					.isNull();
			// Original 10 audit logs should still be there, unchanged
			var count = session.createQuery(
					"select count(l) from PolicyAuditLog l", Long.class
			).getSingleResult();
			assertThat( count ).isEqualTo( 10L );
		} );
	}

	@Test
	public void testMergePersistenceContextSize(SessionFactoryScope scope) {
		final var detached = new UserPassword( 1L, "updated_hash", new PasswordPolicy( 1L, "default" ) );
		scope.inTransaction( session -> {
			session.merge( detached );

			// Only UserPassword and PasswordPolicy should be in the persistence context,
			// not the 10 PolicyAuditLog entities
			var entityCount = ( (SessionImplementor) session )
					.getPersistenceContextInternal()
					.getNumberOfManagedEntities();
			assertThat( entityCount )
					.as( "Only UserPassword and PasswordPolicy should be managed, "
							+ "not the PolicyAuditLog entities from the non-cascade-reachable collection" )
					.isEqualTo( 2 );
		} );
	}

	@Entity( name = "UserPassword" )
	public static class UserPassword {
		@Id
		private Long id;

		private String hash;

		@ManyToOne
		@JoinColumn( name = "policy_id" )
		private PasswordPolicy policy;

		public UserPassword() {
		}

		public UserPassword(Long id, String hash, PasswordPolicy policy) {
			this.id = id;
			this.hash = hash;
			this.policy = policy;
		}

		public Long getId() {
			return id;
		}

		public String getHash() {
			return hash;
		}

		public PasswordPolicy getPolicy() {
			return policy;
		}
	}

	@Entity( name = "PasswordPolicy" )
	public static class PasswordPolicy {
		@Id
		private Long id;

		private String name;

		@OneToMany( mappedBy = "policy", cascade = CascadeType.ALL )
		private List<PolicyAuditLog> auditLogs = new ArrayList<>();

		public PasswordPolicy() {
		}

		public PasswordPolicy(Long id, String name) {
			this.id = id;
			this.name = name;
		}

		public Long getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		public List<PolicyAuditLog> getAuditLogs() {
			return auditLogs;
		}
	}

	@Entity( name = "PolicyAuditLog" )
	public static class PolicyAuditLog {
		@Id
		private Long id;

		@ManyToOne
		@JoinColumn( name = "policy_id" )
		private PasswordPolicy policy;

		private String event;

		public PolicyAuditLog() {
		}

		public PolicyAuditLog(Long id, PasswordPolicy policy, String event) {
			this.id = id;
			this.policy = policy;
			this.event = event;
		}

		public Long getId() {
			return id;
		}
	}
}
