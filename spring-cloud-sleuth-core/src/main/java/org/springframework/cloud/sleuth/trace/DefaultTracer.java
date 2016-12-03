/*
 * Copyright 2013-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.trace;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cloud.sleuth.Sampler;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.SpanExtractor;
import org.springframework.cloud.sleuth.SpanInjector;
import org.springframework.cloud.sleuth.SpanNamer;
import org.springframework.cloud.sleuth.SpanReporter;
import org.springframework.cloud.sleuth.TraceCallable;
import org.springframework.cloud.sleuth.TraceRunnable;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.log.SpanLogger;
import org.springframework.cloud.sleuth.util.ExceptionUtils;
import org.springframework.context.ApplicationContext;

import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;

/**
 * Default implementation of {@link Tracer}
 *
 * @author Spencer Gibb
 * @since 1.0.0
 */
public class DefaultTracer implements Tracer {

	private static final Log log = LogFactory.getLog(MethodHandles.lookup().lookupClass());

	private final Map<String, SpanInjector> injectors = new ConcurrentHashMap<>();
	private final Map<String, SpanExtractor> extractors = new ConcurrentHashMap<>();

	private final Sampler defaultSampler;

	private final Random random;

	private final SpanNamer spanNamer;

	private final SpanLogger spanLogger;

	private final SpanReporter spanReporter;

	private final ApplicationContext applicationContext;

	private final boolean traceId128;

	@Deprecated
	public DefaultTracer(Sampler defaultSampler, Random random, SpanNamer spanNamer,
			SpanLogger spanLogger, SpanReporter spanReporter) {
		this(defaultSampler, random, spanNamer, spanLogger, spanReporter, false);
	}

	@Deprecated
	public DefaultTracer(Sampler defaultSampler, Random random, SpanNamer spanNamer,
				SpanLogger spanLogger, SpanReporter spanReporter, boolean traceId128) {
		this.defaultSampler = defaultSampler;
		this.random = random;
		this.spanNamer = spanNamer;
		this.spanLogger = spanLogger;
		this.spanReporter = spanReporter;
		this.traceId128 = traceId128;
		this.applicationContext = null;
	}

	public DefaultTracer(Sampler defaultSampler, Random random, SpanNamer spanNamer,
			SpanLogger spanLogger, SpanReporter spanReporter, ApplicationContext applicationContext) {
		this(defaultSampler, random, spanNamer, spanLogger, spanReporter, false, applicationContext);
	}

	public DefaultTracer(Sampler defaultSampler, Random random, SpanNamer spanNamer,
				SpanLogger spanLogger, SpanReporter spanReporter, boolean traceId128, ApplicationContext applicationContext) {
		this.defaultSampler = defaultSampler;
		this.random = random;
		this.spanNamer = spanNamer;
		this.spanLogger = spanLogger;
		this.spanReporter = spanReporter;
		this.traceId128 = traceId128;
		this.applicationContext = applicationContext;
	}

	@Override
	public Span createSpan(String name, Span parent) {
		if (parent == null) {
			return createSpan(name);
		}
		return continueSpan(createChild(parent, name));
	}

	@Override
	public Span createSpan(String name) {
		return this.createSpan(name, this.defaultSampler);
	}

	@Override
	public Span createSpan(Span span) {
		if (isTracing()) {
			span = createChild(getCurrentSpan(), span);
		}
		else {
			long id = createId();
			span = Span.builder(span)
					.traceIdHigh(this.traceId128 ? createId() : 0L)
					.traceId(id)
					.spanId(id).build();
			span = sampledSpan(span, this.defaultSampler);
			this.spanLogger.logStartedSpan(null, span);
		}
		return continueSpan(span);
	}

