package org.hibernate.engine.internal;

import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.ManagedEntity;

import java.util.Map;

/**
 * Specialization of {@link Map.Entry} for entries in the current persistence context
 */
public interface EntityEntryCrossRef extends Map.Entry<Object, EntityEntry> {
	// todo marco : move this to spi ?
	/**
	 * The entity
	 *
	 * @return The entity
	 */
	Object getEntity();

	/**
	 * The associated EntityEntry
	 *
	 * @return The EntityEntry associated with the entity in this context
	 */
	EntityEntry getEntityEntry();

	ManagedEntity getPrevious();

	ManagedEntity getNext();

	int getInstanceId();
}
