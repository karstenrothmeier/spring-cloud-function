/*
 * Copyright 2019-2020 the original author or authors.
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

package org.springframework.cloud.function.context.catalog;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.jodah.typetools.TypeResolver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.FunctionProperties;
import org.springframework.cloud.function.context.FunctionRegistration;
import org.springframework.cloud.function.context.FunctionRegistry;
import org.springframework.cloud.function.context.config.RoutingFunction;
import org.springframework.cloud.function.json.JsonMapper;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;


/**
 * Implementation of {@link FunctionCatalog} and {@link FunctionRegistry} which
 * does not depend on Spring's {@link BeanFactory}.
 * Each function must be registered with it explicitly to benefit from features
 * such as type conversion, composition, POJO etc.
 *
 * @author Oleg Zhurakousky
 *
 */
public class SimpleFunctionRegistry implements FunctionRegistry, FunctionInspector {
	protected Log logger = LogFactory.getLog(this.getClass());
	/*
	 * - do we care about FunctionRegistration after it's been registered? What additional value does it bring?
	 *
	 */

	private final Field headersField;

	private final Set<FunctionRegistration<?>> functionRegistrations = new HashSet<>();

	private final Map<String, FunctionInvocationWrapper> wrappedFunctionDefinitions = new HashMap<>();

	private final ConversionService conversionService;

	private final CompositeMessageConverter messageConverter;

	private final JsonMapper jsonMapper;

	public SimpleFunctionRegistry(ConversionService conversionService, CompositeMessageConverter messageConverter, JsonMapper jsonMapper) {
		Assert.notNull(messageConverter, "'messageConverter' must not be null");
		Assert.notNull(jsonMapper, "'jsonMapper' must not be null");
		this.conversionService = conversionService;
		this.jsonMapper = jsonMapper;
		this.messageConverter = messageConverter;
		this.headersField = ReflectionUtils.findField(MessageHeaders.class, "headers");
		this.headersField.setAccessible(true);
	}

