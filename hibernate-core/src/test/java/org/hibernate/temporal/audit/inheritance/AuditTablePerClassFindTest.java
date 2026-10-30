package org.hibernate.temporal.audit.inheritance;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.AuditLogFactory;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.temporal.spi.ChangesetIdentifierSupplier;
import org.hibernate.testing.orm.junit.AfterClassTemplate;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.BeforeClassTemplate;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditTablePerClassFindTest.Base.class,
		AuditTablePerClassFindTest.Sub.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.inheritance.AuditTablePerClassFindTest$TxIdSupplier"
))
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditTablePerClassFindTest {
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
			final var sub = new Sub();
			sub.id = 1L;
			sub.name = "original";
			sub.detail = "detail";
			session.persist( sub );
		} );
		scope.inTransaction( session -> session.find( Sub.class, 1L ).name = "updated" );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void findSubclassAtChangeset(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var original = auditLog.find( Sub.class, 1L, 1 );
			assertThat( original ).isNotNull();
			assertThat( original.name ).isEqualTo( "original" );
			assertThat( original.detail ).isEqualTo( "detail" );
			assertThat( auditLog.find( Base.class, 1L, 1 ) ).isInstanceOf( Sub.class );
			assertThat( auditLog.find( Sub.class, 1L, 2 ).name ).isEqualTo( "updated" );
		}
	}

	@Entity(name = "FindUnionBase")
	@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
	@Audited
	static class Base {
		@Id
		Long id;
		String name;
	}

	@Entity(name = "FindUnionSub")
	static class Sub extends Base {
		String detail;
	}
}
