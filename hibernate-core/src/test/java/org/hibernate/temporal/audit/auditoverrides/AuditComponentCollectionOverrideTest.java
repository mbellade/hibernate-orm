package org.hibernate.temporal.audit.auditoverrides;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
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
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNoTable;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertTable;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditComponentCollectionOverrideTest.ExcludedOwner.class,
		AuditComponentCollectionOverrideTest.ComponentOwner.class,
		AuditComponentCollectionOverrideTest.RestoringOwner.class,
		AuditComponentCollectionOverrideTest.OverrideOwner.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditComponentCollectionOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditComponentCollectionOverrideTest {
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
			final var base = new ComponentOwner();
			base.id = 1L;
			base.address = new Address();
			base.address.lines.add( "excluded" );
			session.persist( base );
			final var restoring = new RestoringOwner();
			restoring.id = 2L;
			restoring.address = new Address();
			restoring.address.lines.addAll( Set.of( "first", "second" ) );
			session.persist( restoring );
			final var baseReference = new ExcludedOwner();
			baseReference.id = 1L;
			baseReference.owner = base;
			session.persist( baseReference );
			final var restoringReference = new ExcludedOwner();
			restoringReference.id = 2L;
			restoringReference.owner = restoring;
			session.persist( restoringReference );

			final var overridden = new OverrideOwner();
			overridden.id = 2L;
			overridden.lines.add( "top-level line" );
			overridden.hiddenLines.add( "top-level hidden" );
			overridden.address.lines.add( "embedded line" );
			overridden.address.hiddenLines.add( "embedded visible" );
			session.persist( overridden );
		} );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void middleTablesFollowEffectiveExclusions(DomainModelScope scope) {
		assertNoMiddleTable( scope, ExcludedOwner.class, "address.lines" );
		assertNoMiddleTable( scope, ExcludedOwner.class, "address.details.notes" );

		assertTable( scope, middleTableName( scope, ComponentOwner.class, "address.lines" ) );
		assertTable( scope, middleTableName( scope, ComponentOwner.class, "address.details.notes" ) );
		assertNoMiddleTable( scope, ComponentOwner.class, "address.excludedLines" );
		assertNoMiddleTable( scope, ComponentOwner.class, "address.details.excludedNotes" );

		assertTable( scope, "embedded_member_lines_audit" );
		assertNoTable( scope, "embedded_override_lines_AUD" );
		assertTable( scope, "embedded_override_hidden_AUD" );
		assertTable( scope, "top_level_lines_audit" );
		assertNoTable( scope, "top_level_hidden_AUD" );
	}

	@Test
	void componentCollectionWritesFollowOwnerMask(DomainModelScope modelScope, SessionFactoryScope scope) {
		final var collection = modelScope.getDomainModel()
				.getCollectionBinding( ComponentOwner.class.getName() + ".address.lines" );
		final var table = collection.getCollectionTable().getName() + "_AUD";
		final var key = collection.getKey().getColumns().get( 0 ).getName();
		scope.inSession( session -> {
			assertThat( session.createNativeQuery(
					"select count(*) from " + table + " where " + key + " = 1",
					Long.class
			).getSingleResult() ).isZero();
			assertThat( session.createNativeQuery(
					"select count(*) from " + table + " where " + key + " = 2",
					Long.class
			).getSingleResult() ).isEqualTo( 2L );
		} );
	}

	@Test
	void polymorphicReadMasksExcludedComponent(SessionFactoryScope scope) {
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var owners = session.createSelectionQuery(
					"from ComponentOwner order by id",
					ComponentOwner.class
			).getResultList();
			assertThat( owners ).hasSize( 2 );
			assertComponentValues( owners.get( 0 ), owners.get( 1 ) );
		}
	}

	@Test
	void joinedFetchMasksExcludedComponent(SessionFactoryScope scope) {
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var references = session.createSelectionQuery(
					"from ExcludedComponentOwner e join fetch e.owner o left join fetch o.address.lines order by e.id",
					ExcludedOwner.class
			).getResultList();
			assertThat( references ).hasSize( 2 );
			assertComponentValues( references.get( 0 ).owner, references.get( 1 ).owner );
		}
	}

	private static void assertComponentValues(ComponentOwner base, ComponentOwner restoring) {
		assertThat( base.address ).isNull();
		assertThat( restoring ).isInstanceOf( RestoringOwner.class );
		assertThat( restoring.address ).isNotNull();
		assertThat( restoring.address.lines ).containsExactlyInAnyOrder( "first", "second" );
	}

	@Test
	void topLevelOverridesDoNotAffectEmbeddedCollections(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var overridden = auditLog.find( OverrideOwner.class, 2L, 1 );
			assertThat( overridden.lines ).containsExactly( "top-level line" );
			assertThat( overridden.hiddenLines ).isNull();
			assertThat( overridden.address.lines ).containsExactly( "embedded line" );
			assertThat( overridden.address.hiddenLines ).containsExactly( "embedded visible" );
		}
	}

	private static void assertNoMiddleTable(DomainModelScope scope, Class<?> owner, String path) {
		assertNoTable( scope, middleTableName( scope, owner, path ) );
	}

	private static String middleTableName(DomainModelScope scope, Class<?> owner, String path) {
		return scope.getDomainModel().getCollectionBinding( owner.getName() + "." + path )
				.getCollectionTable().getName() + "_AUD";
	}

	@Entity(name = "ExcludedComponentOwner")
	@Table(name = "excluded_component_owner")
	@Audited
	static class ExcludedOwner {
		@Id
		long id;
		@Embedded
		@Audited.Excluded
		Address address;
		@ManyToOne
		ComponentOwner owner;
	}

	@Entity(name = "ComponentOwner")
	@Table(name = "component_owner")
	@Audited
	static class ComponentOwner {
		@Id
		long id;
		@Embedded
		@Audited.Excluded
		Address address;
	}

	@Entity(name = "RestoringComponentOwner")
	@Audited.Override(name = "address")
	static class RestoringOwner extends ComponentOwner {
	}

	@Embeddable
	static class Address {
		@ElementCollection
		Set<String> lines = new HashSet<>();
		@ElementCollection
		@Audited.Excluded
		Set<String> excludedLines = new HashSet<>();
		@Embedded
		Details details;
	}

	@Embeddable
	static class Details {
		@ElementCollection
		Set<String> notes = new HashSet<>();
		@ElementCollection
		@Audited.Excluded
		Set<String> excludedNotes = new HashSet<>();
	}

	@MappedSuperclass
	@Audited.Override(name = "lines", collectionTable = @Audited.CollectionTable(name = "top_level_lines_audit"))
	@Audited.Override(name = "hiddenLines", isAudited = false)
	static class OverrideBase {
		@Id
		long id;

		@ElementCollection
		@CollectionTable(name = "top_level_lines")
		Set<String> lines = new HashSet<>();

		@ElementCollection
		@CollectionTable(name = "top_level_hidden")
		Set<String> hiddenLines = new HashSet<>();
	}

	@Entity(name = "EmbeddedOverrideOwner")
	@Table(name = "embedded_override_owner")
	@Audited
	static class OverrideOwner extends OverrideBase {
		@Embedded
		OverrideAddress address = new OverrideAddress();
	}

	@Embeddable
	static class OverrideAddress {
		@ElementCollection
		@CollectionTable(name = "embedded_override_lines")
		@Audited.CollectionTable(name = "embedded_member_lines_audit")
		Set<String> lines = new HashSet<>();

		@ElementCollection
		@CollectionTable(name = "embedded_override_hidden")
		Set<String> hiddenLines = new HashSet<>();
	}
}
