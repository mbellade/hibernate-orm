/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.internal.util;

public final class QuotingHelper {

	private QuotingHelper() { /* static methods only - hide constructor */
	}

	public static String unquoteIdentifier(String text) {
		final int end = text.length() - 1;
		assert text.charAt( 0 ) == '`' && text.charAt( end ) == '`';
		// Unquote a parsed quoted identifier and handle escape sequences
		final StringBuilder sb = new StringBuilder( text.length() - 2 );
		for ( int i = 1; i < end; i++ ) {
			char c = text.charAt( i );
			switch ( c ) {
				case '\\':
					if ( ( i + 1 ) < end ) {
						char nextChar = text.charAt( ++i );
						switch ( nextChar ) {
							case 'b':
								c = '\b';
								break;
							case 't':
								c = '\t';
								break;
							case 'n':
								c = '\n';
								break;
							case 'f':
								c = '\f';
								break;
							case 'r':
								c = '\r';
								break;
							case '\\':
								c = '\\';
								break;
							case '\'':
								c = '\'';
								break;
							case '"':
								c = '"';
								break;
							case '`':
								c = '`';
								break;
							case 'u':
								c = (char) Integer.parseInt( text.substring( i + 1, i + 5 ), 16 );
								i += 4;
								break;
							default:
								sb.append( '\\' );
								c = nextChar;
								break;
						}
					}
					break;
				default:
					break;
			}
			sb.append( c );
		}
		return sb.toString();
	}

	public static String unquoteStringLiteral(String text) {
		return unquoteString( text, false );
	}

	public static String unquoteJavaStringLiteral(String text) {
		return unquoteString( text, true );
	}

	public static String unquoteString(String text, boolean unescape) {
		assert text.length() > 1;
		int start = 0;
		final int end = text.length() - 1;
		final char delimiter = unescape && Character.toLowerCase( text.charAt( start ) ) == 'j' ?
				text.charAt( ++start ) :
				text.charAt( start );
		assert delimiter == text.charAt( end );
		// Unescape the parsed literal and handle escape sequences
		final StringBuilder sb = new StringBuilder( text.length() - ( start + 2 ) );
		for ( int i = start + 1; i < end; i++ ) {
			char c = text.charAt( i );
			switch ( c ) {
				case '\'':
					if ( delimiter == '\'' ) {
						i++;
					}
					break;
				case '"':
					if ( delimiter == '"' ) {
						i++;
					}
					break;
				case '\\':
					if ( unescape && ( i + 1 ) < end ) {
						char nextChar = text.charAt( ++i );
						switch ( nextChar ) {
							case 'b':
								c = '\b';
								break;
							case 't':
								c = '\t';
								break;
							case 'n':
								c = '\n';
								break;
							case 'f':
								c = '\f';
								break;
							case 'r':
								c = '\r';
								break;
							case '\\':
								c = '\\';
								break;
							case '\'':
								c = '\'';
								break;
							case '"':
								c = '"';
								break;
							case '`':
								c = '`';
								break;
							case 'u':
								c = (char) Integer.parseInt( text.substring( i + 1, i + 5 ), 16 );
								i += 4;
								break;
							default:
								sb.append( '\\' );
								c = nextChar;
								break;
						}
					}
					break;
				default:
					break;
			}
			sb.append( c );
		}
		return sb.toString();
	}
}
