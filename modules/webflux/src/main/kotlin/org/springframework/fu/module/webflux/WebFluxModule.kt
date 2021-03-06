/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.fu.module.webflux

import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.WebApplicationType
import org.springframework.boot.web.embedded.jetty.JettyReactiveWebServerFactory
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.embedded.tomcat.TomcatReactiveWebServerFactory
import org.springframework.boot.web.embedded.undertow.UndertowReactiveWebServerFactory
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.support.beans
import org.springframework.context.support.registerBean
import org.springframework.core.codec.CharSequenceEncoder
import org.springframework.core.codec.ResourceDecoder
import org.springframework.core.codec.StringDecoder
import org.springframework.fu.*
import org.springframework.http.codec.CodecConfigurer
import org.springframework.http.codec.ResourceHttpMessageWriter
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.server.*
import org.springframework.web.reactive.handler.WebFluxResponseStatusExceptionHandler
import org.springframework.web.server.WebFilter
import org.springframework.web.server.i18n.AcceptHeaderLocaleContextResolver
import reactor.core.publisher.Mono

open class WebFluxServerModule(private val init: WebFluxServerModule.() -> Unit,
							   private val serverModule: WebServerModule): AbstractModule() {

	private val builder = HandlerStrategies.empty()

	private val routes = mutableListOf<() -> RouterFunction<ServerResponse>>()

	override fun initialize(context: GenericApplicationContext) {
		if (context.containsBeanDefinition("webHandler")) {
			throw IllegalStateException("Only one server per application is supported")
		}
		this.context = context
		init()
		initializers.add(serverModule)
		initializers.add(beans {
			bean("webHandler") {
				builder.exceptionHandler(WebFluxResponseStatusExceptionHandler())
				builder.localeContextResolver(AcceptHeaderLocaleContextResolver())
				initializers.filterIsInstance<WebFluxCodecsModule>()
						.flatMap { codecs -> codecs.initializers }
						.filterIsInstance<WebFluxCodecModule>()
						.apply {
							if (isEmpty())
								builder.codecs { defaultCodecs(it) }
							else
								forEach { codec ->  builder.codecs { codec.invoke(it) } }
						}

				try {
					builder.viewResolver(ref())
				}
				catch (ex: NoSuchBeanDefinitionException) {}
				val router = if (!routes.isEmpty()) {
					routes.map { it() }.reduce(RouterFunction<ServerResponse>::and)
				}
				else {
					RouterFunction<ServerResponse> { Mono.empty() }
				}

				RouterFunctions.toHttpHandler(router, builder.build())
			}
		})
		super.initialize(context)
	}

	fun codecs(init: WebFluxCodecsModule.() -> Unit =  {}) {
		initializers.add(WebFluxCodecsModule(init))
	}

	fun filter(filter: WebFilter) {
		builder.webFilter(filter)
	}

	fun router(routes: (RouterFunctionDsl.() -> Unit)) {
		this.routes.add(RouterFunctionDsl(routes))
	}

	fun include(router: () -> RouterFunction<ServerResponse>) {
		this.routes.add(router)
	}

	interface WebServerModule: Module {
		val baseUrl: String
	}

	abstract class AbstractWebServerModule(port: Int, host: String = "0.0.0.0"): AbstractModule(), WebServerModule {
		override val baseUrl = "http://$host:$port"
	}

	class NettyServerModule(private val port: Int = 8080) : AbstractWebServerModule(port) {

		override fun initialize(context: GenericApplicationContext) {
			context.registerBean {
				NettyReactiveWebServerFactory(port)
			}
		}
	}

	class TomcatServerModule(private val port: Int) : AbstractWebServerModule(port) {

		override fun initialize(context: GenericApplicationContext) {
			context.registerBean {
				TomcatReactiveWebServerFactory(port)
			}
		}
	}

	class JettyServerModule(
			private val port: Int) : AbstractWebServerModule(port) {

		override fun initialize(context: GenericApplicationContext) {
			context.registerBean {
				JettyReactiveWebServerFactory(port)
			}
		}
	}

	/**
	 * @author Ruslan Ibragimov
	 */
	class UndertowServerModule(private val port: Int): AbstractWebServerModule(port) {

		override fun initialize(context: GenericApplicationContext) {
			context.registerBean {
				UndertowReactiveWebServerFactory(port)
			}
		}
	}

}

