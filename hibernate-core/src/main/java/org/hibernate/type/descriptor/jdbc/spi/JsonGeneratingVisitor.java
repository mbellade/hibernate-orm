/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.type.descriptor.jdbc.spi;

import org.hibernate.collection.spi.CollectionSemantics;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.collection.spi.PersistentMap;
import org.hibernate.metamodel.mapping.CollectionPart;
import org.hibernate.metamodel.mapping.CompositeIdentifierMapping;
import org.hibernate.metamodel.mapping.EmbeddableMappingType;
import org.hibernate.metamodel.mapping.EntityIdentifierMapping;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.mapping.ManagedMappingType;
import org.hibernate.metamodel.mapping.MappingType;
import org.hibernate.metamodel.mapping.PluralAttributeMapping;
import org.hibernate.metamodel.mapping.SelectableMapping;
import org.hibernate.metamodel.mapping.ValuedModelPart;
import org.hibernate.metamodel.mapping.internal.BasicValuedCollectionPart;
import org.hibernate.metamodel.mapping.internal.EmbeddedAttributeMapping;
import org.hibernate.metamodel.mapping.internal.SingleAttributeIdentifierMapping;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.type.BasicType;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.BasicPluralJavaType;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.ArrayJdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.format.JsonDocumentWriter;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

import static org.hibernate.type.descriptor.jdbc.StructHelper.getSubPart;

/**
 * Stateless helper class to serialize managed type values to JSON.
 */
public class JsonGeneratingVisitor {
	/**
	 * Serializes an array of values into JSON object/array
	 *
	 * @param elementMappingType the type definitions
	 * @param values the values to be serialized
	 * @param options wrapping options
	 * @param writer the document writer used for serialization
	 */
	public void visitArray(MappingType elementMappingType, Object[] values, WrapperOptions options, JsonDocumentWriter writer) {
		writer.startArray();
		if ( values.length == 0 ) {
			writer.endArray();
			return;
		}
		for ( Object value : values ) {
			try {
				visit( elementMappingType, value, options, writer );
			}
			catch (IOException e) {
				throw new IllegalArgumentException( "Could not serialize JSON array value", e );
			}
		}
		writer.endArray();
	}

	/**
	 * Serializes an array of values into JSON object/array
	 *
	 * @param elementJavaType the array element type
	 * @param elementJdbcType the JDBC type
	 * @param values values to be serialized
	 * @param options wrapping options
	 * @param writer the document writer used for serialization
	 */
	public void serializeArray(JavaType<?> elementJavaType, JdbcType elementJdbcType, Object[] values, WrapperOptions options, JsonDocumentWriter writer) {
		writer.startArray();
		if ( values.length == 0 ) {
			writer.endArray();
			return;
		}
		for ( Object value : values ) {
			if ( value == null ) {
				writer.nullValue();
			}
			else {
				writer.serializeJsonValue( value, (JavaType<?>) elementJavaType, elementJdbcType, options );
			}
		}
		writer.endArray();
	}

	/**
	 * Checks that a <code>JDBCType</code> is assignable to an array
	 *
	 * @param type the jdbc type
	 * @return <code>true</code> if types is of array kind <code>false</code> otherwise.
	 */
	private static boolean isArrayType(JdbcType type) {
		return (type.getDefaultSqlTypeCode() == SqlTypes.ARRAY ||
				type.getDefaultSqlTypeCode() == SqlTypes.JSON_ARRAY);
	}

