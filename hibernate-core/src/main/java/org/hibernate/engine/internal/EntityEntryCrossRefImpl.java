package org.hibernate.engine.internal;

import org.hibernate.engine.spi.EntityEntry;
import org.hibernate.engine.spi.ManagedEntity;

/**
 * Implementation of the EntityEntryCrossRef interface
 */
public class EntityEntryCrossRefImpl implements EntityEntryCrossRef {
	private final Object entity;
	private final EntityEntry entityEntry;
	private final ManagedEntity previous;
	private final ManagedEntity next;
	private final int instanceId;

	public EntityEntryCrossRefImpl(
			Object entity,
			EntityEntry entityEntry,
			ManagedEntity previous,
			ManagedEntity next,
			int instanceId) {
		this.entity = entity;
		this.entityEntry = entityEntry;
		this.next = next;
		this.previous = previous;
		this.instanceId = instanceId;
	}

	@Override
	public Object getEntity() {
		return entity;
	}

	@Override
	public EntityEntry getEntityEntry() {
		return entityEntry;
	}

	@Override
	public ManagedEntity getPrevious() {
		return previous;
	}

	@Override
	public ManagedEntity getNext() {
		return next;
	}

	@Override
	public int getInstanceId() {
		return instanceId;
	}

	@Override
	public Object getKey() {
		return getEntity();
	}

	@Override
	public EntityEntry getValue() {
		return getEntityEntry();
	}

	@Override
	public EntityEntry setValue(EntityEntry entityEntry) {
		throw new UnsupportedOperationException();
	}
}
