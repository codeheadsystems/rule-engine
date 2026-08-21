/**
 * A {@link com.codeheadsystems.rules.schema.FactSchemas} backed by JSON Schema (spec §2.3).
 *
 * <p>Named for the library rather than for the feature, because the feature's name is taken: the
 * {@code -core} interface is {@code FactSchemas}, and {@code com.networknt.schema} ships a class of
 * its own literally called {@code SchemaRegistry}. Keeping the implementation in a package that
 * says which library it wraps means neither name has to be qualified at its use site.
 */
package com.codeheadsystems.rules.schema.networknt;
