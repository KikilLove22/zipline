/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.zipline.samples.emojisearch

import app.cash.zipline.Zipline
import app.cash.zipline.loader.DriverFactory
import app.cash.zipline.loader.OkHttpZiplineHttpClient
import app.cash.zipline.loader.ZiplineLoader
import app.cash.zipline.loader.createZiplineCache
import app.cash.zipline.loader.fetcher.FsCachingFetcher
import app.cash.zipline.loader.fetcher.HttpFetcher
import java.time.Clock
import java.util.concurrent.Executors
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path

class EmojiSearchZipline(
  cacheDir: Path,
) {
  private val executorService = Executors.newSingleThreadExecutor { Thread(it, "Zipline") }
  private val dispatcher = executorService.asCoroutineDispatcher()
  val zipline = Zipline.create(dispatcher)
  private val client = OkHttpClient()
  private val hostApi = RealHostApi(client)

  private val manifestUrl = "http://10.0.2.2:8080/manifest.zipline.json"
  private val moduleName = "./zipline-root-presenters.js"

  private val driver = DriverFactory().createDriver()
  private val ziplineLoader = ZiplineLoader(
    dispatcher = dispatcher,
    httpClient = OkHttpZiplineHttpClient(client),
    fetchers = listOf(
      FsCachingFetcher(
        cache = createZiplineCache(
          driver = driver,
          fileSystem = FileSystem.SYSTEM,
          directory = cacheDir,
          nowMs = { Clock.systemDefaultZone().instant().toEpochMilli() },
        ),
        delegate = HttpFetcher(httpClient = OkHttpZiplineHttpClient(client)),
      ),
    ),
  )

  fun produceModelsIn(
    coroutineScope: CoroutineScope,
    eventFlow: Flow<EmojiSearchEvent>,
    modelsStateFlow: MutableStateFlow<EmojiSearchViewModel>
  ) {
    val job = coroutineScope.launch(dispatcher) {
      ziplineLoader.load(zipline, manifestUrl)
      zipline.bind<HostApi>("hostApi", hostApi)
      zipline.quickJs.evaluate(
        "require('$moduleName').app.cash.zipline.samples.emojisearch.preparePresenters()"
      )
      val presenter = zipline.take<EmojiSearchPresenter>("emojiSearchPresenter")

      val modelsFlow = presenter.produceModels(eventFlow)

      modelsStateFlow.emitAll(modelsFlow)
    }

    job.invokeOnCompletion {
      dispatcher.dispatch(EmptyCoroutineContext) { zipline.close() }
      executorService.shutdown()
    }
  }
}
