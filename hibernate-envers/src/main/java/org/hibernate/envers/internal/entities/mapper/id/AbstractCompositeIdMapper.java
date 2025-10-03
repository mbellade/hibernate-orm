/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.envers.internal.entities.mapper.id;

import java.util.Map;

import org.hibernate.envers.exception.AuditException;
import org.hibernate.envers.internal.entities.PropertyData;
import org.hibernate.envers.internal.tools.Tools;
import org.hibernate.mapping.Component;
import org.hibernate.metamodel.spi.ValueAccess;
import org.hibernate.type.spi.CompositeTypeImplementor;


/**
 * An abstract identifier mapper implementation specific for composite identifiers.
 *
 * @author Adam Warski (adam at warski dot org)
 * @author Lukasz Antoniak (lukasz dot antoniak at gmail dot com)
 * @author Chris Cranford
 */
public abstract class AbstractCompositeIdMapper extends AbstractIdMapper implements SimpleIdMapperBuilder {
	protected final Component component;

	protected Map<PropertyData, AbstractIdMapper> ids;

	protected AbstractCompositeIdMapper(Component component) {
		super( component.getServiceRegistry() );
		this.component = component;
		ids = Tools.newLinkedHashMap();
	}

	@Override
	public void add(PropertyData propertyData) {
		add( propertyData, new SingleIdMapper( getServiceRegistry(), propertyData ) );
	}

	@Override
	public void add(PropertyData propertyData, AbstractIdMapper idMapper) {
		ids.put( propertyData, idMapper );
	}

	@Override
	public Object mapToIdFromMap(Map data) {
		if ( data == null ) {
			return null;
		}

		if ( !component.getType().isMutable() ) {
			return mapToImmutableIdFromMap( data );
		}

		final Object compositeId = instantiateCompositeId( null );

		if ( component.getType().isMutable() ) {
			for ( AbstractIdMapper mapper : ids.values() ) {
				if ( !mapper.mapToEntityFromMap( compositeId, data ) ) {
					return null;
				}
			}
		}

		return compositeId;
	}

	protected Object mapToImmutableIdFromMap(Map data) {
		assert !getComponentType().isMutable();
		final var propertyNames = component.getPropertyNames();
		final var values = new Object[propertyNames.length];
		for ( int i = 0; i < propertyNames.length; i++ ) {
			values[i] = data.get( propertyNames[i] );
		}
		return instantiateCompositeId( () -> values );
	}

	@Override
	public void mapToEntityFromEntity(Object objectTo, Object objectFrom) {
		// no-op; does nothing
	}

	protected Object instantiateCompositeId(ValueAccess valueAccess) {
		try {
			return getComponentType().getMappingModelPart()
					.getEmbeddableTypeDescriptor()
					.getRepresentationStrategy()
					.getInstantiator()
					.instantiate( valueAccess );
		}
		catch ( Exception e ) {
			throw new AuditException( e );
		}
	}

	protected CompositeTypeImplementor getComponentType() {
		return (CompositeTypeImplementor) component.getType();
	}
}
