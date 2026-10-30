package org.hibernate.temporal.audit.auditoverrides;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
		AuditMappedSuperclassOverrideTest.DefaultEntity.class,
		AuditMappedSuperclassOverrideTest.IntermediateEntity.class,
		AuditMappedSuperclassOverrideTest.NearestEntity.class,
		AuditMappedSuperclassOverrideTest.EntityOverride.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditMappedSuperclassOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditMappedSuperclassOverrideTest {
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
			var defaults = new DefaultEntity();
			defaults.id = 1L;
			defaults.secret = "default secret";
			defaults.visible = "default visible";
			session.persist( defaults );
			var intermediate = new IntermediateEntity();
			intermediate.id = 2L;
			intermediate.secret = "intermediate secret";
			intermediate.visible = "intermediate visible";
			session.persist( intermediate );
			var nearest = new NearestEntity();
			nearest.id = 3L;
			nearest.secret = "nearest secret";
			nearest.visible = "nearest visible";
			session.persist( nearest );
			var entityOverride = new EntityOverride();
			entityOverride.id = 4L;
			entityOverride.secret = "entity override";
			entityOverride.visible = "excluded";
			session.persist( entityOverride );
		} );
		scope.inTransaction( session -> {
			session.find( IntermediateEntity.class, 2L ).secret = "updated secret";
			session.find( NearestEntity.class, 3L ).visible = "updated visible";
		} );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void schemaAndOptimisticLocking(DomainModelScope scope) {
		assertNoColumn( assertTable( scope, "msc_default_AUD", "visible" ), "secret" );
		assertNoColumn( assertTable( scope, "msc_intermediate_AUD", "secret" ), "visible" );
		assertNoColumn( assertTable( scope, "msc_nearest_AUD", "visible" ), "secret" );
		assertNoColumn( assertTable( scope, "msc_entity_override_AUD", "secret" ), "visible" );
		final var property = scope.getDomainModel()
				.getEntityBinding( IntermediateEntity.class.getName() ).getProperty( "secret" );
		assertThat( property.isAuditedExcluded() ).isTrue();
		assertThat( property.isOptimisticLocked() ).isFalse();
	}

	@Test
	void nearestOverridesControlAuditReads(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var defaults = auditLog.find( DefaultEntity.class, 1L, 1 );
			assertThat( defaults.secret ).isNull();
			assertThat( defaults.visible ).isEqualTo( "default visible" );
			final var intermediate = auditLog.find( IntermediateEntity.class, 2L, 1 );
			assertThat( intermediate.secret ).isEqualTo( "intermediate secret" );
			assertThat( intermediate.visible ).isNull();
			assertThat( auditLog.find( IntermediateEntity.class, 2L, 2 ).secret )
					.isEqualTo( "updated secret" );
			final var nearest = auditLog.find( NearestEntity.class, 3L, 1 );
			assertThat( nearest.secret ).isNull();
			assertThat( nearest.visible ).isEqualTo( "nearest visible" );
			final var entityOverride = auditLog.find( EntityOverride.class, 4L, 1 );
			assertThat( entityOverride.secret ).isEqualTo( "entity override" );
			assertThat( entityOverride.visible ).isNull();
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 2 ).openSession()) {
			final var entity = session.createSelectionQuery( "from NearestEntity", NearestEntity.class )
					.getSingleResult();
			assertThat( entity.secret ).isNull();
			assertThat( entity.visible ).isEqualTo( "updated visible" );
		}
	}

	@MappedSuperclass
	static class Base {
		@Id
		long id;
		@Audited.Excluded
		String secret;
		String visible;
	}

	@MappedSuperclass
	@Audited.Override(name = "secret")
	@Audited.Override(name = "visible", isAudited = false)
	static class Intermediate extends Base {
	}

	@Entity(name = "DefaultEntity")
	@Table(name = "msc_default")
	@Audited
	static class DefaultEntity extends Base {
	}

	@Entity(name = "IntermediateEntity")
	@Table(name = "msc_intermediate")
	@Audited
	static class IntermediateEntity extends Intermediate {
	}

	@Entity(name = "NearestEntity")
	@Table(name = "msc_nearest")
	@Audited
	@Audited.Override(name = "secret", isAudited = false)
	@Audited.Override(name = "visible")
	static class NearestEntity extends Intermediate {
	}

	@Entity(name = "MscEntityOverride")
	@Table(name = "msc_entity_override")
	@Audited
	@Audited.Override(name = "secret")
	@Audited.Override(name = "visible", isAudited = false)
	static class EntityOverride extends Base {
	}
}
