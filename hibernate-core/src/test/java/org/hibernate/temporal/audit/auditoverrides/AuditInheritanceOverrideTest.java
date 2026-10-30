package org.hibernate.temporal.audit.auditoverrides;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.MappedSuperclass;
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
		AuditInheritanceOverrideTest.SingleBase.class,
		AuditInheritanceOverrideTest.SingleSub.class,
		AuditInheritanceOverrideTest.JoinedBase.class,
		AuditInheritanceOverrideTest.JoinedSub.class,
		AuditInheritanceOverrideTest.UnionBase.class,
		AuditInheritanceOverrideTest.UnionSub.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditInheritanceOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditInheritanceOverrideTest {
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
			session.persist( initialize( new SingleBase(), 1L ) );
			session.persist( initialize( new SingleSub(), 2L ) );
			session.persist( initialize( new JoinedBase(), 1L ) );
			session.persist( initialize( new JoinedSub(), 2L ) );
			session.persist( initialize( new UnionBase(), 1L ) );
			session.persist( initialize( new UnionSub(), 2L ) );
		} );
		scope.inTransaction( session -> {
			session.find( SingleSub.class, 2L ).restored = "updated";
			session.find( JoinedSub.class, 2L ).restored = "updated";
			session.find( UnionSub.class, 2L ).restored = "updated";
		} );
	}

	private static <T extends Fields> T initialize(T entity, long id) {
		entity.id = id;
		entity.restored = "restored";
		entity.hidden = "hidden";
		entity.declaredExcluded = "declared";
		return entity;
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void sharedTablesUseUnionAndNullableColumns(DomainModelScope scope) {
		for ( var name : new String[] { "override_single_AUD", "override_joined_AUD" } ) {
			final var table = assertTable( scope, name, "restored", "hidden", "declaredExcluded" );
			assertThat( table.getColumn( new org.hibernate.mapping.Column( "restored" ) ).isNullable() ).isTrue();
		}
		assertTable( scope, "override_joined_sub_AUD", "detail" );
		assertNoColumn( assertTable( scope, "override_union_AUD", "hidden" ), "restored" );
		assertNoColumn( assertTable( scope, "override_union_AUD" ), "declaredExcluded" );
		assertNoColumn( assertTable( scope, "override_union_sub_AUD", "restored", "declaredExcluded" ), "hidden" );
	}

	@Test
	void singleTableReadsUseLoadedPersister(SessionFactoryScope scope) {
		assertAuditReads( scope, SingleBase.class, SingleSub.class, "OverrideSingleSub" );
		assertNullableBaseRow( scope, "override_single_AUD" );
	}

	@Test
	void joinedReadsUseLoadedPersister(SessionFactoryScope scope) {
		assertAuditReads( scope, JoinedBase.class, JoinedSub.class, "OverrideJoinedSub" );
		assertNullableBaseRow( scope, "override_joined_AUD" );
	}

	@Test
	void tablePerClassResolvesEachTableIndependently(SessionFactoryScope scope) {
		assertAuditReads( scope, UnionBase.class, UnionSub.class, "OverrideUnionSub" );
	}

	private static <T extends Fields> void assertAuditReads(
			SessionFactoryScope scope,
			Class<T> baseType,
			Class<? extends T> subType,
			String subEntityName
	) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var base = auditLog.find( baseType, 1L, 1 );
			assertThat( base.restored ).isNull();
			assertThat( base.declaredExcluded ).isNull();
			assertThat( base.hidden ).isEqualTo( "hidden" );
			final var sub = auditLog.find( subType, 2L, 1 );
			assertThat( sub.restored ).isEqualTo( "restored" );
			assertThat( sub.declaredExcluded ).isEqualTo( "declared" );
			assertThat( sub.hidden ).isNull();
			assertThat( auditLog.find( subType, 2L, 2 ).restored ).isEqualTo( "updated" );
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var sub = session.createSelectionQuery( "from " + subEntityName, subType ).getSingleResult();
			assertThat( sub.restored ).isEqualTo( "restored" );
			assertThat( sub.declaredExcluded ).isEqualTo( "declared" );
			assertThat( sub.hidden ).isNull();
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var results = session.createSelectionQuery(
					"from " + baseType.getAnnotation( Entity.class ).name() + " order by id",
					baseType
			).getResultList();
			assertThat( results ).hasSize( 2 );
			assertThat( results.get( 0 ).restored ).isNull();
			assertThat( results.get( 0 ).declaredExcluded ).isNull();
			assertThat( results.get( 0 ).hidden ).isEqualTo( "hidden" );
			assertThat( results.get( 1 ) ).isInstanceOf( subType );
			assertThat( results.get( 1 ).restored ).isEqualTo( "restored" );
			assertThat( results.get( 1 ).declaredExcluded ).isEqualTo( "declared" );
			assertThat( results.get( 1 ).hidden ).isNull();
			final var baseRows = session.createSelectionQuery(
					"from " + baseType.getAnnotation( Entity.class ).name()
							+ " e where type(e) = " + baseType.getAnnotation( Entity.class ).name(),
					baseType
			).getResultList();
			assertThat( baseRows ).hasSize( 1 );
			assertThat( baseRows.get( 0 ).restored ).isNull();
			assertThat( baseRows.get( 0 ).declaredExcluded ).isNull();
			assertThat( baseRows.get( 0 ).hidden ).isEqualTo( "hidden" );
			final var subRows = session.createSelectionQuery(
					"from " + baseType.getAnnotation( Entity.class ).name()
							+ " e where type(e) = " + subEntityName,
					baseType
			).getResultList();
			assertThat( subRows ).hasSize( 1 );
			assertThat( subRows.get( 0 ).restored ).isEqualTo( "restored" );
			assertThat( subRows.get( 0 ).declaredExcluded ).isEqualTo( "declared" );
			assertThat( subRows.get( 0 ).hidden ).isNull();
		}
	}

	private static void assertNullableBaseRow(SessionFactoryScope scope, String tableName) {
		scope.inSession( session -> {
			final var value = session.createNativeQuery(
					"select restored from " + tableName + " where id = 1 and REV = 1",
					String.class
			).getSingleResult();
			assertThat( value ).isNull();
		} );
	}

	@MappedSuperclass
	static class Fields {
		@Id
		long id;
		@Column(nullable = false)
		String restored;
		String hidden;
		@Audited.Excluded
		String declaredExcluded;
	}

	@Entity(name = "OverrideSingleBase")
	@Table(name = "override_single")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class SingleBase extends Fields {
	}

	@Entity(name = "OverrideSingleSub")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "declaredExcluded")
	@Audited.Override(name = "hidden", isAudited = false)
	static class SingleSub extends SingleBase {
		String detail = "detail";
	}

	@Entity(name = "OverrideJoinedBase")
	@Table(name = "override_joined")
	@Inheritance(strategy = InheritanceType.JOINED)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class JoinedBase extends Fields {
	}

	@Entity(name = "OverrideJoinedSub")
	@Table(name = "override_joined_sub")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "declaredExcluded")
	@Audited.Override(name = "hidden", isAudited = false)
	static class JoinedSub extends JoinedBase {
		String detail = "detail";
	}

	@Entity(name = "OverrideUnionBase")
	@Table(name = "override_union")
	@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
	@Audited
	@Audited.Override(name = "restored", isAudited = false)
	static class UnionBase extends Fields {
	}

	@Entity(name = "OverrideUnionSub")
	@Table(name = "override_union_sub")
	@Audited.Override(name = "restored")
	@Audited.Override(name = "declaredExcluded")
	@Audited.Override(name = "hidden", isAudited = false)
	static class UnionSub extends UnionBase {
		String detail = "detail";
	}
}
