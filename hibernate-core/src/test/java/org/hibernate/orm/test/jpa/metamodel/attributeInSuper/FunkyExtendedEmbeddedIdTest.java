/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.orm.test.jpa.metamodel.attributeInSuper;

import jakarta.persistence.metamodel.EmbeddableType;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.orm.junit.EntityManagerFactoryScope;
import org.hibernate.testing.orm.junit.FailureExpected;
import org.hibernate.testing.orm.junit.Jpa;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * An attempt at defining a test based on the HHH-8712 bug report
 */
@Jpa(annotatedClasses = {
		WorkOrderId.class,
		WorkOrder.class,
		WorkOrderComponentId.class,
		WorkOrderComponent.class
})
@Disabled // todo marco : this actually works with embedded inheritance, so probably rework the test
public class FunkyExtendedEmbeddedIdTest {

	@Test
	@TestForIssue(jiraKey = "HHH-8712")
	public void ensureAttributeForEmbeddableIsGeneratedInMappedSuperClass(EntityManagerFactoryScope scope) {
		EmbeddableType<WorkOrderComponentId> woci = scope.getEntityManagerFactory().getMetamodel()
				.embeddable( WorkOrderComponentId.class );
		assertThat( woci, notNullValue() );
		assertThat( woci.getAttribute( "workOrder" ), notNullValue() );
		assertThat( woci.getAttribute( "plantId" ), notNullValue() );
		assertThat( woci.getAttribute( "lineNumber" ), notNullValue() );
	}
}
