package org.hibernate.temporal.audit.collection;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;

import org.hibernate.SharedSessionContract;
import org.hibernate.annotations.Audited;
import org.hibernate.cfg.StateManagementSettings;
import org.hibernate.temporal.spi.ChangesetIdentifierSupplier;
import org.hibernate.testing.orm.junit.AuditedTest;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.DomainModelScope;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.Setting;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the {@link Audited.Table @Audited.Table} name of an entity
 * is not reused for the audit tables of its collections.
 */
@AuditedTest
@SessionFactory
@DomainModel(annotatedClasses = {
		AuditCustomTableCollectionTest.Owner.class,
		AuditCustomTableCollectionTest.Child.class
})
@ServiceRegistry(settings = @Setting(name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.collection.AuditCustomTableCollectionTest$TxIdSupplier"))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
class AuditCustomTableCollectionTest {
	private static int currentTxId;

	public static class TxIdSupplier implements ChangesetIdentifierSupplier<Integer> {
		@Override
		public Integer generateIdentifier(SharedSessionContract session) {
			return ++currentTxId;
		}
	}

	@Test
	void testElementCollectionAuditTable(DomainModelScope scope) {
		assertDistinctAuditTable( scope, "tags" );
	}

	@Test
	void testOneToManyJoinColumnAuditTable(DomainModelScope scope) {
		assertDistinctAuditTable( scope, "children" );
	}

	private static void assertDistinctAuditTable(DomainModelScope scope, String role) {
		final var metadata = scope.getDomainModel();
		final var entityAuditTable = metadata.getEntityBinding( Owner.class.getName() ).getAuxiliaryTable();
		final var collectionAuditTable =
				metadata.getCollectionBinding( Owner.class.getName() + "." + role ).getAuxiliaryTable();
		assertThat( entityAuditTable.getName() ).isEqualTo( "owner_history" );
		assertThat( collectionAuditTable ).isNotNull();
		assertThat( collectionAuditTable )
				.as( "Audit table of collection '%s' (columns %s)", role, collectionAuditTable.getColumns() )
				.isNotSameAs( entityAuditTable );
		assertThat( collectionAuditTable.getName() ).isNotEqualToIgnoringCase( entityAuditTable.getName() );
	}

	@Audited
	@Audited.Table(name = "owner_history")
	@Entity(name = "Owner")
	static class Owner {
		@Id
		long id;
		String name;
		@ElementCollection
		Set<String> tags = new HashSet<>();
		@OneToMany
		@JoinColumn(name = "owner_id")
		Set<Child> children = new HashSet<>();
	}

	@Audited
	@Entity(name = "Child")
	static class Child {
		@Id
		long id;
		String name;
	}
}
