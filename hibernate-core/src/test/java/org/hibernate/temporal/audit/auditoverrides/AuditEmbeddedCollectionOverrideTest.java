package org.hibernate.temporal.audit.auditoverrides;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertNoTable;
import static org.hibernate.temporal.audit.auditoverrides.AuditOverrideAssertions.assertTable;

@AuditedTest
@DomainModel(annotatedClasses = {
		AuditEmbeddedCollectionOverrideTest.PlainOwner.class,
		AuditEmbeddedCollectionOverrideTest.OverrideOwner.class
})
@SessionFactory
@ServiceRegistry(settings = @Setting(
		name = StateManagementSettings.CHANGESET_ID_SUPPLIER,
		value = "org.hibernate.temporal.audit.auditoverrides.AuditEmbeddedCollectionOverrideTest$TxIdSupplier"
))
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
class AuditEmbeddedCollectionOverrideTest {
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
			final var plain = new PlainOwner();
			plain.id = 1L;
			plain.address.lines.add( "plain line" );
			plain.address.excludedLines.add( "excluded" );
			session.persist( plain );
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
	void embeddedCollectionsWithoutOverridesAreAudited(SessionFactoryScope scope) {
		try (var auditLog = AuditLogFactory.create( scope.getSessionFactory() )) {
			final var plain = auditLog.find( PlainOwner.class, 1L, 1 );
			assertThat( plain.address.lines ).containsExactly( "plain line" );
		}
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

	@Test
	void embeddedTablesKeepDeclarationSettings(DomainModelScope scope) {
		assertTable( scope, "embedded_plain_lines_AUD" );
		assertNoTable( scope, "embedded_plain_excluded_AUD" );
		assertTable( scope, "embedded_member_lines_audit" );
		assertNoTable( scope, "embedded_override_lines_AUD" );
		assertTable( scope, "embedded_override_hidden_AUD" );
		assertTable( scope, "top_level_lines_audit" );
		assertNoTable( scope, "top_level_hidden_AUD" );
	}

	@Entity(name = "EmbeddedPlainOwner")
	@Table(name = "embedded_plain_owner")
	@Audited
	static class PlainOwner {
		@Id
		long id;
		@Embedded
		PlainAddress address = new PlainAddress();
	}

	@Embeddable
	static class PlainAddress {
		@ElementCollection
		@CollectionTable(name = "embedded_plain_lines")
		Set<String> lines = new HashSet<>();

		@ElementCollection
		@CollectionTable(name = "embedded_plain_excluded")
		@Audited.Excluded
		Set<String> excludedLines = new HashSet<>();
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
