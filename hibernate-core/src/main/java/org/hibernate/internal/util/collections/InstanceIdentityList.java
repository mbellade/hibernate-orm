/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.internal.util.collections;

import java.lang.reflect.Array;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Objects;

import org.hibernate.engine.spi.InstanceIdentity;
import org.hibernate.internal.build.AllowReflection;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A list-like structure that uses instance identity to index its elements and stores them in a paged array.
 *
 * @param <E> The type of elements maintained by this list
 */
public class InstanceIdentityList<E extends InstanceIdentity> extends AbstractPagedArray<E> implements Iterable<E> {
	private int size;

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public boolean add(E e) {
		return add( e.$$_hibernate_getInstanceId(), e );
	}

	public boolean add(int instanceId, E e) {
		Objects.requireNonNull( e, "Element cannot be null" );

		final int index = instanceId - 1;
		if ( index < 0 ) {
			throw new IllegalArgumentException( "Instance ID must be a positive value" );
		}

		final E old = super.set( index, e );
		if ( old == null ) {
			size++;
		}
		return true;
	}

	public boolean remove(Object o) {
		if ( o instanceof InstanceIdentity instance ) {
			return remove( instance.$$_hibernate_getInstanceId(), instance ) != null;
		}
		throw new ClassCastException( "Provided element does not support instance identity" );
	}

	public E remove(int instanceId, Object o) {
		if ( instanceId <= 0 ) {
			return null;
		}

		final int index = instanceId - 1;
		final int pageIndex = toPageIndex( index );
		final Page<E> page = getPage( pageIndex );
		if ( page != null ) {
			final int offset = toPageOffset( index );
			final E old = page.set( offset, null );
			if ( old == o ) {
				size--;
				clearEmptyPage( pageIndex, page );
				return old;
			}
			else if ( old != null ) {
				throw new ConcurrentModificationException(
						"Found a different instance corresponding to instanceId [" + instanceId +
								"], this might indicate a concurrent access to this persistence context."
				);
			}
		}
		return null;
	}

	@Override
	public void clear() {
		super.clear();
		size = 0;
	}

	private final class Itr extends PagedArrayIterator<E> {
		@Override
		public E next() {
			return get( nextIndex() );
		}
	}

	@Override
	public Iterator<E> iterator() {
		return new Itr();
	}

	public Object[] toArray() {
		return toArray( new Object[0] );
	}

	@AllowReflection
	@SuppressWarnings("unchecked")
	public <T> @NonNull T @NonNull [] toArray(T[] a) {
		int size = size();
		if ( a.length < size ) {
			a = (T[]) Array.newInstance( a.getClass().getComponentType(), size );
		}
		int i = 0;
		for ( Page<E> page : elementPages ) {
			if ( page != null ) {
				for ( int j = 0; j <= page.lastNotEmptyOffset; j++ ) {
					final E entry;
					if ( ( entry = page.get( j ) ) != null ) {
						a[i++] = (T) entry;
					}
				}
			}
		}
		// fewer elements than expected or concurrent modification from other thread detected
		if ( i < size ) {
			throw new ConcurrentModificationException();
		}
		if ( i < a.length ) {
			a[i] = null;
		}
		return a;
	}
}
