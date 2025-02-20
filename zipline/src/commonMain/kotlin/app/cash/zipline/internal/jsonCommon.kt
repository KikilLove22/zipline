/*
 * Copyright (C) 2022 Block, Inc.
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
package app.cash.zipline.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/*
 * In QuickJS (version 2021-03-27) it's faster to ask the JavaScript engine to parse JSON to a
 * dynamic value (ie. JSON.parse()) and bind that to a Kotlin Objects, than to parse and bind all
 * in Kotlin/JS. In one sample this optimization reduced overall execution time by 35%.
 */

expect fun <T> Json.decodeFromStringFast(deserializer: KSerializer<T>, string: String): T

expect fun <T> Json.encodeToStringFast(serializer: KSerializer<T>, value: T): String
