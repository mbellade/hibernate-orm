package org.hibernate.temporal.audit.auditoverrides;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
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
		AuditCollectionOverrideTest.Owner.class,
		AuditCollectionOverrideTest.SubOwner.class,
		AuditCollectionOverrideTest.Child.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditCollectionOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditCollectionOverrideTest {
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
			var base = new Owner();
			base.id = 1L;
			base.name = "base";
			base.restoredTags.add( "base hidden" );
			base.renamedTags.add( "base visible" );
			base.droppedTags.add( "base dropped" );
			base.revokedTags.add( "base audited" );
			session.persist( base );
			var child = new Child();
			child.id = 1L;
			child.name = "child";
			session.persist( child );
			var sub = new SubOwner();
			sub.id = 2L;
			sub.name = "sub";
			sub.restoredTags.add( "restored" );
			sub.renamedTags.add( "renamed" );
			sub.droppedTags.add( "sub dropped" );
			sub.revokedTags.add( "sub hidden" );
			sub.children.add( child );
			sub.links.add( child );
			sub.mscTags.add( "mapped superclass" );
			sub.memberOnlyTags.add( "member table" );
			session.persist( sub );
		} );
		scope.inTransaction( session -> {
			var sub = session.find( SubOwner.class, 2L );
			// Include an entity change until collection-only subclass revisions resolve the concrete owner.
			sub.name = "updated";
			sub.restoredTags.add( "added" );
			sub.renamedTags.add( "added" );
		} );
	}

	@AfterClassTemplate
	void cleanup(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	void existenceIsUnionButNamesBelongToOwner(DomainModelScope scope) {
		assertTable( scope, "override_restored_tags_AUD" );
		assertTable( scope, "owner_renamed_tags" );
		assertTable( scope, "override_revoked_tags_AUD" );
		assertTable( scope, "override_links_AUD" );
		assertTable( scope, "OverrideCollectionOwner_OverrideCollectionChild_AUD" );
		assertTable( scope, "msc_renamed_tags" );
		assertTable( scope, "member_only_tags" );
		assertNoTable( scope, "member_msc_tags" );
		assertNoTable( scope, "subclass_msc_tags" );
		assertNoTable( scope, "member_renamed_tags" );
		assertNoTable( scope, "subclass_restored_tags" );
		assertNoTable( scope, "subclass_renamed_tags" );
		assertNoTable( scope, "subclass_children" );
		assertNoTable( scope, "subclass_links" );
		assertNoTable( scope, "override_dropped_tags_AUD" );
		assertNoTable( scope, "ignored_dropped_tags" );
	}

	@Test
	void auditReadsRespectOwnerType(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var base = auditLog.find( Owner.class, 1L, 1 );
			assertThat( base.restoredTags ).isNull();
			assertThat( base.renamedTags ).containsExactly( "base visible" );
			assertThat( base.droppedTags ).isNull();
			assertThat( base.revokedTags ).containsExactly( "base audited" );
			final var sub = auditLog.find( SubOwner.class, 2L, 1 );
			assertThat( sub.restoredTags ).containsExactly( "restored" );
			assertThat( sub.renamedTags ).containsExactly( "renamed" );
			assertThat( sub.droppedTags ).isNull();
			assertThat( sub.revokedTags ).isNull();
			assertThat( sub.children ).extracting( child -> child.name ).containsExactly( "child" );
			assertThat( sub.links ).extracting( child -> child.name ).containsExactly( "child" );
			assertThat( sub.mscTags ).containsExactly( "mapped superclass" );
			assertThat( sub.memberOnlyTags ).containsExactly( "member table" );
		}
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 2 ).openSession()) {
			final var sub = session.find( SubOwner.class, 2L );
			assertThat( sub.restoredTags ).containsExactlyInAnyOrder( "restored", "added" );
			assertThat( sub.renamedTags ).containsExactlyInAnyOrder( "renamed", "added" );
			assertThat( sub.droppedTags ).isNull();
		}
	}

	/**
	 * Known HHH-20641 limitation: entity-inherited collections still write middle
	 * rows for excluded owners. Owner-specific write filtering is a separate
	 * work package; these rows must remain hidden on audit reads.
	 */
	@Test
	void excludedSubclassCollectionRowsAreWrittenButHidden(SessionFactoryScope scope) {
		scope.inSession( session -> assertThat( session.createNativeQuery(
				"select count(*) from override_revoked_tags_AUD where owner_id = 2",
				Long.class
		).getSingleResult() ).isEqualTo( 1L ) );
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			assertThat( session.find( SubOwner.class, 2L ).revokedTags ).isNull();
		}
	}

	@Test
	void polymorphicCollectionsUseConcreteOwnerMask(SessionFactoryScope scope) {
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			final var owners = session.createSelectionQuery(
					"from OverrideCollectionOwner order by id",
					Owner.class
			).getResultList();
			assertThat( owners ).hasSize( 2 );
			assertThat( owners.get( 0 ).restoredTags ).isNull();
			assertThat( owners.get( 0 ).revokedTags ).containsExactly( "base audited" );
			assertThat( owners.get( 1 ) ).isInstanceOf( SubOwner.class );
			assertThat( owners.get( 1 ).restoredTags ).containsExactly( "restored" );
			assertThat( owners.get( 1 ).revokedTags ).isNull();
		}
	}

	@MappedSuperclass
	@Audited.Override(
			name = "mscTags",
			collectionTable = @Audited.CollectionTable(name = "msc_renamed_tags")
	)
	static class CollectionBase {
		@ElementCollection
		@CollectionTable(name = "override_msc_tags")
		@Audited.CollectionTable(name = "member_msc_tags")
		Set<String> mscTags = new HashSet<>();
	}

	@Entity(name = "OverrideCollectionOwner")
	@Table(name = "override_collection_owner")
	@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
	@Audited
	@Audited.Override(
			name = "renamedTags",
			collectionTable = @Audited.CollectionTable(name = "owner_renamed_tags")
	)
	@Audited.Override(
			name = "droppedTags",
			isAudited = false,
			collectionTable = @Audited.CollectionTable(name = "ignored_dropped_tags")
	)
	@Audited.Override(name = "memberOnlyTags")
	static class Owner extends CollectionBase {
		@Id
		long id;
		String name;

		@ElementCollection
		@CollectionTable(name = "override_member_only_tags")
		@Audited.CollectionTable(name = "member_only_tags")
		Set<String> memberOnlyTags = new HashSet<>();

		@ElementCollection
		@CollectionTable(name = "override_revoked_tags", joinColumns = @JoinColumn(name = "owner_id"))
		Set<String> revokedTags = new HashSet<>();

		@ElementCollection
		@CollectionTable(name = "override_restored_tags")
		@Audited.Excluded
		Set<String> restoredTags = new HashSet<>();

		@ElementCollection
		@CollectionTable(name = "override_renamed_tags")
		@Audited.CollectionTable(name = "member_renamed_tags")
		Set<String> renamedTags = new HashSet<>();

		@ElementCollection
		@CollectionTable(name = "override_dropped_tags")
		Set<String> droppedTags = new HashSet<>();

		@OneToMany
		@JoinColumn(name = "owner_id")
		@Audited.Excluded
		List<Child> children = new ArrayList<>();

		@ManyToMany
		@JoinTable(name = "override_links")
		@Audited.Excluded
		Set<Child> links = new HashSet<>();
	}

	@Audited.Override(
			name = "mscTags",
			collectionTable = @Audited.CollectionTable(name = "subclass_msc_tags")
	)
	@Entity(name = "OverrideCollectionSubOwner")
	@Audited.Override(
			name = "restoredTags",
			collectionTable = @Audited.CollectionTable(name = "subclass_restored_tags")
	)
	@Audited.Override(
			name = "renamedTags",
			collectionTable = @Audited.CollectionTable(name = "subclass_renamed_tags")
	)
	@Audited.Override(
			name = "children",
			collectionTable = @Audited.CollectionTable(name = "subclass_children")
	)
	@Audited.Override(
			name = "links",
			collectionTable = @Audited.CollectionTable(name = "subclass_links")
	)
	@Audited.Override(name = "revokedTags", isAudited = false)
	static class SubOwner extends Owner {
	}

	@Entity(name = "OverrideCollectionChild")
	@Table(name = "override_collection_child")
	@Audited
	static class Child {
		@Id
		long id;
		String name;
	}
}
