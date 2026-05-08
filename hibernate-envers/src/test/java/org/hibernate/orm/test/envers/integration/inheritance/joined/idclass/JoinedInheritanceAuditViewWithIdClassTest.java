/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.envers.integration.inheritance.joined.idclass;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.Audited;
import org.hibernate.envers.configuration.EnversSettings;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.ServiceRegistry;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.hibernate.testing.orm.junit.Setting;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ServiceRegistry(settings = {
		@Setting(name = EnversSettings.REVISION_FIELD_NAME, value = "REV_ID")
})
@DomainModel(annotatedClasses = {
		JoinedInheritanceAuditViewWithIdClassTest.PartyEntity.class,
		JoinedInheritanceAuditViewWithIdClassTest.PersonEntity.class,
		JoinedInheritanceAuditViewWithIdClassTest.PartyAuditView.class,
		JoinedInheritanceAuditViewWithIdClassTest.PersonAuditView.class
})
@SessionFactory
@JiraKey("https://hibernate.atlassian.net/browse/HHH-20357")
public class JoinedInheritanceAuditViewWithIdClassTest {

	@BeforeAll
	public void initData(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			var person = new PersonEntity();
			person.setId( 1L );
			person.setFullName( "fullName.1" );
			person.setFirstName( "firstName.1" );
			person.setLastName( "lastName.1" );
			session.persist( person );
		} );
		scope.inTransaction( session -> {
			var person = session.find( PersonEntity.class, 1L );
			person.setFullName( "fullName.2" );
			person.setFirstName( "firstName.2" );
			person.setLastName( "lastName.2" );
		} );
		scope.inTransaction( session -> {
			var person = session.find( PersonEntity.class, 1L );
			person.setFullName( "fullName.3" );
			person.setFirstName( "firstName.3" );
			person.setLastName( "lastName.3" );
		} );
	}

	@AfterAll
	public void cleanUp(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	public void testJoinedAuditViewReturnsAllRows(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			List<PersonAuditView> audits = session.createQuery(
					"select p from PersonAuditView p order by p.revisionId",
					PersonAuditView.class
			).getResultList();
			assertEquals( 3, audits.size() );
			assertEquals( "firstName.1", audits.get( 0 ).getFirstName() );
			assertEquals( "firstName.2", audits.get( 1 ).getFirstName() );
			assertEquals( "firstName.3", audits.get( 2 ).getFirstName() );
		} );
	}

	@Test
	public void testJoinedAuditViewReturnsCorrectFullName(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			List<PersonAuditView> audits = session.createQuery(
					"select p from PersonAuditView p order by p.revisionId",
					PersonAuditView.class
			).getResultList();
			assertEquals( 3, audits.size() );
			assertEquals( "fullName.1", audits.get( 0 ).getFullName() );
			assertEquals( "fullName.2", audits.get( 1 ).getFullName() );
			assertEquals( "fullName.3", audits.get( 2 ).getFullName() );
		} );
	}

	@Test
	public void testAuditReaderReturnsCorrectData(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			var auditReader = AuditReaderFactory.get( session );
			var person = auditReader.find( PersonEntity.class, 1L, 1 );
			assertEquals( "fullName.1", person.getFullName() );
			assertEquals( "firstName.1", person.getFirstName() );
		} );
	}

	public static class AuditId implements Serializable {
		private Long entityId;
		private Integer revisionId;

		public AuditId() {
		}

		public AuditId(Long entityId, Integer revisionId) {
			this.entityId = entityId;
			this.revisionId = revisionId;
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}
			if ( !(o instanceof AuditId auditId) ) {
				return false;
			}
			return Objects.equals( entityId, auditId.entityId )
					&& Objects.equals( revisionId, auditId.revisionId );
		}

		@Override
		public int hashCode() {
			return Objects.hash( entityId, revisionId );
		}
	}

	@Entity(name = "PartyEntity")
	@Table(name = "PARTY")
	@Audited
	@Inheritance(strategy = InheritanceType.JOINED)
	public abstract static class PartyEntity {
		@Id
		@Column(name = "PTY_ID")
		private Long id;

		@Column(name = "FULL_NAME")
		private String fullName;

		public Long getId() {
			return id;
		}

		public void setId(Long id) {
			this.id = id;
		}

		public String getFullName() {
			return fullName;
		}

		public void setFullName(String fullName) {
			this.fullName = fullName;
		}
	}

	@Entity(name = "PersonEntity")
	@Table(name = "PERSON")
	@Audited
	public static class PersonEntity extends PartyEntity {
		@Column(name = "FIRST_NAME")
		private String firstName;

		@Column(name = "LAST_NAME")
		private String lastName;

		public String getFirstName() {
			return firstName;
		}

		public void setFirstName(String firstName) {
			this.firstName = firstName;
		}

		public String getLastName() {
			return lastName;
		}

		public void setLastName(String lastName) {
			this.lastName = lastName;
		}
	}

	@MappedSuperclass
	@IdClass(AuditId.class)
	public abstract static class BaseAuditView {
		@Id
		@Column(name = "PTY_ID")
		private Long entityId;

		@Id
		@Column(name = "REV_ID")
		private Integer revisionId;

		public Long getEntityId() {
			return entityId;
		}

		public Integer getRevisionId() {
			return revisionId;
		}
	}

	@Entity(name = "PartyAuditView")
	@Table(name = "PARTY_AUD")
	@Immutable
	@Inheritance(strategy = InheritanceType.JOINED)
	@AttributeOverride(name = "entityId", column = @Column(name = "PTY_ID"))
	public abstract static class PartyAuditView extends BaseAuditView {
		@Column(name = "FULL_NAME")
		private String fullName;

		public String getFullName() {
			return fullName;
		}
	}

	@Entity(name = "PersonAuditView")
	@Table(name = "PERSON_AUD")
	public static class PersonAuditView extends PartyAuditView {
		@Column(name = "FIRST_NAME")
		private String firstName;

		@Column(name = "LAST_NAME")
		private String lastName;

		public String getFirstName() {
			return firstName;
		}

		public String getLastName() {
			return lastName;
		}
	}
}
