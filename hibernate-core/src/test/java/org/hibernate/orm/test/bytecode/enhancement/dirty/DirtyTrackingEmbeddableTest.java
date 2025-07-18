/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.bytecode.enhancement.dirty;

import org.hibernate.testing.bytecode.enhancement.EnhancementOptions;
import org.hibernate.testing.bytecode.enhancement.EnhancerTestUtils;
import org.hibernate.testing.bytecode.enhancement.extension.BytecodeEnhanced;
import org.hibernate.testing.orm.junit.JiraKey;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Objects;

@JiraKey( "HHH-16774" )
@JiraKey( "HHH-16952" )
@BytecodeEnhanced
@EnhancementOptions(
		inlineDirtyChecking = true,
		extendedEnhancement = true
)
public class DirtyTrackingEmbeddableTest {

	@Test
	public void test() {
		SimpleEntity entity = new SimpleEntity();
		Address1 address1 = new Address1();
		entity.address1 = address1;
		Address2 address2 = new Address2();
		entity.address2 = address2;
		EnhancerTestUtils.clearDirtyTracking( entity );

		// testing composite object
		address1.city = "Arendal";
		address2.state = "Sorlandet";
		EnhancerTestUtils.checkDirtyTracking( entity, "address1.city", "address2.state" );
		EnhancerTestUtils.clearDirtyTracking( entity );
	}

	@Test
	public void testSettingEmbedded() {
		SimpleEntity entity = new SimpleEntity();
		SimpleEmbeddable embeddable1 = new SimpleEmbeddable();
		embeddable1.value = "test";
		entity.simpleEmbeddable = embeddable1;
		EnhancerTestUtils.clearDirtyTracking( entity );

		// same value, no dirty tracking should be triggered
		entity.simpleEmbeddable = embeddable1;
		EnhancerTestUtils.checkDirtyTracking( entity );

		// equivalent value, no dirty tracking should be triggered
		SimpleEmbeddable embeddable2 = new SimpleEmbeddable();
		embeddable2.value = "test";
		entity.simpleEmbeddable = embeddable2;
		EnhancerTestUtils.checkDirtyTracking( entity );

		// different value, dirty tracking should be triggered
		SimpleEmbeddable embeddable3 = new SimpleEmbeddable();
		embeddable3.value = "test2";
		entity.simpleEmbeddable = embeddable3;
		EnhancerTestUtils.checkDirtyTracking( entity, "simpleEmbeddable" );
	}

	@Test
	public void testSettingValueInEmbedded() {
		SimpleEntity entity = new SimpleEntity();
		SimpleEmbeddable embeddable1 = new SimpleEmbeddable();
		embeddable1.value = "test";
		entity.simpleEmbeddable = embeddable1;
		EnhancerTestUtils.clearDirtyTracking( entity );

		// same value, no dirty tracking should be triggered
		entity.simpleEmbeddable.value = "test";
		EnhancerTestUtils.checkDirtyTracking( entity );

		// different value, dirty tracking should be triggered
		entity.simpleEmbeddable.value = "test2";
		EnhancerTestUtils.checkDirtyTracking( entity, "simpleEmbeddable.value" );
	}

	@Test
	public void testSettingValueInNestedEmbedded() {
		SimpleEntity entity = new SimpleEntity();
		NestedEmbeddable nestedEmbeddable = new NestedEmbeddable();
		SimpleEmbeddable simpleEmbeddable = new SimpleEmbeddable();
		simpleEmbeddable.value = "test";
		nestedEmbeddable.simpleEmbeddable = simpleEmbeddable;
		nestedEmbeddable.value = "nested";
		entity.nestedEmbeddable = nestedEmbeddable;
		EnhancerTestUtils.clearDirtyTracking( entity );

		// same value, no dirty tracking should be triggered
		entity.nestedEmbeddable.simpleEmbeddable.value = "test";
		entity.nestedEmbeddable.value = "nested";
		EnhancerTestUtils.checkDirtyTracking( entity );

		// different value in nested embeddable, dirty tracking should be triggered
		entity.nestedEmbeddable.simpleEmbeddable.value = "test2";
		EnhancerTestUtils.checkDirtyTracking( entity, "nestedEmbeddable.simpleEmbeddable.value" );
		EnhancerTestUtils.clearDirtyTracking( entity );

		// different value in nested embeddable, dirty tracking should be triggered
		entity.nestedEmbeddable.value = "nested2";
		EnhancerTestUtils.checkDirtyTracking( entity, "nestedEmbeddable.value" );
	}

	// --- //

	@Embeddable
	private static class Address1 {
		String street1;
		String street2;
		String city;
		String state;
		String zip;
		String phone;
	}

	private static class Address2 {
		String street1;
		String street2;
		String city;
		String state;
		String zip;
		String phone;
	}

	private static class SimpleEmbeddable {
		String value;

		@Override
		public boolean equals(Object o) {
			if ( o == null || getClass() != o.getClass() ) {
				return false;
			}

			SimpleEmbeddable that = (SimpleEmbeddable) o;
			return Objects.equals( value, that.value );
		}

		@Override
		public int hashCode() {
			return Objects.hashCode( value );
		}
	}

	private static class NestedEmbeddable {
		SimpleEmbeddable simpleEmbeddable;
		String value;
	}

	@Entity
	private static class SimpleEntity {

		@Id
		Long id;

		String name;

		Address1 address1;
		@Embedded
		Address2 address2;
		@Embedded
		SimpleEmbeddable simpleEmbeddable;
		@Embedded
		NestedEmbeddable nestedEmbeddable;
	}
}
