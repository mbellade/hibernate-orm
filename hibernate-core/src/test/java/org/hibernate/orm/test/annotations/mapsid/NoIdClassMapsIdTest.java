/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.annotations.mapsid;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToMany;
import org.hibernate.annotations.processing.Exclude;
import org.hibernate.testing.orm.junit.DomainModel;
import org.hibernate.testing.orm.junit.SessionFactory;
import org.hibernate.testing.orm.junit.SessionFactoryScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SessionFactory
@DomainModel(annotatedClasses = {NoIdClassMapsIdTest.Loan.class, NoIdClassMapsIdTest.Extension.class})
public class NoIdClassMapsIdTest {

	@Test void test(SessionFactoryScope scope) {
		Extension eid = scope.fromTransaction( s -> {
			Loan loan = new Loan();
			loan.id = 999L;
			Extension extension = new Extension();
			extension.exLoanId = loan.id;
			extension.loan = loan;
			extension.exNo = 1;
			extension.exExtensionDays = 30;
			loan.extensions.add(extension);
			extension = new Extension();
			extension.exLoanId = loan.id;
			extension.loan = loan;
			extension.exNo = 2;
			extension.exExtensionDays = 14;
			loan.extensions.add(extension);
			s.persist(loan);
			return extension;
		});
		scope.inSession( s -> {
			List<Extension> extensions = s.createQuery("from Extension", Extension.class).getResultList();
			assertEquals(2, extensions.size());
		} );
		scope.inSession( s -> {
			Extension extension = s.find(Extension.class, eid);
			assertEquals(14, extension.exExtensionDays);
			assertEquals(2, extension.exNo);
			assertEquals(999L, extension.exLoanId);
			assertNotNull( extension.loan );
		});
		scope.inSession( s -> {
			Loan loan = s.find(Loan.class, eid.exLoanId);
			Extension extension = loan.extensions.get(0);
			assertEquals(1, extension.exNo);
			assertEquals(30, extension.exExtensionDays);
			assertEquals(999L, extension.exLoanId);
			assertEquals(loan, extension.loan);
		});
	}

	@Entity(name = "Loan")
	static class Loan {
		@Id
		@Column(name = "LOAN_ID")
		private Long id;

		private BigDecimal amount = BigDecimal.ZERO;

		@OneToMany(cascade = CascadeType.ALL, mappedBy = "loan")
		private List<Extension> extensions = new ArrayList<>();
	}


	@Entity(name = "Extension")
	@Exclude
	static class Extension {
		@Id
		private Long exLoanId;

		@Id
		@Column(name = "EX_NO")
		private int exNo;

		@Column(name = "EX_EXTENSION_DAYS")
		private int exExtensionDays;

		@ManyToOne
		@MapsId("exLoanId")
		@JoinColumn(name = "EX_LOAN_ID")
		private Loan loan;
	}
}