	@Override
	public Span createSpan(String name, Sampler sampler) {
		Span span;
		if (isTracing()) {
			span = createChild(getCurrentSpan(), name);
		}
		else {
			long id = createId();
			span = Span.builder().name(name)
					.traceIdHigh(this.traceId128 ? createId() : 0L)
					.traceId(id)
					.spanId(id).build();
			if (sampler == null) {
				sampler = this.defaultSampler;
			}
			span = sampledSpan(span, sampler);
			this.spanLogger.logStartedSpan(null, span);
		}
		return continueSpan(span);
	}

	@Override
	public Span detach(Span span) {
		if (span == null) {
			return null;
		}
		Span cur = SpanContextHolder.getCurrentSpan();
		if (!span.equals(cur)) {
			ExceptionUtils.warn("Tried to detach trace span but "
					+ "it is not the current span: " + span
					+ ". You may have forgotten to close or detach " + cur);
		}
		else {
			SpanContextHolder.removeCurrentSpan();
		}
		return span.getSavedSpan();
	}

	@Override
	public Span close(Span span) {
		if (span == null) {
			return null;
		}
		Span cur = SpanContextHolder.getCurrentSpan();
		final Span savedSpan = span.getSavedSpan();
		if (!span.equals(cur)) {
			ExceptionUtils.warn(
					"Tried to close span but it is not the current span: " + span
							+ ".  You may have forgotten to close or detach " + cur);
		}
		else {
			span.stop();
			if (savedSpan != null && span.getParents().contains(savedSpan.getSpanId())) {
				this.spanReporter.report(span);
				this.spanLogger.logStoppedSpan(savedSpan, span);
			}
			else {
				if (!span.isRemote()) {
					this.spanReporter.report(span);
					this.spanLogger.logStoppedSpan(null, span);
				}
			}
			SpanContextHolder.close(new SpanContextHolder.SpanFunction() {
				@Override public void apply(Span span) {
					DefaultTracer.this.spanLogger.logStoppedSpan(savedSpan, span);
				}
			});
		}
		return savedSpan;
	}

	Span createChild(Span parent, String name) {
		long id = createId();
		if (parent == null) {
			Span span = Span.builder().name(name)
					.traceIdHigh(this.traceId128 ? createId() : 0L)
					.traceId(id)
					.spanId(id).build();
			span = sampledSpan(span, this.defaultSampler);
			this.spanLogger.logStartedSpan(null, span);
			return span;
		}
		else {
			if (!isTracing()) {
				SpanContextHolder.push(parent, true);
			}
			Span span = Span.builder().name(name)
					.traceIdHigh(parent.getTraceIdHigh())
					.traceId(parent.getTraceId()).parent(parent.getSpanId()).spanId(id)
					.processId(parent.getProcessId()).savedSpan(parent)
					.exportable(parent.isExportable())
					.baggage(parent.getBaggage())
					.build();
			this.spanLogger.logStartedSpan(parent, span);
			return span;
		}
	}

	Span createChild(Span parent, Span child) {
		long id = createId();
		if (parent == null) {
			Span span = Span.builder(child)
					.traceIdHigh(this.traceId128 ? createId() : 0L)
					.traceId(id)
					.spanId(id).build();
			span = sampledSpan(span, this.defaultSampler);
			this.spanLogger.logStartedSpan(null, span);
			return span;
		}
		else {
			if (!isTracing()) {
				SpanContextHolder.push(parent, true);
			}
			Span span = Span.builder(child)
					.traceIdHigh(parent.getTraceIdHigh())
					.traceId(parent.getTraceId()).parent(parent.getSpanId()).spanId(id)
					.processId(parent.getProcessId()).savedSpan(parent)
					.exportable(parent.isExportable())
					.baggage(parent.getBaggage())
					.build();
			this.spanLogger.logStartedSpan(parent, span);
			return span;
		}
	}

	private Span sampledSpan(Span span, Sampler sampler) {
		if (!sampler.isSampled(span)) {
			// Copy everything, except set exportable to false
			return Span.builder(span)
					.begin(span.getBegin())
					.traceIdHigh(span.getTraceIdHigh())
					.traceId(span.getTraceId())
					.spanId(span.getSpanId())
					.name(span.getName())
					.exportable(false)
					.build();
		}
		return span;
	}

