/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.inheritance.joined;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.JiraKey;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DomainModel(annotatedClasses = {
		JoinedSubclassIdClassMappedSuperclassTest.ParentEntity.class,
		JoinedSubclassIdClassMappedSuperclassTest.ChildEntity.class
})
@SessionFactory
@JiraKey("https://hibernate.atlassian.net/browse/HHH-20357")
public class JoinedSubclassIdClassMappedSuperclassTest {

	@BeforeAll
	public void initData(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			var parent = new ChildEntity();
			parent.setEntityId( 1L );
			parent.setRevisionId( 100 );
			parent.setParentName( "parent1" );
			parent.setChildName( "child1" );
			session.persist( parent );

			var parent2 = new ChildEntity();
			parent2.setEntityId( 1L );
			parent2.setRevisionId( 200 );
			parent2.setParentName( "parent2" );
			parent2.setChildName( "child2" );
			session.persist( parent2 );

			var parent3 = new ChildEntity();
			parent3.setEntityId( 1L );
			parent3.setRevisionId( 300 );
			parent3.setParentName( "parent3" );
			parent3.setChildName( "child3" );
			session.persist( parent3 );
		} );
	}

	@AfterAll
	public void cleanUp(SessionFactoryScope scope) {
		scope.getSessionFactory().getSchemaManager().truncateMappedObjects();
	}

	@Test
	public void testJoinedSubclassQuery(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			List<ChildEntity> results = session.createQuery(
					"select c from ChildEntity c order by c.revisionId",
					ChildEntity.class
			).getResultList();
			assertEquals( 3, results.size() );
			assertEquals( "child1", results.get( 0 ).getChildName() );
			assertEquals( "parent1", results.get( 0 ).getParentName() );
			assertEquals( "child2", results.get( 1 ).getChildName() );
			assertEquals( "parent2", results.get( 1 ).getParentName() );
			assertEquals( "child3", results.get( 2 ).getChildName() );
			assertEquals( "parent3", results.get( 2 ).getParentName() );
		} );
	}

	public static class CompositeId implements Serializable {
		private Long entityId;
		private Integer revisionId;

		public CompositeId() {
		}

		public CompositeId(Long entityId, Integer revisionId) {
			this.entityId = entityId;
			this.revisionId = revisionId;
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}
			if ( !(o instanceof CompositeId that) ) {
				return false;
			}
			return Objects.equals( entityId, that.entityId )
					&& Objects.equals( revisionId, that.revisionId );
		}

		@Override
		public int hashCode() {
			return Objects.hash( entityId, revisionId );
		}
	}

	@MappedSuperclass
	@IdClass(CompositeId.class)
	public abstract static class BaseEntity {
		@Id
		@Column(name = "ENTITY_ID")
		private Long entityId;

		@Id
		@Column(name = "REVISION_ID")
		private Integer revisionId;

		public Long getEntityId() {
			return entityId;
		}

		public void setEntityId(Long entityId) {
			this.entityId = entityId;
		}

		public Integer getRevisionId() {
			return revisionId;
		}

		public void setRevisionId(Integer revisionId) {
			this.revisionId = revisionId;
		}
	}

	@Entity(name = "ParentEntity")
	@Inheritance(strategy = InheritanceType.JOINED)
	public static class ParentEntity extends BaseEntity {
		@Column(name = "PARENT_NAME")
		private String parentName;

		public String getParentName() {
			return parentName;
		}

		public void setParentName(String parentName) {
			this.parentName = parentName;
		}
	}

	@Entity(name = "ChildEntity")
	public static class ChildEntity extends ParentEntity {
		@Column(name = "CHILD_NAME")
		private String childName;

		public String getChildName() {
			return childName;
		}

		public void setChildName(String childName) {
			this.childName = childName;
		}
	}
}
