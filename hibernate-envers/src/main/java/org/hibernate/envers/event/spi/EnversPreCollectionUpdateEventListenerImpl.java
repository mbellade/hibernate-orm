/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.envers.event.spi;

import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.CollectionEntry;
import org.hibernate.envers.boot.internal.EnversService;
import org.hibernate.event.spi.PreCollectionUpdateEvent;
import org.hibernate.event.spi.PreCollectionUpdateEventListener;
import org.hibernate.persister.collection.CollectionPersister;

/**
 * Envers-specific collection update event listener
 *
 * @author Adam Warski (adam at warski dot org)
 * @author HernпїЅn Chanfreau
 * @author Steve Ebersole
 * @author Chris Cranford
 */
public class EnversPreCollectionUpdateEventListenerImpl
		extends BaseEnversCollectionEventListener
		implements PreCollectionUpdateEventListener {

	public EnversPreCollectionUpdateEventListenerImpl(EnversService enversService) {
		super( enversService );
	}

	@Override
	public void onPreUpdateCollection(PreCollectionUpdateEvent event) {
		final var collection = event.getCollection();
		final var persister = collection.getSession().getFactory().getMappingMetamodel()
				.getCollectionDescriptor( collection.getRole() );
		if ( !persister.isInverse() ) {
			onCollectionAction( event, collection, collectionEntry.getSnapshot(), persister );
		}
		else {
			onCollectionActionInversed( event, collection, collectionEntry.getSnapshot(), persister );
		}
	}
}
