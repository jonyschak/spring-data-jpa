/*
 * Copyright 2008-2021 the original author or authors.
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
package org.springframework.data.jpa.repository.support;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.convert.QueryByExamplePredicateBuilder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.provider.PersistenceProvider;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.jpa.repository.support.QueryHints.NoHints;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.data.util.ProxyUtils;
import org.springframework.data.util.Streamable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.NoResultException;
import javax.persistence.Parameter;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.ParameterExpression;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.springframework.data.jpa.repository.query.QueryUtils.*;

/**
 * Default implementation of the {@link org.springframework.data.repository.CrudRepository} interface. This will offer
 * you a more sophisticated interface than the plain {@link EntityManager} .
 *
 * @param <T> the type of the entity to handle
 * @param <ID> the type of the entity's identifier
 * @author Oliver Gierke
 * @author Eberhard Wolff
 * @author Thomas Darimont
 * @author Mark Paluch
 * @author Christoph Strobl
 * @author Stefan Fussenegger
 * @author Jens Schauder
 * @author David Madden
 * @author Moritz Becker
 * @author Sander Krabbenborg
 * @author Jesse Wouters
 * @author Greg Turnquist
 * @author Yanming Zhou
 * @author Ernst-Jan van der Laan
 */
@Repository
@Transactional(readOnly = true)
public class SimpleJpaRepository<T, ID> implements JpaRepositoryImplementation<T, ID> {

	private static final String ID_MUST_NOT_BE_NULL = "The given id must not be null!";

	private final JpaEntityInformation<T, ?> entityInformation;
	private final EntityManager em;
	private final PersistenceProvider provider;

	private @Nullable CrudMethodMetadata metadata;
	private EscapeCharacter escapeCharacter = EscapeCharacter.DEFAULT;

	/**
	 * Creates a new {@link SimpleJpaRepository} to manage objects of the given {@link JpaEntityInformation}.
	 *
	 * @param entityInformation must not be {@literal null}.
	 * @param entityManager must not be {@literal null}.
	 */
	public SimpleJpaRepository(JpaEntityInformation<T, ?> entityInformation, EntityManager entityManager) {

		Assert.notNull(entityInformation, "JpaEntityInformation must not be null!");
		Assert.notNull(entityManager, "EntityManager must not be null!");

		this.entityInformation = entityInformation;
		this.em = entityManager;
		this.provider = PersistenceProvider.fromEntityManager(entityManager);
	}

	/**
	 * Creates a new {@link SimpleJpaRepository} to manage objects of the given domain type.
	 *
	 * @param domainClass must not be {@literal null}.
	 * @param em must not be {@literal null}.
	 */
	public SimpleJpaRepository(Class<T> domainClass, EntityManager em) {
		this(JpaEntityInformationSupport.getEntityInformation(domainClass, em), em);
	}

	/**
	 * Configures a custom {@link CrudMethodMetadata} to be used to detect {@link LockModeType}s and query hints to be
	 * applied to queries.
	 *
	 * @param crudMethodMetadata
	 */
	@Override
	public void setRepositoryMethodMetadata(CrudMethodMetadata crudMethodMetadata) {
		this.metadata = crudMethodMetadata;
	}

	@Override
	public void setEscapeCharacter(EscapeCharacter escapeCharacter) {
		this.escapeCharacter = escapeCharacter;
	}

	@Nullable
	protected CrudMethodMetadata getRepositoryMethodMetadata() {
		return metadata;
	}

	protected Class<T> getDomainClass() {
		return entityInformation.getJavaType();
	}

	private String getDeleteAllQueryString() {
		return getQueryString(DELETE_ALL_QUERY_STRING, entityInformation.getEntityName());
	}

