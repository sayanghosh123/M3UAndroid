package com.m3u.data.parser.xtream

import com.m3u.data.api.OkhttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import javax.inject.Inject

internal class XtreamParserImpl @Inject constructor(
	@OkhttpClient(true) okHttpClient: OkHttpClient,
) : XtreamParser {
	private val delegate = dev.oxyroid.parser.xtream.XtreamParserImpl(okHttpClient)

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