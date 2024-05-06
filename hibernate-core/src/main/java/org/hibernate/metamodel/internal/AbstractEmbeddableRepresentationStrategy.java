/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.metamodel.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.mapping.Component;
import org.hibernate.mapping.Property;
import org.hibernate.metamodel.spi.EmbeddableRepresentationStrategy;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.property.access.spi.PropertyAccess;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * @author Steve Ebersole
 */
public abstract class AbstractEmbeddableRepresentationStrategy implements EmbeddableRepresentationStrategy {
	private final JavaType<?> embeddableJavaType;

	private final int propertySpan;
	private final PropertyAccess[] propertyAccesses;
	private final boolean hasCustomAccessors;

	private final Map<String,Integer> attributeNameToPositionMap;

	public AbstractEmbeddableRepresentationStrategy(
			Component bootDescriptor,
			JavaType<?> embeddableJavaType,
			RuntimeModelCreationContext creationContext) {
		this.propertySpan = bootDescriptor.getPropertySpan();
		this.embeddableJavaType = embeddableJavaType;

		this.propertyAccesses = new PropertyAccess[ propertySpan ];
		this.attributeNameToPositionMap = new ConcurrentHashMap<>( propertySpan );

		boolean foundCustomAccessor = false;
		for ( int i = 0; i < bootDescriptor.getProperties().size(); i++ ) {
			final Property property = bootDescriptor.getProperty( i );
			propertyAccesses[i] = buildPropertyAccess( property, bootDescriptor.getPropertyDeclaringClass( property ) );
			attributeNameToPositionMap.put( property.getName(), i );

			if ( !property.isBasicPropertyAccessor() ) {
				foundCustomAccessor = true;
			}
		}

		hasCustomAccessors = foundCustomAccessor;
	}

	protected abstract PropertyAccess buildPropertyAccess(Property bootAttributeDescriptor, Class<?> declaringClass);

	public JavaType<?> getEmbeddableJavaType() {
		return embeddableJavaType;
	}

	@Override
	public JavaType<?> getMappedJavaType() {
		return getEmbeddableJavaType();
	}

	public int getPropertySpan() {
		return propertySpan;
	}

	public PropertyAccess[] getPropertyAccesses() {
		return propertyAccesses;
	}

	public boolean hasCustomAccessors() {
		return hasCustomAccessors;
	}

	@Override
	public PropertyAccess resolvePropertyAccess(Property bootAttributeDescriptor) {
		return propertyAccesses[ attributeNameToPositionMap.get( bootAttributeDescriptor.getName() ) ];
	}
}