	private long createId() {
		return this.random.nextLong();
	}

	@Override
	public Span continueSpan(Span span) {
		if (span != null) {
			this.spanLogger.logContinuedSpan(span);
		} else {
			return null;
		}
		Span newSpan = createContinuedSpan(span, SpanContextHolder.getCurrentSpan());
		SpanContextHolder.setCurrentSpan(newSpan);
		return newSpan;
	}

	private Span createContinuedSpan(Span span, Span saved) {
		if (saved == null && span.getSavedSpan() != null) {
			saved = span.getSavedSpan();
		}
		return new Span(span, saved);
	}

	@Override
	public Span getCurrentSpan() {
		return SpanContextHolder.getCurrentSpan();
	}

	@Override
	public boolean isTracing() {
		return SpanContextHolder.isTracing();
	}

	@Override
	public void addTag(String key, String value) {
		Span s = getCurrentSpan();
		if (s != null && s.isExportable()) {
			s.tag(key, value);
		}
	}

	/**
	 * Wrap the callable in a TraceCallable, if tracing.
	 *
	 * @return The callable provided, wrapped if tracing, 'callable' if not.
	 */
	@Override
	public <V> Callable<V> wrap(Callable<V> callable) {
		if (isTracing()) {
			return new TraceCallable<>(this, this.spanNamer, callable);
		}
		return callable;
	}

	/**
	 * Wrap the runnable in a TraceRunnable, if tracing.
	 *
	 * @return The runnable provided, wrapped if tracing, 'runnable' if not.
	 */
	@Override
	public Runnable wrap(Runnable runnable) {
		if (isTracing()) {
			return new TraceRunnable(this, this.spanNamer, runnable);
		}
		return runnable;
	}

	@Override
	public SpanBuilder buildSpan(String operationName) {
		return Span.builder().applicationContext(this.applicationContext).name(operationName);
	}

	/**
	 * Shouldn't be used cause we have a Dependency Injection mechanism
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <C> void inject(SpanContext spanContext, Format<C> format, C carrier) {
		if (this.applicationContext != null) {
			Map<String, SpanInjector> injectors = injectors();
			for (SpanInjector injector : injectors.values()) {
				//TODO: This is an aberration of Dependency Injection
				try {
					injector.inject(spanContext, carrier);
				} catch (Exception e) {
					if (log.isDebugEnabled()) {
						log.debug("Exception occurred while trying to invoke the injector [" + injector + "] for carrier [" + carrier + "]");
					}
				}
			}
		}
		throw new UnsupportedOperationException("You've constructed the tracer without support for this method. "
				+ "You have to inject ApplicationContext to make this work");
	}

	private Map<String, SpanInjector> injectors() {
		if (this.injectors.isEmpty()) {
			this.injectors.putAll(this.applicationContext.getBeansOfType(SpanInjector.class));
		}
		return this.injectors;
	}

	/**
	 * Shouldn't be used cause we have a Dependency Injection mechanism
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <C> SpanContext extract(Format<C> format, C carrier) {
		if (this.applicationContext != null) {
			Map<String, SpanExtractor> extractors = extractors();
			for (SpanExtractor extractor : extractors.values()) {
				//TODO: This is an aberration of Dependency Injection
				try {
					extractor.joinTrace(carrier);
				} catch (Exception e) {
					if (log.isDebugEnabled()) {
						log.debug("Exception occurred while trying to invoke the extractor [" + extractor + "] for carrier [" + carrier + "]");
					}
				}
			}
		}
		throw new UnsupportedOperationException("You've constructed the tracer without support for this method. "
				+ "You have to inject ApplicationContext to make this work");
	}

	private Map<String, SpanExtractor> extractors() {
		if (this.extractors.isEmpty()) {
			this.extractors.putAll(this.applicationContext.getBeansOfType(SpanExtractor.class));
		}
		return this.extractors;
	}
}
