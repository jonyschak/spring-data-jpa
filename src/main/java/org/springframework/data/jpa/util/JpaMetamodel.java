/*
 * Copyright 2016-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.util;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.persistence.Embeddable;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.ManagedType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.SingularAttribute;
import javax.persistence.metamodel.Type.PersistenceType;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.util.Lazy;
import org.springframework.data.util.StreamUtils;
import org.springframework.util.Assert;

/**
 * Wrapper around the JPA {@link Metamodel} to be able to apply some fixes against bugs in provider implementations.
 *
 * @author Oliver Gierke
 * @author Mark Paluch
 * @author Sylvère Richard
 */
public class JpaMetamodel {

	private static final Map<Metamodel, JpaMetamodel> CACHE = new ConcurrentHashMap<>(4);
	private static final Set<PersistenceType> ENTITY_OR_MAPPED_SUPERCLASS = EnumSet.of(PersistenceType.ENTITY,
			PersistenceType.MAPPED_SUPERCLASS);

	private final Metamodel metamodel;

	private Lazy<Collection<Class<?>>> managedTypes;
	private Lazy<Collection<Class<?>>> jpaEmbeddables;

	/**
	 * Creates a new {@link JpaMetamodel} for the given JPA {@link Metamodel}.
	 *
	 * @param metamodel must not be {@literal null}.
	 */
	private JpaMetamodel(Metamodel metamodel) {

		Assert.notNull(metamodel, "Metamodel must not be null!");

		this.metamodel = metamodel;

		this.managedTypes = Lazy.of(() -> metamodel.getManagedTypes().stream() //
				.map(ManagedType::getJavaType) //
				.filter(it -> it != null) //
				.collect(StreamUtils.toUnmodifiableSet()));

		this.jpaEmbeddables = Lazy.of(() -> metamodel.getEmbeddables().stream() //
				.map(ManagedType::getJavaType)
				.filter(it -> it != null)
				.filter(it -> AnnotatedElementUtils.isAnnotated(it, Embeddable.class))
				.collect(StreamUtils.toUnmodifiableSet()));
	}

	public static JpaMetamodel of(Metamodel metamodel) {
		return CACHE.computeIfAbsent(metamodel, JpaMetamodel::new);
	}

	/**
	 * Returns whether the given type is managed by the backing JPA {@link Metamodel}.
	 *
	 * @param type must not be {@literal null}.
	 * @return
	 */
	public boolean isJpaManaged(Class<?> type) {

		Assert.notNull(type, "Type must not be null!");

		return managedTypes.get().contains(type);
	}

	/**
	 * Returns whether the attribute of given name and type is the single identifier attribute of the given entity.
	 *
	 * @param entity must not be {@literal null}.
	 * @param name must not be {@literal null}.
	 * @param attributeType must not be {@literal null}.
	 * @return
	 */
	public boolean isSingleIdAttribute(Class<?> entity, String name, Class<?> attributeType) {

		return metamodel.getEntities().stream() //
				.filter(it -> entity.equals(it.getJavaType())) //
				.findFirst() //
				.flatMap(it -> getSingularIdAttribute(it)) //
				.filter(it -> it.getJavaType().equals(attributeType)) //
				.map(it -> it.getName().equals(name)) //
				.orElse(false);
	}

	/**
	 * Returns whether the given type is considered a mapped type, i.e. an actually JPA persisted entity, mapped
	 * superclass or native JPA embeddable.
	 *
	 * @param entity must not be {@literal null}.
	 * @return
	 */
	public boolean isMappedType(Class<?> entity) {

		Assert.notNull(entity, "Type must not be null!");

		if (!isJpaManaged(entity)) {
			return false;
		}

		ManagedType<?> managedType = metamodel.managedType(entity);

		return !managedType.getPersistenceType().equals(PersistenceType.EMBEDDABLE)
				|| jpaEmbeddables.get().contains(entity);
	}

	/**
	 * Wipes the static cache of {@link Metamodel} to {@link JpaMetamodel}.
	 */
	static void clear() {
		CACHE.clear();
	}

	/**
	 * Returns the {@link SingularAttribute} representing the identifier of the given {@link EntityType} if it contains a
	 * singular one.
	 *
	 * @param entityType must not be {@literal null}.
	 * @return
	 */
	private static Optional<? extends SingularAttribute<?, ?>> getSingularIdAttribute(EntityType<?> entityType) {

		if (!entityType.hasSingleIdAttribute()) {
			return Optional.empty();
		}

		return entityType.getSingularAttributes().stream() //
				.filter(SingularAttribute::isId) //
				.findFirst();
	}
}