	private String getCountQueryString() {

		String countQuery = String.format(COUNT_QUERY_STRING, provider.getCountQueryPlaceholder(), "%s");
		return getQueryString(countQuery, entityInformation.getEntityName());
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.io.Serializable)
	 */
	@Transactional
	@Override
	public void deleteById(ID id) {

		Assert.notNull(id, ID_MUST_NOT_BE_NULL);

		delete(findById(id).orElseThrow(() -> new EmptyResultDataAccessException(
				String.format("No %s entity with id %s exists!", entityInformation.getJavaType(), id), 1)));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.lang.Object)
	 */
	@Override
	@Transactional
	@SuppressWarnings("unchecked")
	public void delete(T entity) {

		Assert.notNull(entity, "Entity must not be null!");

		if (entityInformation.isNew(entity)) {
			return;
		}

		Class<?> type = ProxyUtils.getUserClass(entity);

		T existing = (T) em.find(type, entityInformation.getId(entity));

		// if the entity to be deleted doesn't exist, delete is a NOOP
		if (existing == null) {
			return;
		}

		em.remove(em.contains(entity) ? entity : em.merge(entity));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#deleteAllById(java.lang.Iterable)
	 */
	@Override
	@Transactional
	public void deleteAllById(Iterable<? extends ID> ids) {

		Assert.notNull(ids, "Ids must not be null!");

		for (ID id : ids) {
			deleteById(id);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#deleteAllByIdInBatch(java.lang.Iterable)
	 */
	@Override
	@Transactional
	public void deleteAllByIdInBatch(Iterable<ID> ids) {

		Assert.notNull(ids, "Ids must not be null!");

		if (!ids.iterator().hasNext()) {
			return;
		}

		if (entityInformation.hasCompositeId()) {

			List<T> entities = new ArrayList<>();
			// generate entity (proxies) without accessing the database.
			ids.forEach(id -> entities.add(getReferenceById(id)));
			deleteAllInBatch(entities);
		} else {

			String queryString = String.format(DELETE_ALL_QUERY_BY_ID_STRING, entityInformation.getEntityName(),
					entityInformation.getIdAttribute().getName());

			Query query = em.createQuery(queryString);
			query.setParameter("ids", ids);

			query.executeUpdate();
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#delete(java.lang.Iterable)
	 */
	@Override
	@Transactional
	public void deleteAll(Iterable<? extends T> entities) {

		Assert.notNull(entities, "Entities must not be null!");

		for (T entity : entities) {
			delete(entity);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#deleteInBatch(java.lang.Iterable)
	 */
	@Override
	@Transactional
	public void deleteAllInBatch(Iterable<T> entities) {

		Assert.notNull(entities, "Entities must not be null!");

		if (!entities.iterator().hasNext()) {
			return;
		}

		applyAndBind(getQueryString(DELETE_ALL_QUERY_STRING, entityInformation.getEntityName()), entities, em)
				.executeUpdate();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.Repository#deleteAll()
	 */
	@Override
	@Transactional
	public void deleteAll() {

		for (T element : findAll()) {
			delete(element);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#deleteAllInBatch()
	 */
	@Override
	@Transactional
	public void deleteAllInBatch() {
		em.createQuery(getDeleteAllQueryString()).executeUpdate();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#findById(java.io.Serializable)
	 */
	@Override
	public Optional<T> findById(ID id) {

		Assert.notNull(id, ID_MUST_NOT_BE_NULL);

		Class<T> domainType = getDomainClass();

		if (metadata == null) {
			return Optional.ofNullable(em.find(domainType, id));
		}

		LockModeType type = metadata.getLockModeType();

		Map<String, Object> hints = new HashMap<>();
		getQueryHints().withFetchGraphs(em).forEach(hints::put);

		return Optional.ofNullable(type == null ? em.find(domainType, id, hints) : em.find(domainType, id, type, hints));
	}

	/**
	 * Returns {@link QueryHints} with the query hints based on the current {@link CrudMethodMetadata} and potential
	 * {@link EntityGraph} information.
	 *
	 */
	protected QueryHints getQueryHints() {
		return metadata == null ? NoHints.INSTANCE : DefaultQueryHints.of(entityInformation, metadata);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#getOne(java.io.Serializable)
	 */
	@Deprecated
	@Override
	public T getOne(ID id) {
		return getReferenceById(id);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#getById(java.io.Serializable)
	 */
	@Deprecated
	@Override
	public T getById(ID id) {
		return getReferenceById(id);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#getReferenceById(java.io.Serializable)
	 */
	@Override
	public T getReferenceById(ID id) {

		Assert.notNull(id, ID_MUST_NOT_BE_NULL);
		return em.getReference(getDomainClass(), id);
	}

	/*
	* (non-Javadoc)
	* @see org.springframework.data.repository.CrudRepository#existsById(java.io.Serializable)
	*/
	@Override
	public boolean existsById(ID id) {

		Assert.notNull(id, ID_MUST_NOT_BE_NULL);

		if (entityInformation.getIdAttribute() == null) {
			return findById(id).isPresent();
		}

		String placeholder = provider.getCountQueryPlaceholder();
		String entityName = entityInformation.getEntityName();
		Iterable<String> idAttributeNames = entityInformation.getIdAttributeNames();
		String existsQuery = QueryUtils.getExistsQueryString(entityName, placeholder, idAttributeNames);

		TypedQuery<Long> query = em.createQuery(existsQuery, Long.class);

		if (!entityInformation.hasCompositeId()) {
			query.setParameter(idAttributeNames.iterator().next(), id);
			return query.getSingleResult() == 1L;
		}

		for (String idAttributeName : idAttributeNames) {

			Object idAttributeValue = entityInformation.getCompositeIdAttributeValue(id, idAttributeName);

			boolean complexIdParameterValueDiscovered = idAttributeValue != null
					&& !query.getParameter(idAttributeName).getParameterType().isAssignableFrom(idAttributeValue.getClass());

			if (complexIdParameterValueDiscovered) {

				// fall-back to findById(id) which does the proper mapping for the parameter.
				return findById(id).isPresent();
			}

			query.setParameter(idAttributeName, idAttributeValue);
		}

		return query.getSingleResult() == 1L;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#findAll()
	 */
	@Override
	public List<T> findAll() {
		return getQuery(null, Sort.unsorted()).getResultList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#findAll(java.lang.Iterable)
	 */
	@Override
	public List<T> findAllById(Iterable<ID> ids) {

		Assert.notNull(ids, "Ids must not be null!");

		if (!ids.iterator().hasNext()) {
			return Collections.emptyList();
		}

		if (entityInformation.hasCompositeId()) {

			List<T> results = new ArrayList<>();

			for (ID id : ids) {
				findById(id).ifPresent(results::add);
			}

			return results;
		}

		Collection<ID> idCollection = Streamable.of(ids).toList();

		ByIdsSpecification<T> specification = new ByIdsSpecification<>(entityInformation);
		TypedQuery<T> query = getQuery(specification, Sort.unsorted());

		return query.setParameter(specification.parameter, idCollection).getResultList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#findAll(org.springframework.data.domain.Sort)
	 */
	@Override
	public List<T> findAll(Sort sort) {
		return getQuery(null, sort).getResultList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.PagingAndSortingRepository#findAll(org.springframework.data.domain.Pageable)
	 */
	@Override
	public Page<T> findAll(Pageable pageable) {

		if (isUnpaged(pageable)) {
			return new PageImpl<>(findAll());
		}

		return findAll((Specification<T>) null, pageable);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaSpecificationExecutor#findOne(org.springframework.data.jpa.domain.Specification)
	 */
	@Override
	public Optional<T> findOne(@Nullable Specification<T> spec) {

		try {
			return Optional.of(getQuery(spec, Sort.unsorted()).getSingleResult());
		} catch (NoResultException e) {
			return Optional.empty();
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaSpecificationExecutor#findAll(org.springframework.data.jpa.domain.Specification)
	 */
	@Override
	public List<T> findAll(@Nullable Specification<T> spec) {
		return getQuery(spec, Sort.unsorted()).getResultList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaSpecificationExecutor#findAll(org.springframework.data.jpa.domain.Specification, org.springframework.data.domain.Pageable)
	 */
	@Override
	public Page<T> findAll(@Nullable Specification<T> spec, Pageable pageable) {

		TypedQuery<T> query = getQuery(spec, pageable);
		return isUnpaged(pageable) ? new PageImpl<>(query.getResultList())
				: readPage(query, getDomainClass(), pageable, spec);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaSpecificationExecutor#findAll(org.springframework.data.jpa.domain.Specification, org.springframework.data.domain.Sort)
	 */
	@Override
	public List<T> findAll(@Nullable Specification<T> spec, Sort sort) {
		return getQuery(spec, sort).getResultList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryByExampleExecutor#findOne(org.springframework.data.domain.Example)
	 */
	@Override
	public <S extends T> Optional<S> findOne(Example<S> example) {

		try {
			return Optional
					.of(getQuery(new ExampleSpecification<>(example, escapeCharacter), example.getProbeType(), Sort.unsorted())
							.getSingleResult());
		} catch (NoResultException e) {
			return Optional.empty();
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryByExampleExecutor#count(org.springframework.data.domain.Example)
	 */
	@Override
	public <S extends T> long count(Example<S> example) {
		return executeCountQuery(
				getCountQuery(new ExampleSpecification<>(example, escapeCharacter), example.getProbeType()));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryByExampleExecutor#exists(org.springframework.data.domain.Example)
	 */
	@Override
	public <S extends T> boolean exists(Example<S> example) {

		Specification<S> spec = new ExampleSpecification<>(example, this.escapeCharacter);
		CriteriaQuery<Integer> cq = this.em.getCriteriaBuilder().createQuery(Integer.class);
		cq.select(this.em.getCriteriaBuilder().literal(1));
		applySpecificationToCriteria(spec, example.getProbeType(), cq);
		TypedQuery<Integer> query = applyRepositoryMethodMetadata(this.em.createQuery(cq));
		return query.setMaxResults(1).getResultList().size() == 1;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryByExampleExecutor#findAll(org.springframework.data.domain.Example)
	 */
	@Override
	public <S extends T> List<S> findAll(Example<S> example) {
		return getQuery(new ExampleSpecification<>(example, escapeCharacter), example.getProbeType(), Sort.unsorted())
				.getResultList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryByExampleExecutor#findAll(org.springframework.data.domain.Example, org.springframework.data.domain.Sort)
	 */
	@Override
	public <S extends T> List<S> findAll(Example<S> example, Sort sort) {
		return getQuery(new ExampleSpecification<>(example, escapeCharacter), example.getProbeType(), sort)
				.getResultList();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryByExampleExecutor#findAll(org.springframework.data.domain.Example, org.springframework.data.domain.Pageable)
	 */
	@Override
	public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {

		ExampleSpecification<S> spec = new ExampleSpecification<>(example, escapeCharacter);
		Class<S> probeType = example.getProbeType();
		TypedQuery<S> query = getQuery(new ExampleSpecification<>(example, escapeCharacter), probeType, pageable);

		return isUnpaged(pageable) ? new PageImpl<>(query.getResultList()) : readPage(query, probeType, pageable, spec);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryByExampleExecutor#findBy(org.springframework.data.domain.Example, java.util.function.Function)
	 */
	@Override
	public <S extends T, R> R findBy(Example<S> example, Function<FetchableFluentQuery<S>, R> queryFunction) {

		Assert.notNull(example, "Sample must not be null!");
		Assert.notNull(queryFunction, "Query function must not be null!");

		Function<Sort, TypedQuery<S>> finder = sort -> {

			ExampleSpecification<S> spec = new ExampleSpecification<>(example, escapeCharacter);
			Class<S> probeType = example.getProbeType();

			return getQuery(spec, probeType, sort);
		};

		FetchableFluentQuery<S> fluentQuery = new FetchableFluentQueryByExample<>(example, finder, this::count,
				this::exists, this.em, this.escapeCharacter);

		return queryFunction.apply(fluentQuery);
	}

	/*
	* (non-Javadoc)
	* @see org.springframework.data.repository.CrudRepository#count()
	*/
	@Override
	public long count() {
		return em.createQuery(getCountQueryString(), Long.class).getSingleResult();
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaSpecificationExecutor#count(org.springframework.data.jpa.domain.Specification)
	 */
	@Override
	public long count(@Nullable Specification<T> spec) {
		return executeCountQuery(getCountQuery(spec, getDomainClass()));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.CrudRepository#save(java.lang.Object)
	 */
	@Transactional
	@Override
	public <S extends T> S save(S entity) {

		Assert.notNull(entity, "Entity must not be null.");

		if (entityInformation.isNew(entity)) {
			em.persist(entity);
			return entity;
		} else {
			return em.merge(entity);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#saveAndFlush(java.lang.Object)
	 */
	@Transactional
	@Override
	public <S extends T> S saveAndFlush(S entity) {

		S result = save(entity);
		flush();

		return result;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#save(java.lang.Iterable)
	 */
	@Transactional
	@Override
	public <S extends T> List<S> saveAll(Iterable<S> entities) {

		Assert.notNull(entities, "Entities must not be null!");

		List<S> result = new ArrayList<>();

		for (S entity : entities) {
			result.add(save(entity));
		}

		return result;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#saveAllAndFlush(java.lang.Iterable)
	 */
	@Transactional
	@Override
	public <S extends T> List<S> saveAllAndFlush(Iterable<S> entities) {

		List<S> result = saveAll(entities);
		flush();

		return result;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.JpaRepository#flush()
	 */
	@Transactional
	@Override
	public void flush() {
		em.flush();
	}

	/**
	 * Reads the given {@link TypedQuery} into a {@link Page} applying the given {@link Pageable} and
	 * {@link Specification}.
	 *
	 * @param query must not be {@literal null}.
	 * @param spec can be {@literal null}.
	 * @param pageable must not be {@literal null}.
	 * @deprecated use {@link #readPage(TypedQuery, Class, Pageable, Specification)} instead
	 */
	@Deprecated
	protected Page<T> readPage(TypedQuery<T> query, Pageable pageable, @Nullable Specification<T> spec) {
		return readPage(query, getDomainClass(), pageable, spec);
	}

	/**
	 * Reads the given {@link TypedQuery} into a {@link Page} applying the given {@link Pageable} and
	 * {@link Specification}.
	 *
	 * @param query must not be {@literal null}.
	 * @param domainClass must not be {@literal null}.
	 * @param spec can be {@literal null}.
	 * @param pageable can be {@literal null}.
	 */
	protected <S extends T> Page<S> readPage(TypedQuery<S> query, final Class<S> domainClass, Pageable pageable,
			@Nullable Specification<S> spec) {

		if (pageable.isPaged()) {
			query.setFirstResult((int) pageable.getOffset());
			query.setMaxResults(pageable.getPageSize());
		}

		return PageableExecutionUtils.getPage(query.getResultList(), pageable,
				() -> executeCountQuery(getCountQuery(spec, domainClass)));
	}

	/**
	 * Creates a new {@link TypedQuery} from the given {@link Specification}.
	 *
	 * @param spec can be {@literal null}.
	 * @param pageable must not be {@literal null}.
	 */
	protected TypedQuery<T> getQuery(@Nullable Specification<T> spec, Pageable pageable) {

		Sort sort = pageable.isPaged() ? pageable.getSort() : Sort.unsorted();
		return getQuery(spec, getDomainClass(), sort);
	}

	/**
	 * Creates a new {@link TypedQuery} from the given {@link Specification}.
	 *
	 * @param spec can be {@literal null}.
	 * @param domainClass must not be {@literal null}.
	 * @param pageable must not be {@literal null}.
	 */
	protected <S extends T> TypedQuery<S> getQuery(@Nullable Specification<S> spec, Class<S> domainClass,
			Pageable pageable) {

		Sort sort = pageable.isPaged() ? pageable.getSort() : Sort.unsorted();
		return getQuery(spec, domainClass, sort);
	}

	/**
	 * Creates a {@link TypedQuery} for the given {@link Specification} and {@link Sort}.
	 *
	 * @param spec can be {@literal null}.
	 * @param sort must not be {@literal null}.
	 */
	protected TypedQuery<T> getQuery(@Nullable Specification<T> spec, Sort sort) {
		return getQuery(spec, getDomainClass(), sort);
	}

	/**
	 * Creates a {@link TypedQuery} for the given {@link Specification} and {@link Sort}.
	 *
	 * @param spec can be {@literal null}.
	 * @param domainClass must not be {@literal null}.
	 * @param sort must not be {@literal null}.
	 */
	protected <S extends T> TypedQuery<S> getQuery(@Nullable Specification<S> spec, Class<S> domainClass, Sort sort) {

		CriteriaBuilder builder = em.getCriteriaBuilder();
		CriteriaQuery<S> query = builder.createQuery(domainClass);

		Root<S> root = applySpecificationToCriteria(spec, domainClass, query);
		query.select(root);

		if (sort.isSorted()) {
			query.orderBy(toOrders(sort, root, builder));
		}

		return applyRepositoryMethodMetadata(em.createQuery(query));
	}

	/**
	 * Creates a new count query for the given {@link Specification}.
	 *
	 * @param spec can be {@literal null}.
	 * @deprecated override {@link #getCountQuery(Specification, Class)} instead
	 */
	@Deprecated
	protected TypedQuery<Long> getCountQuery(@Nullable Specification<T> spec) {
		return getCountQuery(spec, getDomainClass());
	}

	/**
	 * Creates a new count query for the given {@link Specification}.
	 *
	 * @param spec can be {@literal null}.
	 * @param domainClass must not be {@literal null}.
	 */
	protected <S extends T> TypedQuery<Long> getCountQuery(@Nullable Specification<S> spec, Class<S> domainClass) {

		CriteriaBuilder builder = em.getCriteriaBuilder();
		CriteriaQuery<Long> query = builder.createQuery(Long.class);

		Root<S> root = applySpecificationToCriteria(spec, domainClass, query);

		if (query.isDistinct()) {
			query.select(builder.countDistinct(root));
		} else {
			query.select(builder.count(root));
		}

		// Remove all Orders the Specifications might have applied
		query.orderBy(Collections.emptyList());

		return em.createQuery(query);
	}

	/**
	 * Applies the given {@link Specification} to the given {@link CriteriaQuery}.
	 *
	 * @param spec can be {@literal null}.
	 * @param domainClass must not be {@literal null}.
	 * @param query must not be {@literal null}.
	 */
	private <S, U extends T> Root<U> applySpecificationToCriteria(@Nullable Specification<U> spec, Class<U> domainClass,
			CriteriaQuery<S> query) {

		Assert.notNull(domainClass, "Domain class must not be null!");
		Assert.notNull(query, "CriteriaQuery must not be null!");

		Root<U> root = query.from(domainClass);

		if (spec == null) {
			return root;
		}

		CriteriaBuilder builder = em.getCriteriaBuilder();
		Predicate predicate = spec.toPredicate(root, query, builder);

		if (predicate != null) {
			query.where(predicate);
		}

		return root;
	}

	private <S> TypedQuery<S> applyRepositoryMethodMetadata(TypedQuery<S> query) {

		if (metadata == null) {
			return query;
		}

		LockModeType type = metadata.getLockModeType();
		TypedQuery<S> toReturn = type == null ? query : query.setLockMode(type);

		applyQueryHints(toReturn);

		return toReturn;
	}

	private void applyQueryHints(Query query) {
		getQueryHints().withFetchGraphs(em).forEach(query::setHint);
	}

	/**
	 * Executes a count query and transparently sums up all values returned.
	 *
	 * @param query must not be {@literal null}.
	 */
	private static long executeCountQuery(TypedQuery<Long> query) {

		Assert.notNull(query, "TypedQuery must not be null!");

		List<Long> totals = query.getResultList();
		long total = 0L;

		for (Long element : totals) {
			total += element == null ? 0 : element;
		}

		return total;
	}

	private static boolean isUnpaged(Pageable pageable) {
		return pageable.isUnpaged();
	}

	/**
	 * Specification that gives access to the {@link Parameter} instance used to bind the ids for
	 * {@link SimpleJpaRepository#findAllById(Iterable)}. Workaround for OpenJPA not binding collections to in-clauses
	 * correctly when using by-name binding.
	 *
	 * @author Oliver Gierke
	 * @see <a href="https://issues.apache.org/jira/browse/OPENJPA-2018?focusedCommentId=13924055">OPENJPA-2018</a>
	 */
	@SuppressWarnings("rawtypes")
	private static final class ByIdsSpecification<T> implements Specification<T> {

		private static final long serialVersionUID = 1L;

		private final JpaEntityInformation<T, ?> entityInformation;

		@Nullable ParameterExpression<Collection<?>> parameter;

		ByIdsSpecification(JpaEntityInformation<T, ?> entityInformation) {
			this.entityInformation = entityInformation;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.jpa.domain.Specification#toPredicate(javax.persistence.criteria.Root, javax.persistence.criteria.CriteriaQuery, javax.persistence.criteria.CriteriaBuilder)
		 */
		@Override
		public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {

			Path<?> path = root.get(entityInformation.getIdAttribute());
			parameter = (ParameterExpression<Collection<?>>) (ParameterExpression) cb.parameter(Collection.class);
			return path.in(parameter);
		}
	}

	/**
	 * {@link Specification} that gives access to the {@link Predicate} instance representing the values contained in the
	 * {@link Example}.
	 *
	 * @param <T>
	 * @author Christoph Strobl
	 * @since 1.10
	 */
	private static class ExampleSpecification<T> implements Specification<T> {

		private static final long serialVersionUID = 1L;

		private final Example<T> example;
		private final EscapeCharacter escapeCharacter;

		/**
		 * Creates new {@link ExampleSpecification}.
		 *
		 * @param example the example to base the specification of. Must not be {@literal null}.
		 * @param escapeCharacter the escape character to use for like expressions. Must not be {@literal null}.
		 */
		ExampleSpecification(Example<T> example, EscapeCharacter escapeCharacter) {

			Assert.notNull(example, "Example must not be null!");
			Assert.notNull(escapeCharacter, "EscapeCharacter must not be null!");

			this.example = example;
			this.escapeCharacter = escapeCharacter;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.jpa.domain.Specification#toPredicate(javax.persistence.criteria.Root, javax.persistence.criteria.CriteriaQuery, javax.persistence.criteria.CriteriaBuilder)
		 */
		@Override
		public Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
			return QueryByExamplePredicateBuilder.getPredicate(root, cb, example, escapeCharacter);
		}
	}
}
