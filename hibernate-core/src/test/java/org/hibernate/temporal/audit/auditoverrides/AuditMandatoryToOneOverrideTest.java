/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hibernate.temporal.audit.auditoverrides;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.AuditLogFactory;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.temporal.spi.ChangesetIdentifierSupplier;
import org.hibernate.testing.orm.junit.AfterClassTemplate;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.BeforeClassTemplate;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.DomainModelScope;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertTable;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditMandatoryToOneOverrideTest.Base.class,
		AuditMandatoryToOneOverrideTest.Sub.class,
		AuditMandatoryToOneOverrideTest.Target.class
})
@SessionFactory(useCollectingStatementObserver = true)
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditMandatoryToOneOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
class AuditMandatoryToOneOverrideTest {
	private static int currentTxId;

	public static class TxIdSupplier implements ChangesetIdentifierSupplier<Integer> {
		@Override
		public Integer generateIdentifier(SharedSessionContract session) {
			return ++currentTxId;
		}
	}

	@BeforeClassTemplate
	void createData(SessionFactoryScope scope) {
		currentTxId = 0;
		scope.inTransaction( session -> {
			final var target = new Target();
			target.id = 1L;
			target.name = "target";
			session.persist( target );
			final var base = new Base();
			base.id = 1L;
			base.target = target;
			base.uniformTarget = target;
			base.requiredText = "base";
			session.persist( base );
			final var sub = new Sub();
			sub.id = 2L;
			sub.target = target;
			sub.uniformTarget = target;
			sub.requiredText = "sub";
			session.persist( sub );
		} );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void polymorphicPointLoadsPreserveExcludedRows(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var sub = auditLog.find( Base.class, 2L, 1 );
			assertThat( sub ).isInstanceOf( Sub.class );
			assertThat( sub.target ).isNull();
			assertThat( sub.requiredText ).isNull();
			assertThat( sub.uniformTarget.name ).isEqualTo( "target" );
			assertThat( auditLog.find( Base.class, 1L, 1 ).target.name ).isEqualTo( "target" );
		}
		final var observer = scope.getCollectingStatementObserver();
		observer.clear();
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var sub = session.find( Base.class, 2L );
			assertThat( sub ).isInstanceOf( Sub.class );
			assertThat( sub.target ).isNull();
			assertThat( sub.requiredText ).isNull();
			assertThat( sub.uniformTarget.name ).isEqualTo( "target" );
			// Only the uniformly audited mandatory association retains its implicit inner join.
			observer.assertQuery( 0 ).containsToken( " join ", 1 ).doesNotContain( " left join " );
			assertThat( session.find( Base.class, 1L ).target.name ).isEqualTo( "target" );
		}
	}

	@Test
	void currentStateRemainsMandatory(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final var sub = session.find( Base.class, 2L );
			assertThat( sub ).isInstanceOf( Sub.class );
			assertThat( sub.target.name ).isEqualTo( "target" );
			assertThat( sub.uniformTarget.name ).isEqualTo( "target" );
			assertThat( sub.requiredText ).isEqualTo( "sub" );
		} );
	}

	@Test
	void sharedAuditColumnsAreNullable(DomainModelScope modelScope, SessionFactoryScope scope) {
		final var current = assertTable( modelScope, "mandatory_override", "target_id", "requiredText" );
		final var audit = assertTable( modelScope, "mandatory_override_AUD", "target_id", "requiredText" );
		for ( var name : new String[] { "target_id", "requiredText" } ) {
			final var column = new org.hibernate.mapping.Column( name );
			assertThat( current.getColumn( column ).isNullable() ).isFalse();
			assertThat( audit.getColumn( column ).isNullable() ).isTrue();
		}
		scope.inSession( session -> {
			assertThat( session.createNativeQuery(
					"select count(*) from mandatory_override_AUD where id = 2 and REV = 1"
							+ " and target_id is null and requiredText is null",
					Long.class
			).getSingleResult() ).isEqualTo( 1L );
			assertThat( session.createNativeQuery(
					"select requiredText from mandatory_override_AUD where id = 1 and REV = 1",
					String.class
			).getSingleResult() ).isEqualTo( "base" );
		} );
	}

	@Entity(name = "MandatoryOverrideBase")
	@Table(name = "mandatory_override")
	@Audited
	static class Base {
		@Id
		long id;
		@ManyToOne(optional = false)
		@JoinColumn(name = "target_id", nullable = false)
		Target target;
		@ManyToOne(optional = false)
		@JoinColumn(name = "uniform_target_id", nullable = false)
		Target uniformTarget;
		@Column(nullable = false)
		String requiredText;
	}

	@Entity(name = "MandatoryOverrideSub")
	@Audited.Override(name = "target", isAudited = false)
	@Audited.Override(name = "requiredText", isAudited = false)
	static class Sub extends Base {
	}

	@Entity(name = "MandatoryOverrideTarget")
	@Table(name = "mandatory_override_target")
	@Audited
	static class Target {
		@Id
		long id;
		String name;
	}
}
