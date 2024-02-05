/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.jpa.internal.enhance;

import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import org.hibernate.bytecode.enhance.spi.EnhancementContext;
import org.hibernate.bytecode.enhance.spi.EnhancementContextWrapper;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.internal.BytecodeProviderInitiator;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.bytecode.spi.ClassTransformer;

import jakarta.persistence.spi.TransformerException;

/**
 * @author Steve Ebersole
 * @author Luis Barreiro
 */
public class EnhancingClassTransformerImpl implements ClassTransformer {

	private final EnhancementContext enhancementContext;
	private final BytecodeProvider bytecodeProvider;
	private final ReentrantLock lock = new ReentrantLock();
	private volatile WeakReference<Entry> entryReference;

	public EnhancingClassTransformerImpl(EnhancementContext enhancementContext) {
		Objects.requireNonNull( enhancementContext );
		this.enhancementContext = enhancementContext;
		this.bytecodeProvider = BytecodeProviderInitiator.buildDefaultByteBuddyProvider();
	}

	public EnhancingClassTransformerImpl(EnhancementContext enhancementContext, ClassLoader classLoader) {
		Objects.requireNonNull( enhancementContext );
		this.enhancementContext = enhancementContext;
		this.bytecodeProvider = BytecodeProviderInitiator.buildDefaultBytecodeProvider( classLoader );
	}

	@Override
	public byte[] transform(
			ClassLoader loader,
			String className,
			Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain,
			byte[] classfileBuffer)  throws TransformerException {

		try {
			return getEnhancer( loader ).enhance( className, classfileBuffer );
		}
		catch (final Exception e) {
			throw new TransformerException( "Error performing enhancement of " + className, e );
		}
		finally {
			bytecodeProvider.resetCaches();
		}
	}

	@Override
	public void discoverTypes(ClassLoader loader, String className) {
		getEnhancer( loader ).discoverTypes( className, null );
	}

	private Enhancer getEnhancer(ClassLoader loader) {
		Entry enhancerEntry = getEnhancerEntry( entryReference, loader );
		if ( enhancerEntry == null ) {
			lock.lock();
			try {
				enhancerEntry = getEnhancerEntry( entryReference, loader );
				if ( enhancerEntry == null ) {
					enhancerEntry = new Entry( loader, createEnhancer( loader ) );
					entryReference = new WeakReference<>( enhancerEntry );
				}
			}
			finally {
				lock.unlock();
			}
		}
		return enhancerEntry.enhancer;
	}

	private static Entry getEnhancerEntry(WeakReference<Entry> weakReference, ClassLoader loader) {
		if ( weakReference == null ) {
			return null;
		}
		final Entry entry = weakReference.get();
		if ( entry == null || entry.classLoader != loader ) {
			return null;
		}
		return entry;
	}

	private Enhancer createEnhancer(ClassLoader loader) {
		return bytecodeProvider.getEnhancer( new EnhancementContextWrapper( enhancementContext, loader ) );
	}

	private static class Entry {
		final ClassLoader classLoader;
		final Enhancer enhancer;

		public Entry(ClassLoader classLoader, Enhancer enhancer) {
			this.classLoader = classLoader;
			this.enhancer = enhancer;
		}
	}
}
