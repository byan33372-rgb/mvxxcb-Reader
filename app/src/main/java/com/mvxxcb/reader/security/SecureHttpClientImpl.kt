package com.mvxxcb.reader.security

import com.mvxxcb.reader.common.coroutine.DefaultDispatcherProvider
import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.SystemTimeProvider
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ReaderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 响应体超出 [FetchRequest.maxBytes] 时的内部信号异常（在层内转 RESPONSE_TOO_LARGE，绝不外抛）。 */
private class BodyTooLargeException : IOException("response body exceeds size limit")

/**
 * 安全 HTTP 客户端实现（契约 02 §4.2 / 04 §5 DoD2/3）。
 *
 * 强制能力清单：
 *  - **唯一 OkHttpClient**：私有构造，不提供任何访问器，parser/data 无法自建或获取；
 *    `followRedirects=false`，重定向全部由本类手动逐跳处理，每一跳目标先过
 *    [UrlSecurityValidator.validateRedirect]（防 SSRF 重定向绕过）；
 *  - DNS 钉扎：OkHttp [Dns] 注入 [DnsPinningResolver]，建连地址与校验地址过同一黑名单；
 *  - 重定向上限（TOO_MANY_REDIRECTS）与循环检测（REDIRECT_LOOP，基于已访问 URL 集合）；
 *  - 响应体流式读取，超过 `maxBytes` 立即中断连接并报 RESPONSE_TOO_LARGE；
 *  - 连接/读超时按 [NetworkLimits]；OkHttp 异常逐类映射 [NetworkFailure]；
 *  - 每主机自适应限速（[AdaptiveRateLimiter]，2.0 WP-S）：同主机请求间隔自起始
 *    `minIntervalPerHostMs` 自适应——连续成功降档（floor 1s 合规保底）、失败翻倍退避
 *    （cap 10s）、HTTP 429 按 Retry-After 一次性冷却（无 Retry-After 或超上限时间隔
 *    顶到 cap）；等待经协程 delay 挂起，可被取消打断；同主机并发请求严格串行；
 *  - 协程取消桥接：挂起等待阶段经 `suspendCancellableCoroutine` + `invokeOnCancellation`
 *    触发 `call.cancel()`；正文读取是阻塞 IO（协程自身无法被调度打断），另设监视协程，
 *    外层 fetch 协程一旦取消立即对当前 [Call] 执行 `call.cancel()` 关闭底层连接，
 *    使阻塞读以 IOException 立即中断；
 *  - 取消沿协程语义重抛 [CancellationException]（协程控制流，不属于业务失败）；
 *    业务失败一律返回 `AppResult.Failure(ReaderError)`，绝不抛异常跨层。
 *
 * 契约边界说明：
 *  - `NetworkLimits.maxRequestsPerTask` 由 parser 管线按任务计数（ParseOptions.maxTotalRequests），
 *    客户端无任务边界概念，不做重复计数；
 *  - 跨主机重定向时丢弃 Cookie/Authorization 头（防凭据外泄）；一期无登录态，风险本就极低；
 *  - 错误对象中的 url 一律经 [UrlSecurityValidatorImpl.redactQuery] 脱敏（01 §8 隐私最小化）。
 */