	@Override
	public FunctionRegistration<?> getRegistration(Object function) {
		throw new UnsupportedOperationException("FunctionInspector is deprecated. There is no need "
				+ "to access FunctionRegistration directly since you can interogate the actual "
				+ "looked-up function (see FunctionInvocationWrapper.");
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T lookup(Class<?> type, String functionDefinition, String... expectedOutputMimeTypes) {
		functionDefinition = this.normalizeFunctionDefinition(functionDefinition);
		FunctionInvocationWrapper function = this.doLookup(type, functionDefinition, expectedOutputMimeTypes);
		if (logger.isInfoEnabled()) {
			if (function != null) {
				logger.info("Located function: " + function);
			}
			else {
				logger.info("Failed to locate function: " + functionDefinition);
			}
		}
		return (T) function;
	}

	@Override
	public <T> void register(FunctionRegistration<T> registration) {
		this.functionRegistrations.add(registration);
	}

	//-----

	@Override
	public Set<String> getNames(Class<?> type) {
		return this.functionRegistrations.stream().flatMap(fr -> fr.getNames().stream()).collect(Collectors.toSet());
	}

	@Override
	public int size() {
		return this.functionRegistrations.size();
	}

	/*
	 *
	 */
	protected boolean containsFunction(String functionName) {
		return this.functionRegistrations.stream().anyMatch(reg -> reg.getNames().contains(functionName));
	}

	/*
	 *
	 */
	@SuppressWarnings("unchecked")
	<T> T doLookup(Class<?> type, String functionDefinition, String[] expectedOutputMimeTypes) {
		FunctionInvocationWrapper function = this.wrappedFunctionDefinitions.get(functionDefinition);

		if (function == null) {
			function = this.compose(type, functionDefinition);
		}

		if (function != null) {
			function.expectedOutputContentType = expectedOutputMimeTypes;
		}
		else {
			logger.debug("Function '" + functionDefinition + "' is not found");
		}
		return (T) function;
	}

	/**
	 * This method will make sure that if there is only one function in catalog
	 * it can be looked up by any name or no name.
	 * It does so by attempting to determine the default function name
	 * (the only function in catalog) and checking if it matches the provided name
	 * replacing it if it does not.
	 */
	String normalizeFunctionDefinition(String functionDefinition) {
		functionDefinition = StringUtils.hasText(functionDefinition)
				? functionDefinition.replaceAll(",", "|")
				: System.getProperty(FunctionProperties.FUNCTION_DEFINITION, "");

		if (!this.getNames(null).contains(functionDefinition)) {
			List<String> eligibleFunction = this.getNames(null).stream()
					.filter(name -> !RoutingFunction.FUNCTION_NAME.equals(name))
					.collect(Collectors.toList());
			if (eligibleFunction.size() == 1
					&& !eligibleFunction.get(0).equals(functionDefinition)
					&& !functionDefinition.contains("|")) {
				functionDefinition = eligibleFunction.get(0);
			}
		}
		return functionDefinition;
	}

	/*
	 *
	 */
	private FunctionInvocationWrapper findFunctionInFunctionRegistrations(String functionName) {
		FunctionRegistration<?> functionRegistration = this.functionRegistrations.stream()
				.filter(fr -> fr.getNames().contains(functionName))
				.findFirst()
				.orElseGet(() -> null);
		return functionRegistration != null
				? this.invocationWrapperInstance(functionName, functionRegistration.getTarget(), functionRegistration.getType().getType())
				: null;

	}

	/*
	 *
	 */
	private FunctionInvocationWrapper compose(Class<?> type, String functionDefinition) {
		String[] functionNames = StringUtils.delimitedListToStringArray(functionDefinition.replaceAll(",", "|").trim(), "|");
		FunctionInvocationWrapper composedFunction = null;

		for (String functionName : functionNames) {
			FunctionInvocationWrapper function = this.findFunctionInFunctionRegistrations(functionName);
			if (function == null) {
				return null;
			}
			else {
				if (composedFunction == null) {
					composedFunction = function;
				}
				else {
					FunctionInvocationWrapper andThenFunction =
							invocationWrapperInstance(functionName, function.getTarget(), function.inputType, function.outputType);
					composedFunction = (FunctionInvocationWrapper) composedFunction.andThen((Function<Object, Object>) andThenFunction);
				}
				this.wrappedFunctionDefinitions.put(composedFunction.functionDefinition, composedFunction);
			}
		}
		return composedFunction;
	}

	/*
	 *
	 */
	private FunctionInvocationWrapper invocationWrapperInstance(String functionDefinition, Object target, Type inputType, Type outputType) {
		return new FunctionInvocationWrapper(functionDefinition, target, inputType, outputType);
	}

	/*
	 *
	 */
	private FunctionInvocationWrapper invocationWrapperInstance(String functionDefinition, Object target, Type functionType) {
		return invocationWrapperInstance(functionDefinition, target,
				FunctionTypeUtils.isSupplier(functionType) ? null : FunctionTypeUtils.getInputType(functionType),
				FunctionTypeUtils.getOutputType(functionType));
	}

	/**
	 *
	 */
	@SuppressWarnings("rawtypes")
	public final class FunctionInvocationWrapper implements Function<Object, Object>, Consumer<Object>, Supplier<Object>, Runnable {

		private final Object target;

		private Type inputType;

		private final Type outputType;

		private final String functionDefinition;

		private boolean composed;

		private boolean message;

		private String[] expectedOutputContentType;

		/*
		 * This is primarily to support Stream's ability to access
		 * un-converted payload (e.g., to evaluate expression on some attribute of a payload)
		 * It does not have a setter/getter and can only be set via reflection.
		 * It is not intended to remain here and will be removed as soon as particular elements
		 * of stream will be refactored to address this.
		 */
		private Function<Object, Message> enhancer;

		private FunctionInvocationWrapper(String functionDefinition,  Object target, Type inputType, Type outputType) {
			this.target = target;
			this.inputType = this.normalizeType(inputType);
			this.outputType = this.normalizeType(outputType);
			this.functionDefinition = functionDefinition;
			this.message = this.inputType != null && FunctionTypeUtils.isMessage(this.inputType);
		}

		public Object getTarget() {
			return target;
		}

		public Type getOutputType() {
			return this.outputType;
		}

		public Type getInputType() {
			return this.inputType;
		}

		/**
		 * Use individual {@link #getInputType()}, {@link #getOutputType()} and their variants as well as
		 * other supporting operations instead.
		 * @deprecated since 3.1
		 */
		@Deprecated
		public Type getFunctionType() {
			if (this.isFunction()) {
				ResolvableType rItype = ResolvableType.forType(this.inputType);
				ResolvableType rOtype = ResolvableType.forType(this.outputType);
				return ResolvableType.forClassWithGenerics(Function.class, rItype, rOtype).getType();
			}
			else if (this.isConsumer()) {
				ResolvableType rItype = ResolvableType.forType(this.inputType);
				return ResolvableType.forClassWithGenerics(Consumer.class, rItype).getType();
			}
			else {
				ResolvableType rOtype = ResolvableType.forType(this.outputType);
				return ResolvableType.forClassWithGenerics(Supplier.class, rOtype).getType();
			}
		}

		public Class<?> getRawOutputType() {
			return this.outputType == null ? null : TypeResolver.resolveRawClass(this.outputType, null);
		}

		public Class<?> getRawInputType() {
			return this.inputType == null ? null : TypeResolver.resolveRawClass(this.inputType, null);
		}

		/**
		 *
		 */
		@Override
		public Object apply(Object input) {

			Object result = this.doApply(input);

			if (result != null && this.outputType != null) {
				result = this.convertOutputIfNecessary(result, this.outputType, this.expectedOutputContentType);
			}

			return result;
		}

		@Override
		public Object get() {
			return this.apply(null);
		}

		@Override
		public void accept(Object input) {
			this.apply(input);
		}

		@Override
		public void run() {
			this.apply(null);
		}

		public boolean isConsumer() {
			return this.outputType == null;
		}

		public boolean isSupplier() {
			return this.inputType == null;
		}

		public boolean isFunction() {
			return this.inputType != null && this.outputType != null;
		}

		public boolean isInputTypePublisher() {
			return this.isTypePublisher(this.inputType);
		}

		public boolean isOutputTypePublisher() {
			return this.isTypePublisher(this.outputType);
		}

		public boolean isInputTypeMessage() {
			return this.message || this.isRoutingFunction();
		}

		public boolean isOutputTypeMessage() {
			return FunctionTypeUtils.isMessage(this.outputType);
		}


		public boolean isRoutingFunction() {
			return this.target instanceof RoutingFunction;
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		@Override
		public <V> Function<Object, V> andThen(Function<? super Object, ? extends V> after) {
			Assert.isTrue(after instanceof FunctionInvocationWrapper, "Composed function must be an instanceof FunctionInvocationWrapper.");
			if (FunctionTypeUtils.isMultipleArgumentType(this.inputType)
					|| FunctionTypeUtils.isMultipleArgumentType(this.outputType)
					|| FunctionTypeUtils.isMultipleArgumentType(((FunctionInvocationWrapper) after).inputType)
					|| FunctionTypeUtils.isMultipleArgumentType(((FunctionInvocationWrapper) after).outputType)) {
				throw new UnsupportedOperationException("Composition of functions with multiple arguments is not supported at the moment");
			}

			Function rawComposedFunction = v -> ((FunctionInvocationWrapper) after).doApply(doApply(v));

			FunctionInvocationWrapper afterWrapper = (FunctionInvocationWrapper) after;

			Type composedFunctionType;
			if (afterWrapper.outputType == null) {
				composedFunctionType = ResolvableType.forClassWithGenerics(Consumer.class, this.inputType == null
						? null
						: ResolvableType.forType(this.inputType)).getType();
			}
			else if (this.inputType == null && afterWrapper.outputType != null) {
				ResolvableType composedOutputType;
				if (FunctionTypeUtils.isFlux(this.outputType)) {
					composedOutputType = ResolvableType.forClassWithGenerics(Flux.class, ResolvableType.forType(afterWrapper.outputType));
				}
				else if (FunctionTypeUtils.isMono(this.outputType)) {
					composedOutputType = ResolvableType.forClassWithGenerics(Mono.class, ResolvableType.forType(afterWrapper.outputType));
				}
				else {
					composedOutputType = ResolvableType.forType(afterWrapper.outputType);
				}

				composedFunctionType = ResolvableType.forClassWithGenerics(Supplier.class, composedOutputType).getType();
			}
			else if (this.outputType == null) {
				throw new IllegalArgumentException("Can NOT compose anything with Consumer");
			}
			else {
				composedFunctionType = ResolvableType.forClassWithGenerics(Function.class,
						ResolvableType.forType(this.inputType),
						ResolvableType.forType(((FunctionInvocationWrapper) after).outputType)).getType();
			}

			String composedName = this.functionDefinition + "|" + afterWrapper.functionDefinition;
			FunctionInvocationWrapper composedFunction = invocationWrapperInstance(composedName, rawComposedFunction, composedFunctionType);
			composedFunction.composed = true;

			return (Function<Object, V>) composedFunction;
		}

		/**
		 * Returns the definition of this function.
		 * @return function definition
		 */
		public String getFunctionDefinition() {
			return this.functionDefinition;
		}

		/*
		 *
		 */
		@Override
		public String toString() {
			return this.functionDefinition + (this.isComposed() ? "" : "<" + this.inputType + ", " + this.outputType + ">");
		}

		/**
		 * Returns true if this function wrapper represents a composed function.
		 * @return true if this function wrapper represents a composed function otherwise false
		 */
		boolean isComposed() {
			return this.composed;
		}

		/*
		 *
		 */
		private boolean isTypePublisher(Type type) {
			return type != null && FunctionTypeUtils.isPublisher(type);
		}

		/**
		 * Will return Object.class if type is represented as TypeVariable(T) or WildcardType(?).
		 */
		private Type normalizeType(Type type) {
			if (type != null) {
				return !(type instanceof TypeVariable) && !(type instanceof WildcardType) ? type : Object.class;
			}
			return type;
		}

		/*
		 *
		 */
		private Class<?> getRawClassFor(@Nullable Type type) {
			return type instanceof TypeVariable || type instanceof WildcardType ? Object.class : TypeResolver.resolveRawClass(type, null);
		}

		/**
		 * Will wrap the result in a Message if necessary and will copy input headers to the output message.
		 */
		@SuppressWarnings("unchecked")
		private Object enrichInvocationResultIfNecessary(Object input, Object result) {
			// TODO we need to investigate this further. This effectively states that if `scf-func-name` present
			// wrap the result in a message regardless and copy all the headers from the incoming message.
			// Used in SupplierExporter
			if (input instanceof Message && ((Message) input).getHeaders().containsKey("scf-func-name")) {
				if (result instanceof Message) {
					Map<String, Object> headersMap = (Map<String, Object>) ReflectionUtils
							.getField(SimpleFunctionRegistry.this.headersField, ((Message) result).getHeaders());
					headersMap.putAll(((Message) input).getHeaders());
				}
				else {
					result = MessageBuilder.withPayload(result).copyHeaders(((Message) input).getHeaders()).build();
				}
			}
			return result;
		}

		/*
		 *
		 */
		private Object fluxifyInputIfNecessary(Object input) {
			if (!(input instanceof Publisher) && this.isTypePublisher(this.inputType) && !FunctionTypeUtils.isMultipleArgumentType(this.inputType)) {
				return input == null
						? FunctionTypeUtils.isMono(this.inputType) ? Mono.empty() : Flux.empty()
						: FunctionTypeUtils.isMono(this.inputType) ? Mono.just(input) : Flux.just(input);
			}
			return input;
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		private Object doApply(Object input) {
			Object result;

			input = this.fluxifyInputIfNecessary(input);

			Object convertedInput = this.convertInputIfNecessary(input, this.inputType);

			if (this.isRoutingFunction() || this.isComposed()) {
				result = ((Function) this.target).apply(convertedInput);
			}
			else if (this.isSupplier()) {
				result = ((Supplier) this.target).get();
			}
			else if (this.isConsumer()) {
				result = this.invokeConsumer(convertedInput);
			}
			else { // Function
				result = this.invokeFunction(convertedInput);
			}
			return result;
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		private Object invokeFunction(Object convertedInput) {
			Object result;
			if (!this.isTypePublisher(this.inputType) && convertedInput instanceof Publisher) {
				result = convertedInput instanceof Mono
						? Mono.from((Publisher) convertedInput).map(value -> this.invokeFunctionAndEnrichResultIfNecessary(value))
							.doOnError(ex -> logger.error("Failed to invoke function '" + this.functionDefinition + "'", (Throwable) ex))
						: Flux.from((Publisher) convertedInput).map(value -> this.invokeFunctionAndEnrichResultIfNecessary(value))
							.doOnError(ex -> logger.error("Failed to invoke function '" + this.functionDefinition + "'", (Throwable) ex));
			}
			else {
				result = this.invokeFunctionAndEnrichResultIfNecessary(convertedInput);
			}
			return result;
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		private Object invokeFunctionAndEnrichResultIfNecessary(Object value) {
			Object inputValue = value instanceof OriginalMessageHolder ? ((OriginalMessageHolder) value).getKey() : value;

			Object result = ((Function) this.target).apply(inputValue);

			return value instanceof OriginalMessageHolder
					? this.enrichInvocationResultIfNecessary(((OriginalMessageHolder) value).getValue(), result)
					: result;
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		private Object invokeConsumer(Object convertedInput) {
			Object result = null;
			if (this.isTypePublisher(this.inputType)) {
				if (convertedInput instanceof Flux) {
					result = ((Flux) convertedInput)
							.transform(flux -> {
								((Consumer) this.target).accept(flux);
								return Mono.ignoreElements((Flux) flux);
							}).then();
				}
				else {
					result = ((Mono) convertedInput)
							.transform(mono -> {
								((Consumer) this.target).accept(mono);
								return Mono.ignoreElements((Flux) mono);
							}).then();
				}
			}
			else if (convertedInput instanceof Publisher) {
				result = convertedInput instanceof Mono
						? Mono.from((Publisher) convertedInput).doOnNext((Consumer) this.target).then()
						: Flux.from((Publisher) convertedInput).doOnNext((Consumer) this.target).then();
			}
			else {
				((Consumer) this.target).accept(convertedInput);
			}
			return result;
		}

		/**
		 * This operation will parse value coming in as Tuples to Object[].
		 */
		private Object[] parseMultipleValueArguments(Object multipleValueArgument, int argumentCount) {
			Object[] parsedArgumentValues = new Object[argumentCount];
			if (multipleValueArgument.getClass().getName().startsWith("reactor.util.function.Tuple")) {
				for (int i = 0; i < argumentCount; i++) {
					Expression parsed = new SpelExpressionParser().parseExpression("getT" + (i + 1) + "()");
					Object outputArgument = parsed.getValue(multipleValueArgument);
					parsedArgumentValues[i] = outputArgument;
				}
				return parsedArgumentValues;
			}
			throw new UnsupportedOperationException("At the moment only Tuple-based function are supporting multiple arguments");
		}

		/*
		 *
		 */
		private Object convertInputIfNecessary(Object input, Type type) {
			if (this.getRawClassFor(type) == Void.class && !(input instanceof Publisher) && !(input instanceof Message)) {
				logger.info("Input value '" + input + "' is ignored for function '"
						+ this.functionDefinition + "' since it's input type is Void and as such it is treated as Supplier.");
				input = null;
			}

			if (FunctionTypeUtils.isMultipleArgumentType(type)) {
				Type[] inputTypes = ((ParameterizedType) type).getActualTypeArguments();
				Object[] multipleValueArguments = this.parseMultipleValueArguments(input, inputTypes.length);
				Object[] convertedInputs = new Object[inputTypes.length];
				for (int i = 0; i < multipleValueArguments.length; i++) {
					Object convertedInput = this.convertInputIfNecessary(multipleValueArguments[i], inputTypes[i]);
					convertedInputs[i] = convertedInput;
				}
				return Tuples.fromArray(convertedInputs);
			}

			Object convertedInput = input;
			if (input == null || this.target instanceof RoutingFunction || this.isComposed()) {
				return input;
			}

			if (input instanceof Publisher) {
				convertedInput = this.convertInputPublisherIfNecessary((Publisher) input, type);
			}
			else if (input instanceof Message) {
				convertedInput = this.convertInputMessageIfNecessary((Message) input, type);
				if (!FunctionTypeUtils.isMultipleArgumentType(this.inputType)) {
					convertedInput = this.isPropagateInputHeaders((Message) input) ? new OriginalMessageHolder(convertedInput, (Message<?>) input) : convertedInput;
				}
			}
			else {
				convertedInput = this.convertNonMessageInputIfNecessary(type, input);
			}
			// wrap in Message if necessary
			if (this.isWrapConvertedInputInMessage(convertedInput)) {
				convertedInput = MessageBuilder.withPayload(convertedInput).build();
			}
			return convertedInput;
		}

		/**
		 * This is an optional conversion which would only happen if `expected-content-type` is
		 * set as a header in a message or explicitly provided as part of the lookup.
		 */
		private Object convertOutputIfNecessary(Object output, Type type, String[] contentType) {
			if (!(output instanceof Publisher) && this.enhancer != null) {
				output = enhancer.apply(output);
			}
			Object convertedOutput = output;
			if (FunctionTypeUtils.isMultipleArgumentType(type)) {
				convertedOutput = this.convertMultipleOutputArgumentTypeIfNecesary(convertedOutput, type, contentType);
			}
			else if (output instanceof Publisher) {
				convertedOutput = this.convertOutputPublisherIfNecessary((Publisher) output, type, contentType);
			}
			else if (output instanceof Message) {
				convertedOutput = this.convertOutputMessageIfNecessary(output, ObjectUtils.isEmpty(contentType) ? null : contentType[0]);
			}
			else if (output instanceof Collection && this.isOutputTypeMessage()) {
				convertedOutput = this.convertMultipleOutputValuesIfNecessary(output, ObjectUtils.isEmpty(contentType) ? null : contentType);
			}
			else if (!ObjectUtils.isEmpty(contentType)) {
				convertedOutput = messageConverter.toMessage(output,
						new MessageHeaders(Collections.singletonMap(MessageHeaders.CONTENT_TYPE, MimeType.valueOf(contentType[0]))));
			}

			return convertedOutput;
		}

		/*
		 *
		 */
		private Object convertNonMessageInputIfNecessary(Type inputType, Object input) {
			Object convertedInput = input;
			Class<?> rawInputType = this.isTypePublisher(inputType) || this.isInputTypeMessage()
					? TypeResolver.resolveRawClass(FunctionTypeUtils.getImmediateGenericType(inputType, 0), null)
					: this.getRawClassFor(inputType);

			if (JsonMapper.isJsonString(input) && !Message.class.isAssignableFrom(rawInputType)) {
				if (FunctionTypeUtils.isMessage(inputType)) {
					inputType = FunctionTypeUtils.getGenericType(inputType);
				}
				if (Object.class != inputType) {
					convertedInput = SimpleFunctionRegistry.this.jsonMapper.fromJson(input, inputType);
				}
			}
			else if (SimpleFunctionRegistry.this.conversionService != null
					&& !rawInputType.equals(input.getClass())
					&& SimpleFunctionRegistry.this.conversionService.canConvert(input.getClass(), rawInputType)) {
				convertedInput = SimpleFunctionRegistry.this.conversionService.convert(input, rawInputType);
			}
			return convertedInput;
		}

		/*
		 *
		 */
		private boolean isWrapConvertedInputInMessage(Object convertedInput) {
			return this.inputType != null
					&& FunctionTypeUtils.isMessage(this.inputType)
					&& !(convertedInput instanceof Message)
					&& !(convertedInput instanceof Publisher)
					&& !(convertedInput instanceof OriginalMessageHolder);
		}

		/*
		 *
		 */
		private boolean isPropagateInputHeaders(Message message) {
			return !this.isTypePublisher(this.inputType) && this.isFunction();
		}

		/*
		 *
		 */
		private Type extractActualValueTypeIfNecessary(Type type) {
			if (type  instanceof ParameterizedType && (FunctionTypeUtils.isPublisher(type) || FunctionTypeUtils.isMessage(type))) {
				return FunctionTypeUtils.getImmediateGenericType(type, 0);
			}
			return type;
		}

		private boolean isConversionHintRequired(Object actualType, Class<?> rawType) {
			return rawType != actualType;
		}

		/*
		 *
		 */
		private Object convertInputMessageIfNecessary(Message message, Type type) {
			if (message.getPayload() instanceof Optional) {
				return message;
			}
			if (type == null) {
				return null;
			}

			Object convertedInput = message;
			type = this.extractActualValueTypeIfNecessary(type);
			Class rawType = TypeResolver.resolveRawClass(type, null);
			convertedInput = this.isConversionHintRequired(type, rawType)
					? SimpleFunctionRegistry.this.messageConverter.fromMessage(message, rawType, type)
					: SimpleFunctionRegistry.this.messageConverter.fromMessage(message, rawType);


			if (this.isInputTypeMessage()) {
				if (convertedInput == null) {
					/*
					 * In the event conversion was unsuccessful we simply return the original un-converted message.
					 * This will help to deal with issues like KafkaNull and others. However if this was not the intention
					 * of the developer, this would be discovered early in the development process where the
					 * additional message converter could be added to facilitate the conversion.
					 */
					logger.info("Input type conversion of payload " + message.getPayload() + " resulted in 'null'. "
							+ "Will use the original message as input.");
					convertedInput = message;
				}
				else {
					convertedInput = MessageBuilder.withPayload(convertedInput).copyHeaders(message.getHeaders()).build();
				}
			}
			return convertedInput;
		}

		/**
		 * This method handles function with multiple output arguments (e.g. Tuple2<..>)
		 */
		private Object convertMultipleOutputArgumentTypeIfNecesary(Object output, Type type, String[] contentType) {
			Type[] outputTypes = ((ParameterizedType) type).getActualTypeArguments();
			Object[] multipleValueArguments = this.parseMultipleValueArguments(output, outputTypes.length);
			Object[] convertedOutputs = new Object[outputTypes.length];
			for (int i = 0; i < multipleValueArguments.length; i++) {
				String[] ctToUse = !ObjectUtils.isEmpty(contentType)
						? new String[]{contentType[i]}
						: new String[] {"application/json"};
				Object convertedInput = this.convertOutputIfNecessary(multipleValueArguments[i], outputTypes[i], ctToUse);
				convertedOutputs[i] = convertedInput;
			}
			return Tuples.fromArray(convertedOutputs);
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		private Object convertOutputMessageIfNecessary(Object output, String expectedOutputContetntType) {
			Map<String, Object> headersMap = (Map<String, Object>) ReflectionUtils
					.getField(SimpleFunctionRegistry.this.headersField, ((Message) output).getHeaders());
			String contentType = ((Message) output).getHeaders().containsKey(FunctionProperties.EXPECT_CONTENT_TYPE_HEADER)
					? (String) ((Message) output).getHeaders().get(FunctionProperties.EXPECT_CONTENT_TYPE_HEADER)
							: expectedOutputContetntType;

			if (StringUtils.hasText(contentType)) {
				String[] expectedContentTypes = StringUtils.delimitedListToStringArray(contentType, ",");
				for (String expectedContentType : expectedContentTypes) {
					headersMap.put(MessageHeaders.CONTENT_TYPE, expectedContentType);
					Object result = messageConverter.toMessage(((Message) output).getPayload(), ((Message) output).getHeaders());
					if (result != null) {
						return result;
					}
				}
			}
			return output;
		}

		/**
		 * This one is used to convert individual value of Collection or array.
		 */
		@SuppressWarnings("unchecked")
		private Object convertMultipleOutputValuesIfNecessary(Object output, String[] contentType) {
			Collection outputCollection = (Collection) output;
			Collection convertedOutputCollection = output instanceof List ? new ArrayList<>() : new TreeSet<>();
			for (Object outToConvert : outputCollection) {
				Object result = this.convertOutputIfNecessary(outToConvert, this.outputType, contentType);
				Assert.notNull(result, () -> "Failed to convert output '" + output + "'");
				convertedOutputCollection.add(result);
			}
			return convertedOutputCollection;
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		private Object convertInputPublisherIfNecessary(Publisher publisher, Type type) {
			Type actualType = type != null ? FunctionTypeUtils.getGenericType(type) : type;
			return publisher instanceof Mono
					? Mono.from(publisher).map(v -> this.convertInputIfNecessary(v, actualType))
							.doOnError(ex -> logger.error("Failed to convert input", (Throwable) ex))
					: Flux.from(publisher).map(v -> this.convertInputIfNecessary(v, actualType))
							.doOnError(ex -> logger.error("Failed to convert input", (Throwable) ex));
		}

		/*
		 *
		 */
		@SuppressWarnings("unchecked")
		private Object convertOutputPublisherIfNecessary(Publisher publisher, Type type, String[] expectedOutputContentType) {
			Type actualType = type != null ? FunctionTypeUtils.getGenericType(type) : type;
			return publisher instanceof Mono
					? Mono.from(publisher).map(v -> this.convertOutputIfNecessary(v, actualType, expectedOutputContentType))
							.doOnError(ex -> logger.error("Failed to convert output", (Throwable) ex))
					: Flux.from(publisher).map(v -> this.convertOutputIfNecessary(v, actualType, expectedOutputContentType))
							.doOnError(ex -> logger.error("Failed to convert output", (Throwable) ex));
		}
	}

	/**
	 *
	 */
	private static final class OriginalMessageHolder implements Entry<Object, Message<?>> {
		private final Object key;

		private final Message<?> value;

		private OriginalMessageHolder(Object key, Message<?> value) {
			this.key = key;
			this.value = value;
		}

		@Override
		public Object getKey() {
			return this.key;
		}

		@Override
		public Message<?> getValue() {
			return this.value;
		}

		@Override
		public Message<?> setValue(Message<?> value) {
			throw new UnsupportedOperationException();
		}
	}
}
