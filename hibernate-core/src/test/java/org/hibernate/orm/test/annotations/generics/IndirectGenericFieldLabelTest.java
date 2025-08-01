/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.orm.test.annotations.generics;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.testing.orm.junit.Jira;
import org.hibernate.testing.util.ServiceRegistryUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Jira( "https://hibernate.atlassian.net/browse/HHH-19620" )
public class IndirectGenericFieldLabelTest {
	@Test
	void indirectGenericFieldSameGenericName() {
		try (final StandardServiceRegistry serviceRegistry = ServiceRegistryUtil.serviceRegistryBuilder()
				.enableAutoClose()
				.build()) {
			final Metadata metadata = new MetadataSources( serviceRegistry )
					.addAnnotatedClasses( MappedSuper.class, Middle1.class, EntitySameName.class )
					.buildMetadata();
			final PersistentClass entity = metadata.getEntityBinding( EntitySameName.class.getName() );
			assertThat( entity.getProperty( "genericField" ).getType().getReturnedClass() ).isEqualTo( String.class );
		}
	}

	@Test
	void indirectGenericFieldDifferentGenericName() {
		try (final StandardServiceRegistry serviceRegistry = ServiceRegistryUtil.serviceRegistryBuilder()
				.enableAutoClose()
				.build()) {
			final Metadata metadata = new MetadataSources( serviceRegistry )
					.addAnnotatedClasses( MappedSuper.class, Middle2.class, EntityDifferentName.class )
					.buildMetadata();
			final PersistentClass entity = metadata.getEntityBinding( EntityDifferentName.class.getName() );
			assertThat( entity.getProperty( "genericField" ).getType().getReturnedClass() ).isEqualTo( Integer.class );
		}
	}

	@MappedSuperclass
	public static abstract class MappedSuper<T1> {
		@Id
		Long id;

		T1 genericField;
	}

	@MappedSuperclass
	public static abstract class Middle1<T1> extends MappedSuper<T1> { // same type variable name
	}

	@MappedSuperclass
	public static abstract class Middle2<T2> extends MappedSuper<T2> { // different type variable name
	}

	@Entity
	public static class EntitySameName extends Middle1<String> {
	}

	@Entity
	public static class EntityDifferentName extends Middle2<Integer> {
	}
}
