package org.hibernate.temporal.audit.auditoverrides;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
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

import org.hibernate.Hibernate;
import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.audit.AuditLogFactory;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.persister.collection.mutation.CollectionAuditSupport;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.temporal.spi.ChangesetIdentifierSupplier;
import org.hibernate.sql.results.internal.ResultsHelper;
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
			base.reference = 101L;
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
			sub.reference = 102L;
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
			sub.restoredTags.add( "added" );
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
			assertThat( sub.renamedTags ).containsExactly( "renamed" );
			assertThat( sub.droppedTags ).isNull();
		}
	}

	@Test
	void restoredCollectionOnlyChangeCreatesOwnerRevision(SessionFactoryScope scope) {
		scope.inSession( session -> {
			assertThat( session.createNativeQuery(
					"select count(*) from override_collection_owner_AUD where id = 2",
					Long.class
			).getSingleResult() ).isEqualTo( 2L );
			assertThat( session.createNativeQuery(
					"select count(*) from override_restored_tags_AUD",
					Long.class
			).getSingleResult() ).isEqualTo( 2L );
		} );
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 2 ).openSession()) {
			final var sub = session.find( SubOwner.class, 2L );
			assertThat( sub.name ).isEqualTo( "sub" );
			assertThat( sub.restoredTags ).containsExactlyInAnyOrder( "restored", "added" );
		}
	}

	@Test
	void excludedSubclassCollectionRowsAreNotWritten(SessionFactoryScope scope) {
		// An excluded-collection-only update still creates an owner revision via the legacy path's
		// hasDirtyCollection rule. Use a separate owner to isolate the owner-resolution assertions;
		// revisiting this pre-existing revision policy is outside package 2.
		scope.inTransaction( session -> {
			var sub = new SubOwner();
			sub.id = 3L;
			sub.reference = 103L;
			sub.name = "excluded collection change";
			sub.revokedTags.add( "hidden" );
			session.persist( sub );
		} );
		scope.inTransaction( session -> session.find( SubOwner.class, 3L ).revokedTags.add( "still hidden" ) );
		scope.inSession( session -> assertThat( session.createNativeQuery(
				"select count(*) from override_revoked_tags_AUD where owner_id in (2, 3)",
				Long.class
		).getSingleResult() ).isZero() );
		try (var session = scope.getSessionFactory().withOptions().atChangeset( 1 ).openSession()) {
			assertThat( session.find( SubOwner.class, 2L ).revokedTags ).isNull();
		}
	}

	@Test
	void propertyRefFallbackUsesConcreteOwnerMask(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final var base = new Owner();
			base.id = 4L;
			base.reference = 104L;
			base.propertyRefTags.add( "audited" );
			final var sub = new SubOwner();
			sub.id = 5L;
			sub.reference = 105L;
			sub.propertyRefTags.add( "excluded" );
			session.persist( base );
			session.persist( sub );
		} );
		scope.inTransaction( session -> {
			// Wrong-non-null-owner collisions await a separate Jira for the getCollectionOwner ordering fix.
			final var base = session.find( Owner.class, 4L );
			final var sub = session.find( SubOwner.class, 5L );
			final var descriptor = session.getFactory().getMappingMetamodel()
					.getCollectionDescriptor( Owner.class.getName() + ".propertyRefTags" );
			assertThat( session.getPersistenceContextInternal().getCollectionOwner( base.reference, descriptor ) ).isNull();
			assertThat( session.getPersistenceContextInternal().getCollectionOwner( sub.reference, descriptor ) ).isNull();
			base.propertyRefTags.add( "also audited" );
			sub.propertyRefTags.add( "also excluded" );
		} );
		scope.inSession( session -> {
			assertThat( session.createNativeQuery(
					"select count(*) from override_property_ref_tags_AUD where owner_ref = 104",
					Long.class
			).getSingleResult() ).isEqualTo( 2L );
			assertThat( session.createNativeQuery(
					"select count(*) from override_property_ref_tags_AUD where owner_ref = 105",
					Long.class
			).getSingleResult() ).isZero();
			assertThat( session.createNativeQuery(
					"select count(*) from override_collection_owner_AUD where id = 4",
					Long.class
			).getSingleResult() ).isEqualTo( 2L );
		} );
	}

	@Test
	void propertyRefFallbackExtractsProxyIdentifierWithoutInitializing(SessionFactoryScope scope) {
		final Owner detachedProxy = scope.fromSession( session -> session.getReference( Owner.class, 1L ) );
		scope.inTransaction( session -> {
			final var base = session.find( Owner.class, 1L );
			final var collection = (PersistentCollection<?>) base.propertyRefTags;
			final var descriptor = session.getFactory().getMappingMetamodel()
					.getCollectionDescriptor( Owner.class.getName() + ".propertyRefTags" );
			final var pc = session.getPersistenceContextInternal();
			assertThat( pc.getCollectionOwner( base.reference, descriptor ) ).isNull();
			assertThat( Hibernate.isInitialized( detachedProxy ) ).isFalse();
			try {
				collection.setOwner( detachedProxy );
				assertThat( ResultsHelper.getCollectionOwner( pc, descriptor, collection, base.reference, session ) )
						.isSameAs( base );
				assertThat( Hibernate.isInitialized( detachedProxy ) ).isFalse();
				assertThat( CollectionAuditSupport.resolveOwnerPersister( descriptor, base, session ) )
						.isSameAs( pc.getEntry( base ).getPersister() );
				collection.setOwner( null );
				assertThat( ResultsHelper.getCollectionOwner( pc, descriptor, collection, base.reference, session ) ).isNull();
				assertThat( CollectionAuditSupport.resolveOwnerPersister( descriptor, null, session ) )
						.isSameAs( descriptor.getOwnerEntityPersister() );
			}
			finally {
				collection.setOwner( base );
			}
		} );
	}

	@Test
	void currentStateCollectionsIgnoreAuditExclusions(SessionFactoryScope scope) {
		scope.inSession( session -> {
			final var base = session.find( Owner.class, 1L );
			assertThat( base.restoredTags ).containsExactly( "base hidden" );
			final var sub = session.find( SubOwner.class, 2L );
			assertThat( sub.revokedTags ).containsExactly( "sub hidden" );
		} );
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

		@Column(name = "owner_ref", unique = true)
		Long reference;

		@ElementCollection
		@CollectionTable(
				name = "override_property_ref_tags",
				joinColumns = @JoinColumn(name = "owner_ref", referencedColumnName = "owner_ref")
		)
		Set<String> propertyRefTags = new HashSet<>();

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
	@Audited.Override(name = "propertyRefTags", isAudited = false)
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
