/*
 * Copyright 2013-2022 the original author or authors.
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
package org.springframework.data.jpa.repository.query;

import static java.util.regex.Pattern.*;
import static org.springframework.util.ObjectUtils.*;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.repository.query.SpelQueryContext;
import org.springframework.data.repository.query.SpelQueryContext.SpelExtractor;
import org.springframework.data.repository.query.parser.Part.Type;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Encapsulation of a JPA query String. Offers access to parameters as bindings. The internal query String is cleaned
 * from decorated parameters like {@literal %:lastname%} and the matching bindings take care of applying the decorations
 * in the {@link ParameterBinding#prepare(Object)} method. Note that this class also handles replacing SpEL expressions
 * with synthetic bind parameters
 *
 * @author Oliver Gierke
 * @author Thomas Darimont
 * @author Oliver Wehrens
 * @author Mark Paluch
 * @author Jens Schauder
 */
class StringQuery implements DeclaredQuery {

	private final String query;
	private final List<ParameterBinding> bindings;
	private final @Nullable String alias;
	private final boolean hasConstructorExpression;
	private final boolean containsPageableInSpel;
	private final boolean usesJdbcStyleParameters;

	/**
	 * Creates a new {@link StringQuery} from the given JPQL query.
	 *
	 * @param query must not be {@literal null} or empty.
	 */
	@SuppressWarnings("deprecation")
	StringQuery(String query) {

		Assert.hasText(query, "Query must not be null or empty!");

		this.bindings = new ArrayList<>();
		this.containsPageableInSpel = query.contains("#pageable");

		Metadata queryMeta = new Metadata();
		this.query = ParameterBindingParser.INSTANCE.parseParameterBindingsOfQueryIntoBindingsAndReturnCleanedQuery(query,
				this.bindings, queryMeta);

		this.usesJdbcStyleParameters = queryMeta.usesJdbcStyleParameters;
		this.alias = QueryUtils.detectAlias(query);
		this.hasConstructorExpression = QueryUtils.hasConstructorExpression(query);
	}

	/**
	 * Returns whether we have found some like bindings.
	 */
	boolean hasParameterBindings() {
		return !bindings.isEmpty();
	}

