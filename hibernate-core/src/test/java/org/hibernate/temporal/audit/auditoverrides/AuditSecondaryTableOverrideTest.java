package org.hibernate.temporal.audit.auditoverrides;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.SecondaryTable;
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
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNoColumn;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertTable;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditSecondaryTableOverrideTest.SingleBase.class,
		AuditSecondaryTableOverrideTest.SingleSub.class,
		AuditSecondaryTableOverrideTest.JoinedBase.class,
		AuditSecondaryTableOverrideTest.JoinedSub.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditSecondaryTableOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditSecondaryTableOverrideTest {
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
			var singleBase = new SingleBase();
			singleBase.id = 1L;
			singleBase.restored = "base restored";
			singleBase.hidden = "base hidden";
			session.persist( singleBase );
			var singleSub = new SingleSub();
			singleSub.id = 2L;
			singleSub.restored = "sub restored";
			singleSub.hidden = "sub hidden";
			singleSub.ownRestored = "own restored";
			singleSub.ownHidden = "own hidden";
			session.persist( singleSub );
			var joinedBase = new JoinedBase();
			joinedBase.id = 1L;
			joinedBase.restored = "base restored";
			joinedBase.hidden = "base hidden";
			session.persist( joinedBase );
			var joinedSub = new JoinedSub();
			joinedSub.id = 2L;
			joinedSub.restored = "sub restored";
			joinedSub.hidden = "sub hidden";
			joinedSub.ownRestored = "own restored";
			joinedSub.ownHidden = "own hidden";
			session.persist( joinedSub );
		} );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void secondaryTableColumnsUseEffectiveExclusions(DomainModelScope scope) {
		for ( var name : new String[] { "secondary_single_details_AUD", "secondary_joined_details_AUD" } ) {
			final var table = assertTable( scope, name, "restored", "hidden" );
			assertThat( table.getColumn( new org.hibernate.mapping.Column( "restored" ) ).isNullable() ).isTrue();
		}
		assertNoColumn( assertTable( scope, "secondary_single_own_AUD", "ownRestored" ), "ownHidden" );
		assertNoColumn( assertTable( scope, "secondary_joined_own_AUD", "ownRestored" ), "ownHidden" );
	}

	@Test
	void singleTableSecondaryAuditReads(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var base = auditLog.find( SingleBase.class, 1L, 1 );
			assertThat( base.restored ).isNull();
			assertThat( base.hidden ).isEqualTo( "base hidden" );
			final var sub = auditLog.find( SingleSub.class, 2L, 1 );
			assertThat( sub.restored ).isEqualTo( "sub restored" );
			assertThat( sub.hidden ).isNull();
			assertThat( sub.ownRestored ).isEqualTo( "own restored" );
			assertThat( sub.ownHidden ).isNull();
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var sub = session.createSelectionQuery( "from SecondarySingleSub", SingleSub.class ).getSingleResult();
			assertThat( sub.restored ).isEqualTo( "sub restored" );
			assertThat( sub.ownRestored ).isEqualTo( "own restored" );
			assertThat( sub.hidden ).isNull();
		}
	}

	@Test
	void joinedSecondaryAuditReads(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var base = auditLog.find( JoinedBase.class, 1L, 1 );
			assertThat( base.restored ).isNull();
			assertThat( base.hidden ).isEqualTo( "base hidden" );
			final var sub = auditLog.find( JoinedSub.class, 2L, 1 );
			assertThat( sub.restored ).isEqualTo( "sub restored" );
			assertThat( sub.hidden ).isNull();
			assertThat( sub.ownRestored ).isEqualTo( "own restored" );
			assertThat( sub.ownHidden ).isNull();
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var sub = session.createSelectionQuery( "from SecondaryJoinedSub", JoinedSub.class ).getSingleResult();
			assertThat( sub.restored ).isEqualTo( "sub restored" );
			assertThat( sub.ownRestored ).isEqualTo( "own restored" );
			assertThat( sub.ownHidden ).isNull();
		}
	}

	@Entity(name = "SecondarySingleBase")
	@Table(name = "secondary_single")
	@SecondaryTable(name = "secondary_single_details")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class SingleBase {
		@Id
		long id;
		@Column(table = "secondary_single_details", nullable = false)
		String restored;
		@Column(table = "secondary_single_details")
		String hidden;
	}

	@Entity(name = "SecondarySingleSub")
	@SecondaryTable(name = "secondary_single_own")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "hidden", isAudited = false)
	@Audited.Override(name = "ownRestored")
	@Audited.Override(name = "ownHidden", isAudited = false)
	static class SingleSub extends SingleBase {
		@Column(table = "secondary_single_own")
		@Audited.Excluded
		String ownRestored;
		@Column(table = "secondary_single_own")
		String ownHidden;
	}

	@Entity(name = "SecondaryJoinedBase")
	@Table(name = "secondary_joined")
	@SecondaryTable(name = "secondary_joined_details")
	@Inheritance(strategy = InheritanceType.JOINED)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class JoinedBase {
		@Id
		long id;
		@Column(table = "secondary_joined_details", nullable = false)
		String restored;
		@Column(table = "secondary_joined_details")
		String hidden;
	}

	@Entity(name = "SecondaryJoinedSub")
	@Table(name = "secondary_joined_sub")
	@SecondaryTable(name = "secondary_joined_own")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "hidden", isAudited = false)
	@Audited.Override(name = "ownRestored")
	@Audited.Override(name = "ownHidden", isAudited = false)
	static class JoinedSub extends JoinedBase {
		@Column(table = "secondary_joined_own")
		@Audited.Excluded
		String ownRestored;
		@Column(table = "secondary_joined_own")
		String ownHidden;
	}
}