	public void visit(MappingType mappedType, Object value, WrapperOptions options, JsonDocumentWriter writer)
			throws IOException {
		if ( handleNullOrLazy( value, writer ) ) {
			// nothing left to do
			return;
		}

		if ( mappedType instanceof EntityMappingType entityType ) {
			serializeEntity( value, entityType, options, writer );
		}
		else if ( mappedType instanceof ManagedMappingType managedMappingType ) {
			serializeObject( managedMappingType, value, options, writer );
		}
		else if ( mappedType instanceof BasicType<?> basicType ) {
			if ( isArrayType( basicType.getJdbcType() ) ) {
				final int length = Array.getLength( value );
				writer.startArray();
				if ( length != 0 ) {
					//noinspection unchecked
					final JavaType<Object> elementJavaType = ((BasicPluralJavaType<Object>) basicType.getJdbcJavaType()).getElementJavaType();
					final JdbcType elementJdbcType = ((ArrayJdbcType) basicType.getJdbcType()).getElementJdbcType();
					final Object domainArray = basicType.convertToRelationalValue( value );
					for ( int j = 0; j < length; j++ ) {
						writer.serializeJsonValue( Array.get( domainArray, j ), elementJavaType, elementJdbcType,
								options );
					}
				}
				writer.endArray();
			}
			else {
				writer.serializeJsonValue(
						basicType.convertToRelationalValue( value ),
						basicType.getJdbcJavaType(),
						basicType.getJdbcType(),
						options
				);
			}
		}
		else {
			throw new UnsupportedOperationException(
					"Support for mapping type not yet implemented: " + mappedType.getClass().getName()
			);
		}
	}

	/**
	 * Checks the provided {@code value} is either null or a lazy property.
	 *
	 * @param value the value to check
	 * @param writer the current {@link JsonDocumentWriter}
	 * @return {@code true} if it was, indicating no further processing of the value is needed, {@code false otherwise}.
	 */
	protected boolean handleNullOrLazy(Object value, JsonDocumentWriter writer) {
		if ( value == null ) {
			writer.nullValue();
			return true;
		}
		else {
			return false;
		}
	}

	/**
	 * Serialized an Object value to JSON object using a document writer.
	 *
	 * @param managedMappingType the managed mapping type of the given value
	 * @param value the value to be serialized
	 * @param options wrapping options
	 * @param writer the document writer
	 * @throws IOException if the underlying writer failed to serialize a mpped value or failed to perform need I/O.
	 */
	private void serializeObject(ManagedMappingType managedMappingType, Object value, WrapperOptions options, JsonDocumentWriter writer)
			throws IOException {
		writer.startObject();
		serializeObjectValues( managedMappingType, value, options, writer );
		writer.endObject();
	}

	/**
	 * JSON object managed type serialization.
	 *
	 * @param managedMappingType the managed mapping type of the given object
	 * @param object the object to be serialized
	 * @param options wrapping options
	 * @param writer the document writer
	 * @throws IOException if an error occurred while writing to an underlying writer
	 * @see #serializeObject(ManagedMappingType, Object, WrapperOptions, JsonDocumentWriter)
	 */
	protected void serializeObjectValues(ManagedMappingType managedMappingType, Object object, WrapperOptions options, JsonDocumentWriter writer)
			throws IOException {
		final Object[] values = managedMappingType.getValues( object );
		for ( int i = 0; i < values.length; i++ ) {
			final ValuedModelPart subPart = getSubPart( managedMappingType, i );
			final Object value = values[i];
			serializeModelPart( subPart, value, options, writer );
		}
	}

	protected void serializeModelPart(
			ValuedModelPart modelPart,
			Object value,
			WrapperOptions options,
			JsonDocumentWriter writer) throws IOException {
		if ( modelPart instanceof SelectableMapping selectableMapping ) {
			writer.objectKey( selectableMapping.getSelectableName() );
			visit( modelPart.getMappedType(), value, options, writer );
		}
		else if ( modelPart instanceof EmbeddedAttributeMapping embeddedAttribute ) {
			if ( value != null ) {
				final EmbeddableMappingType mappingType = embeddedAttribute.getMappedType();
				final SelectableMapping aggregateMapping = mappingType.getAggregateMapping();
				if ( aggregateMapping == null ) {
					serializeObjectValues( mappingType, value, options, writer );
				}
				else {
					final String name = aggregateMapping.getSelectableName();
					writer.objectKey( name );
					visit( mappingType, value, options, writer );
				}
			}
		}
		else {
			// could not handle model part, throw exception
			throw new UnsupportedOperationException(
					"Support for model part type not yet implemented: "
					+ (modelPart != null ? modelPart.getClass().getName() : "null")
			);
		}
	}