	String getProjection() {
		return QueryUtils.getProjection(query);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#getParameterBindings()
	 */
	@Override
	public List<ParameterBinding> getParameterBindings() {
		return bindings;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#deriveCountQuery(java.lang.String, java.lang.String)
	 */
	@Override
	@SuppressWarnings("deprecation")
	public DeclaredQuery deriveCountQuery(@Nullable String countQuery, @Nullable String countQueryProjection) {

		return DeclaredQuery
				.of(countQuery != null ? countQuery : QueryUtils.createCountQueryFor(query, countQueryProjection));
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#usesJdbcStyleParameters()
	 */
	@Override
	public boolean usesJdbcStyleParameters() {
		return usesJdbcStyleParameters;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#getQueryString()
	 */
	@Override
	public String getQueryString() {
		return query;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#getAlias()
	 */
	@Override
	@Nullable
	public String getAlias() {
		return alias;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#hasConstructorExpression()
	 */
	@Override
	public boolean hasConstructorExpression() {
		return hasConstructorExpression;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#isDefaultProjection()
	 */
	@Override
	public boolean isDefaultProjection() {
		return getProjection().equalsIgnoreCase(alias);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#hasNamedParameter()
	 */
	@Override
	public boolean hasNamedParameter() {
		return bindings.stream().anyMatch(b -> b.getName() != null);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.jpa.repository.query.DeclaredQuery#usesPaging()
	 */
	@Override
	public boolean usesPaging() {
		return containsPageableInSpel;
	}

	/**
	 * A parser that extracts the parameter bindings from a given query string.
	 *
	 * @author Thomas Darimont
	 */
	enum ParameterBindingParser {

		INSTANCE;

		private static final String EXPRESSION_PARAMETER_PREFIX = "__$synthetic$__";
		public static final String POSITIONAL_OR_INDEXED_PARAMETER = "\\?(\\d*+(?![#\\w]))";
		// .....................................................................^ not followed by a hash or a letter.
		// .................................................................^ zero or more digits.
		// .............................................................^ start with a question mark.
		private static final Pattern PARAMETER_BINDING_BY_INDEX = Pattern.compile(POSITIONAL_OR_INDEXED_PARAMETER);
		private static final Pattern PARAMETER_BINDING_PATTERN;
		private static final String MESSAGE = "Already found parameter binding with same index / parameter name but differing binding type! "
				+ "Already have: %s, found %s! If you bind a parameter multiple times make sure they use the same binding.";
		private static final int INDEXED_PARAMETER_GROUP = 4;
		private static final int NAMED_PARAMETER_GROUP = 6;
		private static final int COMPARISION_TYPE_GROUP = 1;

		static {

			List<String> keywords = new ArrayList<>();

			for (ParameterBindingType type : ParameterBindingType.values()) {
				if (type.getKeyword() != null) {
					keywords.add(type.getKeyword());
				}
			}

			StringBuilder builder = new StringBuilder();
			builder.append("(");
			builder.append(StringUtils.collectionToDelimitedString(keywords, "|")); // keywords
			builder.append(")?");
			builder.append("(?: )?"); // some whitespace
			builder.append("\\(?"); // optional braces around parameters
			builder.append("(");
			builder.append("%?(" + POSITIONAL_OR_INDEXED_PARAMETER + ")%?"); // position parameter and parameter index
			builder.append("|"); // or

			// named parameter and the parameter name
			builder.append("%?(" + QueryUtils.COLON_NO_DOUBLE_COLON + QueryUtils.IDENTIFIER_GROUP + ")%?");

			builder.append(")");
			builder.append("\\)?"); // optional braces around parameters

			PARAMETER_BINDING_PATTERN = Pattern.compile(builder.toString(), CASE_INSENSITIVE);
		}

		/**
		 * Parses {@link ParameterBinding} instances from the given query and adds them to the registered bindings. Returns
		 * the cleaned up query.
		 */
		private String parseParameterBindingsOfQueryIntoBindingsAndReturnCleanedQuery(String query,
				List<ParameterBinding> bindings, Metadata queryMeta) {

			int greatestParameterIndex = tryFindGreatestParameterIndexIn(query);
			boolean parametersShouldBeAccessedByIndex = greatestParameterIndex != -1;

			/*
			 * Prefer indexed access over named parameters if only SpEL Expression parameters are present.
			 */
			if (!parametersShouldBeAccessedByIndex && query.contains("?#{")) {
				parametersShouldBeAccessedByIndex = true;
				greatestParameterIndex = 0;
			}

			SpelExtractor spelExtractor = createSpelExtractor(query, parametersShouldBeAccessedByIndex,
					greatestParameterIndex);

			String resultingQuery = spelExtractor.getQueryString();
			Matcher matcher = PARAMETER_BINDING_PATTERN.matcher(resultingQuery);

			int expressionParameterIndex = parametersShouldBeAccessedByIndex ? greatestParameterIndex : 0;

			boolean usesJpaStyleParameters = false;
			while (matcher.find()) {

				if (spelExtractor.isQuoted(matcher.start())) {
					continue;
				}

				String parameterIndexString = matcher.group(INDEXED_PARAMETER_GROUP);
				String parameterName = parameterIndexString != null ? null : matcher.group(NAMED_PARAMETER_GROUP);
				Integer parameterIndex = getParameterIndex(parameterIndexString);

				String typeSource = matcher.group(COMPARISION_TYPE_GROUP);
				Assert.isTrue(parameterIndexString != null || parameterName != null, () -> String.format("We need either a name or an index! Offending query string: %s", query));
				String expression = spelExtractor.getParameter(parameterName == null ? parameterIndexString : parameterName);
				String replacement = null;

				expressionParameterIndex++;
				if ("".equals(parameterIndexString)) {

					queryMeta.usesJdbcStyleParameters = true;
					parameterIndex = expressionParameterIndex;
				} else {
					usesJpaStyleParameters = true;
				}

				if (usesJpaStyleParameters && queryMeta.usesJdbcStyleParameters) {
					throw new IllegalArgumentException("Mixing of ? parameters and other forms like ?1 is not supported!");
				}

				switch (ParameterBindingType.of(typeSource)) {

					case LIKE:

						Type likeType = LikeParameterBinding.getLikeTypeFrom(matcher.group(2));
						replacement = matcher.group(3);

						if (parameterIndex != null) {
							checkAndRegister(new LikeParameterBinding(parameterIndex, likeType, expression), bindings);
						} else {
							checkAndRegister(new LikeParameterBinding(parameterName, likeType, expression), bindings);

							replacement = ":" + parameterName;
						}

						break;

					case IN:

						if (parameterIndex != null) {
							checkAndRegister(new InParameterBinding(parameterIndex, expression), bindings);
						} else {
							checkAndRegister(new InParameterBinding(parameterName, expression), bindings);
						}

						break;

					case AS_IS: // fall-through we don't need a special parameter binding for the given parameter.
					default:

						bindings.add(parameterIndex != null ? new ParameterBinding(null, parameterIndex, expression)
								: new ParameterBinding(parameterName, null, expression));
				}

				if (replacement != null) {
					resultingQuery = replaceFirst(resultingQuery, matcher.group(2), replacement);
				}

			}

			return resultingQuery;
		}

		private static SpelExtractor createSpelExtractor(String queryWithSpel, boolean parametersShouldBeAccessedByIndex,
				int greatestParameterIndex) {

			/*
			 * If parameters need to be bound by index, we bind the synthetic expression parameters starting from position of the greatest discovered index parameter in order to
			 * not mix-up with the actual parameter indices.
			 */
			int expressionParameterIndex = parametersShouldBeAccessedByIndex ? greatestParameterIndex : 0;

			BiFunction<Integer, String, String> indexToParameterName = parametersShouldBeAccessedByIndex
					? (index, expression) -> String.valueOf(index + expressionParameterIndex + 1)
					: (index, expression) -> EXPRESSION_PARAMETER_PREFIX + (index + 1);

			String fixedPrefix = parametersShouldBeAccessedByIndex ? "?" : ":";

			BiFunction<String, String, String> parameterNameToReplacement = (prefix, name) -> fixedPrefix + name;

			return SpelQueryContext.of(indexToParameterName, parameterNameToReplacement).parse(queryWithSpel);
		}

		private static String replaceFirst(String text, String substring, String replacement) {

			int index = text.indexOf(substring);
			if (index < 0) {
				return text;
			}

			return text.substring(0, index) + replacement + text.substring(index + substring.length());
		}

		@Nullable
		private static Integer getParameterIndex(@Nullable String parameterIndexString) {

			if (parameterIndexString == null || parameterIndexString.isEmpty()) {
				return null;
			}
			return Integer.valueOf(parameterIndexString);
		}

		private static int tryFindGreatestParameterIndexIn(String query) {

			Matcher parameterIndexMatcher = PARAMETER_BINDING_BY_INDEX.matcher(query);

			int greatestParameterIndex = -1;
			while (parameterIndexMatcher.find()) {

				String parameterIndexString = parameterIndexMatcher.group(1);
				Integer parameterIndex = getParameterIndex(parameterIndexString);
				if (parameterIndex != null) {
					greatestParameterIndex = Math.max(greatestParameterIndex, parameterIndex);
				}
			}

			return greatestParameterIndex;
		}

		private static void checkAndRegister(ParameterBinding binding, List<ParameterBinding> bindings) {

			bindings.stream() //
					.filter(it -> it.hasName(binding.getName()) || it.hasPosition(binding.getPosition())) //
					.forEach(it -> Assert.isTrue(it.equals(binding), String.format(MESSAGE, it, binding)));

			if (!bindings.contains(binding)) {
				bindings.add(binding);
			}
		}

		/**
		 * An enum for the different types of bindings.
		 *
		 * @author Thomas Darimont
		 * @author Oliver Gierke
		 */
		private enum ParameterBindingType {

			// Trailing whitespace is intentional to reflect that the keywords must be used with at least one whitespace
			// character, while = does not.
			LIKE("like "), IN("in "), AS_IS(null);

			private final @Nullable String keyword;

			ParameterBindingType(@Nullable String keyword) {
				this.keyword = keyword;
			}

			/**
			 * Returns the keyword that will trigger the binding type or {@literal null} if the type is not triggered by a
			 * keyword.
			 *
			 * @return the keyword
			 */
			@Nullable
			public String getKeyword() {
				return keyword;
			}

			/**
			 * Return the appropriate {@link ParameterBindingType} for the given {@link String}. Returns {@literal #AS_IS} in
			 * case no other {@link ParameterBindingType} could be found.
			 */
			static ParameterBindingType of(String typeSource) {

				if (!StringUtils.hasText(typeSource)) {
					return AS_IS;
				}

				for (ParameterBindingType type : values()) {
					if (type.name().equalsIgnoreCase(typeSource.trim())) {
						return type;
					}
				}

				throw new IllegalArgumentException(String.format("Unsupported parameter binding type %s!", typeSource));
			}
		}
	}

	/**
	 * A generic parameter binding with name or position information.
	 *
	 * @author Thomas Darimont
	 */
	static class ParameterBinding {

		private final @Nullable String name;
		private final @Nullable String expression;
		private final @Nullable Integer position;

		/**
		 * Creates a new {@link ParameterBinding} for the parameter with the given position.
		 *
		 * @param position must not be {@literal null}.
		 */
		ParameterBinding(Integer position) {
			this(null, position, null);
		}

		/**
		 * Creates a new {@link ParameterBinding} for the parameter with the given name, position and expression
		 * information. Either {@literal name} or {@literal position} must be not {@literal null}.
		 *
		 * @param name of the parameter may be {@literal null}.
		 * @param position of the parameter may be {@literal null}.
		 * @param expression the expression to apply to any value for this parameter.
		 */
		ParameterBinding(@Nullable String name, @Nullable Integer position, @Nullable String expression) {

			if (name == null) {
				Assert.notNull(position, "Position must not be null!");
			}

			if (position == null) {
				Assert.notNull(name, "Name must not be null!");
			}

			this.name = name;
			this.position = position;
			this.expression = expression;
		}

		/**
		 * Returns whether the binding has the given name. Will always be {@literal false} in case the
		 * {@link ParameterBinding} has been set up from a position.
		 */
		boolean hasName(@Nullable String name) {
			return this.position == null && this.name != null && this.name.equals(name);
		}

		/**
		 * Returns whether the binding has the given position. Will always be {@literal false} in case the
		 * {@link ParameterBinding} has been set up from a name.
		 */
		boolean hasPosition(@Nullable Integer position) {
			return position != null && this.name == null && position.equals(this.position);
		}

		/**
		 * @return the name
		 */
		@Nullable
		public String getName() {
			return name;
		}

		/**
		 * @return the name
		 * @throws IllegalStateException if the name is not available.
		 * @since 2.0
		 */
		String getRequiredName() throws IllegalStateException {

			String name = getName();

			if (name != null) {
				return name;
			}

			throw new IllegalStateException(String.format("Required name for %s not available!", this));
		}

		/**
		 * @return the position
		 */
		@Nullable
		Integer getPosition() {
			return position;
		}

		/**
		 * @return the position
		 * @throws IllegalStateException if the position is not available.
		 * @since 2.0
		 */
		int getRequiredPosition() throws IllegalStateException {

			Integer position = getPosition();

			if (position != null) {
				return position;
			}

			throw new IllegalStateException(String.format("Required position for %s not available!", this));
		}

		/**
		 * @return {@literal true} if this parameter binding is a synthetic SpEL expression.
		 */
		public boolean isExpression() {
			return this.expression != null;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {

			int result = 17;

			result += nullSafeHashCode(this.name);
			result += nullSafeHashCode(this.position);
			result += nullSafeHashCode(this.expression);

			return result;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof ParameterBinding)) {
				return false;
			}

			ParameterBinding that = (ParameterBinding) obj;

			return nullSafeEquals(this.name, that.name) && nullSafeEquals(this.position, that.position)
					&& nullSafeEquals(this.expression, that.expression);
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			return String.format("ParameterBinding [name: %s, position: %d, expression: %s]", getName(), getPosition(),
					getExpression());
		}

		/**
		 * @param valueToBind value to prepare
		 */
		@Nullable
		public Object prepare(@Nullable Object valueToBind) {
			return valueToBind;
		}

		@Nullable
		public String getExpression() {
			return expression;
		}
	}

	/**
	 * Represents a {@link ParameterBinding} in a JPQL query augmented with instructions of how to apply a parameter as an
	 * {@code IN} parameter.
	 *
	 * @author Thomas Darimont
	 */
	static class InParameterBinding extends ParameterBinding {

		/**
		 * Creates a new {@link InParameterBinding} for the parameter with the given name.
		 */
		InParameterBinding(String name, @Nullable String expression) {
			super(name, null, expression);
		}

		/**
		 * Creates a new {@link InParameterBinding} for the parameter with the given position.
		 */
		InParameterBinding(int position, @Nullable String expression) {
			super(null, position, expression);
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.data.jpa.repository.query.StringQuery.ParameterBinding#prepare(java.lang.Object)
		 */
		@Override
		public Object prepare(@Nullable Object value) {

			if (!ObjectUtils.isArray(value)) {
				return value;
			}

			int length = Array.getLength(value);
			Collection<Object> result = new ArrayList<>(length);

			for (int i = 0; i < length; i++) {
				result.add(Array.get(value, i));
			}

			return result;
		}
	}

	/**
	 * Represents a parameter binding in a JPQL query augmented with instructions of how to apply a parameter as LIKE
	 * parameter. This allows expressions like {@code …like %?1} in the JPQL query, which is not allowed by plain JPA.
	 *
	 * @author Oliver Gierke
	 * @author Thomas Darimont
	 */
	static class LikeParameterBinding extends ParameterBinding {

		private static final List<Type> SUPPORTED_TYPES = Arrays.asList(Type.CONTAINING, Type.STARTING_WITH,
				Type.ENDING_WITH, Type.LIKE);

		private final Type type;

		/**
		 * Creates a new {@link LikeParameterBinding} for the parameter with the given name and {@link Type}.
		 *
		 * @param name must not be {@literal null} or empty.
		 * @param type must not be {@literal null}.
		 */
		LikeParameterBinding(String name, Type type) {
			this(name, type, null);
		}

		/**
		 * Creates a new {@link LikeParameterBinding} for the parameter with the given name and {@link Type} and parameter
		 * binding input.
		 *
		 * @param name must not be {@literal null} or empty.
		 * @param type must not be {@literal null}.
		 * @param expression may be {@literal null}.
		 */
		LikeParameterBinding(String name, Type type, @Nullable String expression) {

			super(name, null, expression);

			Assert.hasText(name, "Name must not be null or empty!");
			Assert.notNull(type, "Type must not be null!");

			Assert.isTrue(SUPPORTED_TYPES.contains(type),
					String.format("Type must be one of %s!", StringUtils.collectionToCommaDelimitedString(SUPPORTED_TYPES)));

			this.type = type;
		}

		/**
		 * Creates a new {@link LikeParameterBinding} for the parameter with the given position and {@link Type}.
		 *
		 * @param position position of the parameter in the query.
		 * @param type must not be {@literal null}.
		 */
		LikeParameterBinding(int position, Type type) {
			this(position, type, null);
		}

		/**
		 * Creates a new {@link LikeParameterBinding} for the parameter with the given position and {@link Type}.
		 *
		 * @param position position of the parameter in the query.
		 * @param type must not be {@literal null}.
		 * @param expression may be {@literal null}.
		 */
		LikeParameterBinding(int position, Type type, @Nullable String expression) {

			super(null, position, expression);

			Assert.isTrue(position > 0, "Position must be greater than zero!");
			Assert.notNull(type, "Type must not be null!");

			Assert.isTrue(SUPPORTED_TYPES.contains(type),
					String.format("Type must be one of %s!", StringUtils.collectionToCommaDelimitedString(SUPPORTED_TYPES)));

			this.type = type;
		}

		/**
		 * Returns the {@link Type} of the binding.
		 *
		 * @return the type
		 */
		public Type getType() {
			return type;
		}

		/**
		 * Prepares the given raw keyword according to the like type.
		 */
		@Nullable
		@Override
		public Object prepare(@Nullable Object value) {

			if (value == null) {
				return null;
			}

			switch (type) {
				case STARTING_WITH:
					return String.format("%s%%", value);
				case ENDING_WITH:
					return String.format("%%%s", value);
				case CONTAINING:
					return String.format("%%%s%%", value);
				case LIKE:
				default:
					return value;
			}
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(Object obj) {

			if (!(obj instanceof LikeParameterBinding)) {
				return false;
			}

			LikeParameterBinding that = (LikeParameterBinding) obj;

			return super.equals(obj) && this.type.equals(that.type);
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		@Override
		public int hashCode() {

			int result = super.hashCode();

			result += nullSafeHashCode(this.type);

			return result;
		}

		/*
		 * (non-Javadoc)
		 * @see java.lang.Object#toString()
		 */
		@Override
		public String toString() {
			return String.format("LikeBinding [name: %s, position: %d, type: %s]", getName(), getPosition(), type);
		}

		/**
		 * Extracts the like {@link Type} from the given JPA like expression.
		 *
		 * @param expression must not be {@literal null} or empty.
		 */
		private static Type getLikeTypeFrom(String expression) {

			Assert.hasText(expression, "Expression must not be null or empty!");

			if (expression.matches("%.*%")) {
				return Type.CONTAINING;
			}

			if (expression.startsWith("%")) {
				return Type.ENDING_WITH;
			}

			if (expression.endsWith("%")) {
				return Type.STARTING_WITH;
			}

			return Type.LIKE;
		}
	}

	static class Metadata {
		private boolean usesJdbcStyleParameters = false;
	}
}
