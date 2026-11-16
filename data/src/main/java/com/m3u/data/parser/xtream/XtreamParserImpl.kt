package com.m3u.data.parser.xtream

import com.m3u.data.api.OkhttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

internal class XtreamParserImpl @Inject constructor(
	@OkhttpClient(true) okHttpClient: OkHttpClient,
) : XtreamParser {
	private val httpStatusHistory = ConcurrentLinkedQueue<Int>()

	private val trackedClient: OkHttpClient = okHttpClient.newBuilder()
		.addNetworkInterceptor { chain ->
			val response = chain.proceed(chain.request())
			httpStatusHistory.add(response.code)
			while (httpStatusHistory.size > MAX_TRACKED_STATUS_CODES) {
				httpStatusHistory.poll()
			}
			response
		}
		.build()

	private val delegate = dev.oxyroid.parser.xtream.XtreamParserImpl(trackedClient)

	override fun clearHttpStatusHistory() {
		httpStatusHistory.clear()
	}

	override fun drainHttpStatusHistory(): List<Int> = buildList {
		while (true) {
			val code = httpStatusHistory.poll() ?: break
			add(code)
		}
	}

	private companion object {
		const val MAX_TRACKED_STATUS_CODES = 64
	}

	override suspend fun getSeriesInfoOrThrow(
		input: XtreamInput,
		seriesId: Int,
	): XtreamChannelInfo = delegate.getSeriesInfoOrThrow(input, seriesId)

	override fun parse(input: XtreamInput): Flow<XtreamData> = flow {
		var index = 0
		for (item in delegate.parse(input)) {
			emit(item)
			index++
			if (index and 0xFF == 0) yield()
		}
	}.flowOn(Dispatchers.IO)

	override suspend fun getInfo(input: XtreamInput): XtreamInfo = delegate.getInfo(input)

	override suspend fun getXtreamOutput(input: XtreamInput): XtreamOutput =
		delegate.getXtreamOutput(input)
}