	protected void serializeEntity(
			Object value,
			EntityMappingType entityType,
			WrapperOptions options,
			JsonDocumentWriter writer) throws IOException {
		// We only need the identifier here
		final EntityIdentifierMapping identifierMapping = entityType.getIdentifierMapping();
		writer.objectKey( identifierMapping.getAttributeName() );
		serializeEntityIdentifier( value, identifierMapping, options, writer );
	}

	protected void serializeEntityIdentifier(
			Object value,
			EntityIdentifierMapping identifierMapping,
			WrapperOptions options,
			JsonDocumentWriter writer) throws IOException {
		final Object identifier = identifierMapping.getIdentifier( value );
		if ( identifierMapping instanceof SingleAttributeIdentifierMapping singleAttribute ) {
			writer.serializeJsonValue(
					identifier,
					singleAttribute.getJavaType(),
					singleAttribute.getSingleJdbcMapping().getJdbcType(),
					options
			);
		}
		else if ( identifier instanceof CompositeIdentifierMapping composite ) {
			visit( composite.getMappedType(), identifier, options, writer );
		}
		else {
			throw new UnsupportedOperationException(
					"Unsupported identifier type: " + identifier.getClass().getName() );
		}
	}

	protected void serializePluralAttribute(
			Object value,
			PluralAttributeMapping plural,
			WrapperOptions options,
			JsonDocumentWriter writer) throws IOException {
		if ( handleNullOrLazy( value, writer ) ) {
			// nothing left to do
			return;
		}

		final CollectionPart element = plural.getElementDescriptor();
		final CollectionSemantics<?, ?> collectionSemantics = plural.getMappedType().getCollectionSemantics();
		switch ( collectionSemantics.getCollectionClassification() ) {
			case MAP:
			case SORTED_MAP:
			case ORDERED_MAP:
				serializePersistentMap(
						(PersistentMap<?, ?>) value,
						plural.getIndexDescriptor(),
						element,
						options,
						writer
				);
				break;
			default:
				serializePersistentCollection(
						(PersistentCollection<?>) value,
						plural.getCollectionDescriptor(),
						element,
						options,
						writer
				);
		}
	}

	/**
	 * Serializes a persistent map to JSON [{key: ..., value: ...}, ...]
	 */
	private <K, E> void serializePersistentMap(
			PersistentMap<K, E> map,
			CollectionPart key,
			CollectionPart value,
			WrapperOptions options,
			JsonDocumentWriter writer) throws IOException {
		writer.startArray();
		for ( final Map.Entry<K, E> entry : map.entrySet() ) {
			writer.startObject();
			writer.objectKey( "key" );
			serializeCollectionPart( entry.getKey(), key, options, writer );
			writer.objectKey( "value" );
			serializeCollectionPart( entry.getValue(), value, options, writer );
			writer.endObject();
		}
		writer.endArray();
	}

	/**
	 * Serializes a persistent collection to a JSON array
	 */
	private <E> void serializePersistentCollection(
			PersistentCollection<E> collection,
			CollectionPersister persister,
			CollectionPart element,
			WrapperOptions options,
			JsonDocumentWriter appender) throws IOException {
		appender.startArray();
		final Iterator<?> entries = collection.entries( persister );
		while ( entries.hasNext() ) {
			serializeCollectionPart( entries.next(), element, options, appender );
		}
		appender.endArray();
	}

	private void serializeCollectionPart(
			Object value,
			CollectionPart collectionPart,
			WrapperOptions options,
			JsonDocumentWriter appender) throws IOException {
		if ( collectionPart instanceof BasicValuedCollectionPart basic ) {
			appender.serializeJsonValue( value, basic.getJavaType(), basic.getJdbcMapping().getJdbcType(), options );
		}
		else {
			visit( collectionPart.getMappedType(), value, options, appender );
		}
	}
}
