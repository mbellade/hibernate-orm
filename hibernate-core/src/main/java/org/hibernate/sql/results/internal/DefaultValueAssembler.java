package org.hibernate.sql.results.internal;

import org.hibernate.metamodel.mapping.AttributeMapping;
import org.hibernate.sql.results.graph.DomainResultAssembler;
import org.hibernate.sql.results.jdbc.spi.RowProcessingState;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * Supplies the default value for a primitive entity attribute omitted from the SQL result.
 *
 * @author Gavin King
 */
public class DefaultValueAssembler<J> implements DomainResultAssembler<J> {
	private final JavaType<J> javaType;
	private final J defaultValue;

	public DefaultValueAssembler(JavaType<J> javaType) {
		this.javaType = javaType;
		this.defaultValue = javaType.getDefaultValue();
	}

	/**
	 * An assembler for an attribute omitted from the SQL result, for example because
	 * the columns it maps are not present in the audit table: null for reference
	 * attributes and the default value for primitives.
	 */
	public static DomainResultAssembler<?> forUnfetchedAttribute(AttributeMapping attributeMapping) {
		final var javaType = attributeMapping.getMappedType().getMappedJavaType();
		final var propertyAccess = attributeMapping.getPropertyAccess();
		// Inspect the declared property type: int and Integer share the same JavaType descriptor.
		return propertyAccess != null && propertyAccess.getGetter().getReturnTypeClass().isPrimitive()
				? new DefaultValueAssembler<>( javaType )
				: new NullValueAssembler<>( javaType );
	}

	@Override
	public J assemble(RowProcessingState rowProcessingState) {
		return defaultValue;
	}

	@Override
	public JavaType<J> getAssembledJavaType() {
		return javaType;
	}
}
