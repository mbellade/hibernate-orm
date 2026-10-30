package org.hibernate.temporal.audit.auditoverrides;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;

import org.hibernate.MappingException;
import org.hibernate.annotations.Audited;
import org.hibernate.annotations.Temporal;
import org.hibernate.boot.MetadataSources;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.ServiceRegistryScope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ServiceRegistry
@Jira("https://hibernate.atlassian.net/browse/HHH-20641")
class AuditOverrideValidationTest {
	@Test
	void blankNameFails(ServiceRegistryScope scope) {
		assertInvalid( scope, BlankName.class, BlankName.class, "Blank", " " );
	}

	@Test
	void unknownNameFails(ServiceRegistryScope scope) {
		assertInvalid( scope, UnknownName.class, UnknownName.class, "Unknown", "missing" );
	}

	@Test
	void collectionTableOnlyOverrideAlsoValidatesName(ServiceRegistryScope scope) {
		assertInvalid( scope, UnknownCollectionName.class, UnknownCollectionName.class, "Unknown", "missingCollection" );
	}

	@Test
	void unknownNameOnUnauditedEntityIsIgnored(ServiceRegistryScope scope) {
		final var metadata = new MetadataSources( scope.getRegistry() )
				.addAnnotatedClass( Unaudited.class ).buildMetadata();
		assertThat( metadata.getEntityBinding( Unaudited.class.getName() ).getAuxiliaryTable() ).isNull();
	}

	@Test
	void unknownNameOnUnauditedTemporalEntityIsIgnored(ServiceRegistryScope scope) {
		final var metadata = new MetadataSources( scope.getRegistry() )
				.addAnnotatedClass( UnauditedTemporal.class ).buildMetadata();
		assertThat( metadata.getEntityBinding( UnauditedTemporal.class.getName() ).getAuxiliaryTable() ).isNotNull();
	}

	@Test
	void blankNameOnUnauditedEntityStillFails(ServiceRegistryScope scope) {
		assertInvalid( scope, UnauditedBlank.class, UnauditedBlank.class, "Blank", "" );
	}

	@Test
	void blankNameOnMappedSuperclassFails(ServiceRegistryScope scope) {
		assertInvalid( scope, MappedEntity.class, BlankMappedSuperclass.class, "Blank", " " );
	}

	private static void assertInvalid(
			ServiceRegistryScope scope,
			Class<?> entityClass,
			Class<?> declaringClass,
			String kind,
			String property
	) {
		final var sources = new MetadataSources( scope.getRegistry() ).addAnnotatedClass( entityClass );
		final var exception = assertThrows( MappingException.class, sources::buildMetadata );
		assertThat( exception ).hasMessageContaining( kind )
				.hasMessageContaining( declaringClass.getName() )
				.hasMessageContaining( "'" + property + "'" );
	}

	@MappedSuperclass
	static class Base {
		@Id
		long id;
		String name;
	}

	@Entity(name = "OverrideBlankName")
	@Table(name = "override_blank_name")
	@Audited
	@Audited.Override(name = " ")
	static class BlankName extends Base {
	}

	@Entity(name = "OverrideUnknownName")
	@Table(name = "override_unknown_name")
	@Audited
	@Audited.Override(name = "missing", isAudited = false)
	static class UnknownName extends Base {
	}

	@Entity(name = "OverrideUnknownCollectionName")
	@Table(name = "override_unknown_collection")
	@Audited
	@Audited.Override(
			name = "missingCollection",
			collectionTable = @Audited.CollectionTable(name = "unused")
	)
	static class UnknownCollectionName extends Base {
	}

	@Entity(name = "OverrideUnaudited")
	@Table(name = "override_unaudited")
	@Audited.Override(name = "missing")
	static class Unaudited extends Base {
	}

	@Entity(name = "OverrideUnauditedTemporal")
	@Table(name = "override_unaudited_temporal")
	@Temporal
	@Audited.Override(name = "missing")
	static class UnauditedTemporal extends Base {
	}

	@Entity(name = "OverrideUnauditedBlank")
	@Table(name = "override_unaudited_blank")
	@Audited.Override(name = "")
	static class UnauditedBlank extends Base {
	}

	@MappedSuperclass
	@Audited.Override(name = " ")
	static class BlankMappedSuperclass extends Base {
	}

	@Entity(name = "OverrideMappedEntity")
	@Table(name = "override_mapped_entity")
	static class MappedEntity extends BlankMappedSuperclass {
	}
}