class WebFluxClientModule(private val init: WebFluxClientModule.() -> Unit, val baseUrl: String?, val name: String?) : AbstractModule() {

	private val clientBuilder = WebClient.builder()

	override fun initialize(context: GenericApplicationContext) {
		initializers.add(beans {

			bean(name = name) {
				if (baseUrl != null) {
					clientBuilder.baseUrl(baseUrl)
				}
				val exchangeStrategiesBuilder = ExchangeStrategies.builder()
				initializers.filterIsInstance<WebFluxCodecsModule>()
						.flatMap { codecs -> codecs.initializers }
						.filterIsInstance<WebFluxCodecModule>()
						.apply {
							if (isEmpty())
								exchangeStrategiesBuilder.codecs { defaultCodecs(it) }
							else
								forEach { codec ->  exchangeStrategiesBuilder.codecs { codec.invoke(it) } }
						}

				clientBuilder.exchangeStrategies(exchangeStrategiesBuilder.build())
				clientBuilder.build()
			}
		})
		super.initialize(context)
	}

	fun codecs(init: WebFluxCodecsModule.() -> Unit =  {}) {
		initializers.add(WebFluxCodecsModule(init))
	}
}

class WebFluxCodecsModule(private val init: WebFluxCodecsModule.() -> Unit): AbstractModule() {
	override fun initialize(context: GenericApplicationContext) {
		init()
		super.initialize(context)
	}

	fun string() {
		initializers.add(StringCodecModule())
	}

	fun resource() {
		initializers.add(ResourceCodecModule())
	}
}

class StringCodecModule : WebFluxCodecModule, AbstractModule() {
	override fun invoke(configurer: CodecConfigurer) {
		with(configurer.customCodecs()) {
			encoder(CharSequenceEncoder.textPlainOnly())
			decoder(StringDecoder.textPlainOnly())
		}
	}
}

interface WebFluxCodecModule: Module, (CodecConfigurer) -> (Unit)

class ResourceCodecModule : WebFluxCodecModule, AbstractModule() {
	override fun invoke(configurer: CodecConfigurer) {
		with(configurer.customCodecs()) {
			decoder(ResourceDecoder())
		}
	}
}

private fun defaultCodecs(codecConfigurer: CodecConfigurer) = with(codecConfigurer.customCodecs()) {
	encoder(CharSequenceEncoder.textPlainOnly())
	writer(ResourceHttpMessageWriter())
	decoder(ResourceDecoder())
	decoder(StringDecoder.textPlainOnly())
}

fun ApplicationDsl.netty(port: Int = 8080): WebFluxServerModule.WebServerModule = WebFluxServerModule.NettyServerModule(port)

fun ApplicationDsl.tomcat(port: Int = 8080): WebFluxServerModule.WebServerModule = WebFluxServerModule.TomcatServerModule(port)

fun ApplicationDsl.undertow(port: Int = 8080): WebFluxServerModule.WebServerModule = WebFluxServerModule.UndertowServerModule(port)

fun ApplicationDsl.jetty(port: Int = 8080): WebFluxServerModule.WebServerModule = WebFluxServerModule.JettyServerModule(port)

fun ApplicationDsl.server(server: WebFluxServerModule.WebServerModule, init: WebFluxServerModule.() -> Unit =  {}) {
	initializers.add(WebFluxServerModule(init, server))
}

fun ApplicationDsl.client(baseUrl: String? = null, name: String? = null, init: WebFluxClientModule.() -> Unit =  {}) {
	initializers.add(WebFluxClientModule(init, baseUrl, name))
}