class SecureHttpClientImpl(
    private val validator: UrlSecurityValidator,
    private val limits: NetworkLimits = NetworkLimits.DEFAULT,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    /** 可空：测试用空日志器；生产装配注入 container.logger。 */
    private val logger: AppLogger? = null,
    /** DNS 钉扎实现；默认私网黑名单版，单测可注入放行 MockWebServer 的实现。 */
    dns: Dns = DnsPinningResolver(),
) : SecureHttpClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(java.time.Duration.ofMillis(limits.connectTimeoutMs))
        .readTimeout(java.time.Duration.ofMillis(limits.readTimeoutMs))
        .followRedirects(false)
        .followSslRedirects(false)
        // 关闭自动重试：避免静默重试绕过同主机限流与逐跳校验
        .retryOnConnectionFailure(false)
        .dns(dns)
        .build()

    /** 每主机自适应限速器（起始间隔/降档/退避/429 冷却参数见 [NetworkLimits]）。 */
    private val rateLimiter = AdaptiveRateLimiter(limits, timeProvider)

    override suspend fun fetch(request: FetchRequest): AppResult<FetchResult> {
        val startAt = timeProvider.nowMillis()
        return try {
            withContext(dispatchers.io) {
                doFetch(request, startAt)
            }
        } catch (e: CancellationException) {
            // 协程取消属于控制流而非业务失败：沿结构化并发语义重抛（底层 call 已被取消中断）
            throw e
        } catch (e: Throwable) {
            logger?.w(TAG, "fetch 失败: ${UrlSecurityValidatorImpl.redactQuery(request.url)} ${e.javaClass.simpleName}", e)
            AppResult.Failure(mapException(e, request.url))
        }
    }

    private suspend fun doFetch(request: FetchRequest, startAt: Long): AppResult<FetchResult> {
        // 1) 首次校验（含 DNS 解析级黑名单）
        val firstUrl = when (val check = validator.validate(request.url)) {
            is UrlCheckResult.Allowed -> check.normalizedUrl
            is UrlCheckResult.Blocked -> {
                logger?.w(TAG, "已拦截请求: ${check.reason} ${UrlSecurityValidatorImpl.redactQuery(request.url)}")
                return AppResult.Failure(
                    ReaderError.Security(check.reason, UrlSecurityValidatorImpl.redactQuery(request.url), check.detail),
                )
            }
        }

        val effectiveMaxBytes = if (request.maxBytes > 0) request.maxBytes else limits.maxResponseBytes

        // 2) 取消监视 + 手动逐跳重定向。watcher 挂起等待取消；fetch 协程被取消时
        //    watcher 立即恢复并对"当前 call"执行 cancel()，中断可能正阻塞在正文
        //    读取上的底层连接（阻塞读不受协程调度影响，只能靠关连接打断）。
        val currentCall = AtomicReference<Call?>(null)
        return coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    currentCall.get()?.cancel()
                }
            }
            try {
                followAndRead(request, firstUrl, effectiveMaxBytes, startAt, currentCall)
            } finally {
                watcher.cancel()   // 正常结束时也会触发 finally 内 cancel()，对已完结 call 是无害空操作
            }
        }
    }

    private suspend fun followAndRead(
        request: FetchRequest,
        firstUrl: String,
        effectiveMaxBytes: Long,
        startAt: Long,
        currentCall: AtomicReference<Call?>,
    ): AppResult<FetchResult> {
        var currentUrl = firstUrl
        var currentHttpUrl = currentUrl.toHttpUrl()
        var headers = request.headers
        val redirectChain = mutableListOf(currentUrl)
        val visited = mutableSetOf(currentUrl)
        var redirects = 0

        while (true) {
            rateLimiter.acquire(currentHttpUrl.host)

            val call = client.newCall(buildRequest(currentHttpUrl, headers))
            currentCall.set(call)
            val response = try {
                execute(call)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // 建连/响应头阶段传输失败（DNS/连接拒绝/超时/重置）→ 记为站点侧失败退避后重抛
                rateLimiter.onFailure(currentHttpUrl.host)
                throw e
            }
            try {
                if (response.isRedirect) {
                    val location = response.header("location")
                    val code = response.code
                    response.close()
                    if (location == null) {
                        return failure(NetworkFailure.HTTP_ERROR_STATUS, currentUrl, code, "重定向响应缺少 Location")
                    }
                    redirects++
                    if (redirects > limits.maxRedirects) {
                        return failure(NetworkFailure.TOO_MANY_REDIRECTS, currentUrl, code, "重定向次数超过上限 ${limits.maxRedirects}")
                    }
                    val target = currentHttpUrl.resolve(location)
                        ?: return failure(NetworkFailure.HTTP_ERROR_STATUS, currentUrl, code, "Location 无法解析为绝对 URL")
                    val targetUrl = target.toString()
                    if (!visited.add(targetUrl)) {
                        return failure(NetworkFailure.REDIRECT_LOOP, targetUrl, code, "检测到重定向循环")
                    }
                    when (val vr = validator.validateRedirect(currentUrl, targetUrl)) {
                        is UrlCheckResult.Allowed -> {
                            // 跨主机重定向丢弃敏感头
                            headers = if (target.host != currentHttpUrl.host) headers.minusSensitive() else headers
                            currentUrl = vr.normalizedUrl
                            currentHttpUrl = currentUrl.toHttpUrl()
                            redirectChain.add(currentUrl)
                        }
                        is UrlCheckResult.Blocked -> {
                            logger?.w(TAG, "重定向目标被拦截: ${vr.reason} ${UrlSecurityValidatorImpl.redactQuery(targetUrl)}")
                            return AppResult.Failure(
                                ReaderError.Security(
                                    vr.reason,
                                    UrlSecurityValidatorImpl.redactQuery(targetUrl),
                                    "重定向目标被安全校验拒绝: ${vr.detail}",
                                ),
                            )
                        }
                    }
                    continue
                }

                // 3) 终响应：读取正文（限额/超时/空体判定在 readAndBuild 内）。
                //    正文阶段逃逸的传输异常（连接重置等）同样记为失败退避后重抛。
                return try {
                    readAndBuild(response, currentHttpUrl.host, currentUrl, redirectChain, effectiveMaxBytes, startAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    rateLimiter.onFailure(currentHttpUrl.host)
                    throw e
                }
            } finally {
                // 各分支均已显式 close；此处兜底（Response 重复 close 幂等安全）
                response.close()
            }
        }
    }

    // ———————————————————— 响应读取与组装 ————————————————————

    private suspend fun readAndBuild(
        response: Response,
        host: String,
        finalUrl: String,
        redirectChain: List<String>,
        maxBytes: Long,
        startAt: Long,
    ): AppResult<FetchResult> {
        val code = response.code
        val headersElapsedMs = (timeProvider.nowMillis() - startAt).coerceAtLeast(0)

        if (code >= 400) {
            // 限速反馈：429 按 Retry-After 冷却；其余错误态退避（RESPONSE_TOO_LARGE 例外，
            // 见下——客户端自设上限，非站点限流信号，不改节奏）
            if (code == 429) {
                rateLimiter.onRateLimited(host, parseRetryAfter(response.header("Retry-After")))
            } else {
                rateLimiter.onFailure(host)
            }
            response.close()
            return failure(NetworkFailure.HTTP_ERROR_STATUS, finalUrl, code, "HTTP 状态码 $code")
        }

        val body = response.body
            ?: return run {
                rateLimiter.onFailure(host)
                response.close()
                failure(NetworkFailure.EMPTY_RESPONSE, finalUrl, code, "无响应体")
            }

        // Content-Length 预检（gzip 透传解压时 OkHttp 报 -1，由流式限额兜底）。
        // RESPONSE_TOO_LARGE 属客户端自设上限而非站点限流/故障信号，限速器记中性（不改节奏）。
        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) {
            response.close()
            return failure(NetworkFailure.RESPONSE_TOO_LARGE, finalUrl, code, "响应体 $declaredLength 字节超过上限 $maxBytes")
        }

        val mediaType = body.contentType()
        val bytes = try {
            readBodyLimited(body.source(), maxBytes)
        } catch (e: SocketTimeoutException) {
            rateLimiter.onFailure(host)
            return failure(NetworkFailure.READ_TIMEOUT, finalUrl, code, "读取响应体超时", e)
        } catch (e: BodyTooLargeException) {
            return failure(NetworkFailure.RESPONSE_TOO_LARGE, finalUrl, code, "响应体超过上限 $maxBytes，已中断", e)
        } catch (e: IOException) {
            throw e   // 交给统一异常映射（followAndRead 捕获后先记失败退避）
        } finally {
            response.close()
        }

        if (bytes.isEmpty()) {
            rateLimiter.onFailure(host)
            return failure(NetworkFailure.EMPTY_RESPONSE, finalUrl, code, "响应体为空")
        }

        val headerMap = LinkedHashMap<String, List<String>>()
        for (name in response.headers.names()) {
            headerMap[name.lowercase()] = response.headers.values(name)
        }

        // elapsed 覆盖到正文读取完成（失败路径用 headersElapsedMs 即可）
        val elapsedMs = (timeProvider.nowMillis() - startAt).coerceAtLeast(headersElapsedMs)

        rateLimiter.onSuccess(host)   // 完整成功响应：累计连续成功 → 触发自适应降档

        return AppResult.Success(
            FetchResult(
                finalUrl = finalUrl,
                statusCode = code,
                headers = headerMap,
                bodyBytes = bytes,
                contentType = mediaType?.let { "${it.type}/${it.subtype}" },
                declaredCharset = mediaType?.charset()?.name(),
                elapsedMs = elapsedMs,
                redirectChain = redirectChain.toList(),
                fromCache = false,
            ),
        )
    }

    /** 流式读取，超过 maxBytes 立即抛 [BodyTooLargeException]（连接随后关闭）。 */
    private suspend fun readBodyLimited(source: okio.BufferedSource, maxBytes: Long): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()   // 长读取中的协作式取消出口
            val read = source.read(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw BodyTooLargeException()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ———————————————————— 限流 / 执行 / 请求构造 ————————————————————

    /**
     * Retry-After 头解析（429 冷却）：仅接受正整数秒（RFC 7231 也允许 HTTP-date，
     * 采集场景不解析日期形态——返回 null 走"间隔顶到 cap"分支，更保守）。
     */
    private fun parseRetryAfter(headerValue: String?): Long? =
        headerValue?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** 桥接协程取消 → call.cancel()；失败/成功经回调恢复挂起点。 */
    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                }
            },
        )
    }

    private fun buildRequest(url: HttpUrl, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        for ((name0, value) in headers) {
            val name = name0.trim()
            if (name.isEmpty()) continue
            // OkHttp 受控/危险头：禁止调用方覆盖
            if (name.equals("host", true) || name.equals("content-length", true) ||
                name.equals("connection", true) || name.equals("transfer-encoding", true)
            ) {
                continue
            }
            try {
                builder.header(name, value)
            } catch (_: IllegalArgumentException) {
                // 非法头名/值：跳过而非失败整请求（parse 侧规则可控，防御第三方规则输入）
            }
        }
        return builder.build()
    }

    private fun Map<String, String>.minusSensitive(): Map<String, String> = filterKeys { key ->
        val k = key.trim().lowercase()
        k != "cookie" && k != "authorization"
    }

    // ———————————————————— 异常映射 ————————————————————

    /**
     * OkHttp/IO 异常 → [ReaderError]。
     * execute() 挂起点抛出的 SocketTimeoutException 必然发生在收到响应头之前 → CONNECT_TIMEOUT；
     * 正文读取阶段的 SocketTimeoutException 已在 readAndBuild 内转为 READ_TIMEOUT。
     */
    private fun mapException(e: Throwable, url: String): ReaderError {
        val redacted = UrlSecurityValidatorImpl.redactQuery(url)
        return when (e) {
            is BodyTooLargeException -> ReaderError.Network(
                NetworkFailure.RESPONSE_TOO_LARGE, redacted, null, "响应体超过大小上限", e,
            )
            is UnknownHostException -> ReaderError.Network(
                NetworkFailure.DNS_FAILURE, redacted, null, "DNS 解析失败", e,
            )
            is ConnectException -> ReaderError.Network(
                NetworkFailure.CONNECTION_REFUSED, redacted, null, "连接被拒绝", e,
            )
            is SocketTimeoutException -> ReaderError.Network(
                NetworkFailure.CONNECT_TIMEOUT, redacted, null, "连接超时", e,
            )
            is SSLException -> ReaderError.Network(
                NetworkFailure.TLS_ERROR, redacted, null, "TLS 握手/证书异常", e,
            )
            is MalformedURLException, is IllegalArgumentException -> ReaderError.Network(
                NetworkFailure.INVALID_URL, redacted, null, "URL 非法", e,
            )
            is InterruptedIOException ->
                if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    ReaderError.Network(NetworkFailure.CONNECT_TIMEOUT, redacted, null, "连接超时", e)
                } else {
                    ReaderError.Network(NetworkFailure.CANCELLED, redacted, null, "请求被中断", e)
                }
            is IOException ->
                if (e.message?.contains("Canceled", ignoreCase = false) == true) {
                    ReaderError.Network(NetworkFailure.CANCELLED, redacted, null, "请求被取消", e)
                } else {
                    // 连接重置/流意外终止等无对应枚举项的错误 → Unknown（01 §7 错误树兜底桶）
                    ReaderError.Unknown("网络 IO 异常: ${e.javaClass.simpleName}", e)
                }
            else -> ReaderError.Unknown("网络异常: ${e.javaClass.simpleName}", e)
        }
    }

    private fun failure(failure: NetworkFailure, url: String, statusCode: Int?, detail: String?): AppResult<FetchResult> =
        AppResult.Failure(ReaderError.Network(failure, UrlSecurityValidatorImpl.redactQuery(url), statusCode, detail))

    private fun failure(
        failure: NetworkFailure,
        url: String,
        statusCode: Int?,
        detail: String?,
        cause: Throwable?,
    ): AppResult<FetchResult> =
        AppResult.Failure(ReaderError.Network(failure, UrlSecurityValidatorImpl.redactQuery(url), statusCode, detail, cause))

    private companion object {
        const val TAG = "SecureHttpClient"
    }
}
