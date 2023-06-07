package org.hibernate.orm.test.caching;

import java.util.List;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@DomainModel( annotatedClasses = {
		CachingAndVersionOneToOneLazyTest.Domain.class,
		CachingAndVersionOneToOneLazyTest.DomainID.class
} )
@SessionFactory
public class CachingAndVersionOneToOneLazyTest {
	@BeforeAll
	public void setUp(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final Domain domain = new Domain();
			final DomainID domainID = new DomainID();
			domain.setDomainID( domainID );
			session.persist( domain );
			session.persist( domainID );
		} );
	}

	@AfterAll
	public void tearDown(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			session.createMutationQuery( "delete from DomainID" ).executeUpdate();
			session.createMutationQuery( "delete from Domain" ).executeUpdate();
		} );
	}

	@Test
	public void testSelect(SessionFactoryScope scope) {
		scope.inTransaction( session -> {
			final List<Domain> domains = session.createQuery(
					"SELECT d from Domain d",
					Domain.class
			).getResultList();
			assertThat( domains.size() ).isEqualTo( 1 );
		} );
	}

	@Entity( name = "Domain" )
	public static class Domain {
		@Id
		@GeneratedValue
		private Long id;

		@Version
		private Integer rowVersion;

		@OneToOne( mappedBy = "domain" )
		private DomainID domainID;

		public Long getId() {
			return id;
		}

		public Integer getRowVersion() {
			return rowVersion;
		}

		public DomainID getDomainID() {
			return domainID;
		}

		public void setDomainID(DomainID domainID) {
			this.domainID = domainID;
			domainID.setDomain( this );
		}
	}

	@Entity( name = "DomainID" )
	@Cacheable
	@Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
	public static class DomainID {
		@Id
		@GeneratedValue
		private Long id;

		@Version
		private Integer rowVersion;

		@OneToOne( fetch = FetchType.LAZY )
		private Domain domain;

		public Long getId() {
			return id;
		}

		public Integer getRowVersion() {
			return rowVersion;
		}

		public Domain getDomain() {
			return domain;
		}

		public void setDomain(Domain domain) {
			this.domain = domain;
		}
	}
}
