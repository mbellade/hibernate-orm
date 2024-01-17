/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.bytecode.internal;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

import org.hibernate.Internal;
import org.hibernate.boot.registry.StandardServiceInitiator;
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.service.spi.ServiceRegistryImplementor;

import org.jboss.logging.Logger;

import static org.hibernate.internal.util.NullnessUtil.castNonNull;

public final class BytecodeProviderInitiator implements StandardServiceInitiator<BytecodeProvider> {

	/**
	 * @deprecated Register a {@link BytecodeProvider} through Java {@linkplain java.util.ServiceLoader services}.
	 */
	@Deprecated( forRemoval = true )
	public static final String BYTECODE_PROVIDER_NAME_BYTEBUDDY = "bytebuddy";

	/**
	 * @deprecated Register a {@link BytecodeProvider} through Java {@linkplain java.util.ServiceLoader services}.
	 */
	@Deprecated( forRemoval = true )
	public static final String BYTECODE_PROVIDER_NAME_NONE = "none";

	/**
	 * @deprecated Deprecated with no replacement
	 */
	@Deprecated( forRemoval = true )
	public static final String BYTECODE_PROVIDER_NAME_DEFAULT = BYTECODE_PROVIDER_NAME_BYTEBUDDY;

	/**
	 * Singleton access
	 */
	public static final StandardServiceInitiator<BytecodeProvider> INSTANCE = new BytecodeProviderInitiator();

	@Override
	public BytecodeProvider initiateService(
			Map<String, Object> configurationValues,
			ServiceRegistryImplementor registry) {
		final ClassLoaderService classLoaderService = castNonNull( registry.getService( ClassLoaderService.class ) );
		final Collection<BytecodeProvider> bytecodeProviders = classLoaderService.loadJavaServices( BytecodeProvider.class );
		return getBytecodeProvider( bytecodeProviders );
	}

	public static BytecodeProvider getBytecodeProvider(Iterable<BytecodeProvider> bytecodeProviders) {
		final Iterator<BytecodeProvider> iterator = bytecodeProviders.iterator();
		if ( !iterator.hasNext() ) {
			// If no BytecodeProvider service is available, default to the "no-op" enhancer
			return new org.hibernate.bytecode.internal.none.BytecodeProviderImpl();
		}

		BytecodeProvider provider = null;
		while ( iterator.hasNext() ) {
			final BytecodeProvider next = iterator.next();
			if ( provider == null ) {
				provider = next;
			}
			else if ( provider instanceof org.hibernate.bytecode.internal.none.BytecodeProviderImpl ) {
				provider = next;
			}
			else if ( !( next instanceof org.hibernate.bytecode.internal.none.BytecodeProviderImpl ) ) {
				throw new IllegalStateException( "Found multiple BytecodeProvider service registrations and cannot determine which one to use" );
			}
		}
		return provider;
	}

	@Override
	public Class<BytecodeProvider> getServiceInitiated() {
		return BytecodeProvider.class;
	}

	@Internal
	public static BytecodeProvider buildDefaultBytecodeProvider() {
		return new org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl();
	}

	@Internal
	@Deprecated( forRemoval = true )
	public static BytecodeProvider buildBytecodeProvider(String providerName) {
		CoreMessageLogger LOG = Logger.getMessageLogger( CoreMessageLogger.class, BytecodeProviderInitiator.class.getName() );
		LOG.bytecodeProvider( providerName );

		if ( BYTECODE_PROVIDER_NAME_NONE.equals( providerName ) ) {
			return new org.hibernate.bytecode.internal.none.BytecodeProviderImpl();
		}
		if ( BYTECODE_PROVIDER_NAME_BYTEBUDDY.equals( providerName ) ) {
			return new org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl();
		}

		// There is no need to support plugging in a custom BytecodeProvider via FQCN
		// as it's possible to plug a custom BytecodeProviderInitiator into the bootstrap.
		//
		// This also allows integrators to inject a BytecodeProvider instance which has some
		// state: particularly useful to inject proxy definitions which have been prepared in
		// advance.
		// See also https://hibernate.atlassian.net/browse/HHH-13804 and how this was solved in
		// Quarkus.

		LOG.unknownBytecodeProvider( providerName, BYTECODE_PROVIDER_NAME_DEFAULT );
		return new org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl();
	}

}
