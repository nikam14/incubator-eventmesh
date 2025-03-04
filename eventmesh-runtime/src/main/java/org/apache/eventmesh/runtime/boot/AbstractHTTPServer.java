/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.boot;

import org.apache.eventmesh.common.Constants;
import org.apache.eventmesh.common.ThreadPoolFactory;
import org.apache.eventmesh.common.protocol.http.HttpCommand;
import org.apache.eventmesh.common.protocol.http.HttpEventWrapper;
import org.apache.eventmesh.common.protocol.http.body.Body;
import org.apache.eventmesh.common.protocol.http.common.EventMeshRetCode;
import org.apache.eventmesh.common.protocol.http.common.ProtocolKey;
import org.apache.eventmesh.common.protocol.http.common.ProtocolVersion;
import org.apache.eventmesh.common.protocol.http.common.RequestCode;
import org.apache.eventmesh.common.protocol.http.header.Header;
import org.apache.eventmesh.common.utils.AssertUtils;
import org.apache.eventmesh.common.utils.JsonUtils;
import org.apache.eventmesh.runtime.common.Pair;
import org.apache.eventmesh.runtime.configuration.EventMeshHTTPConfiguration;
import org.apache.eventmesh.runtime.constants.EventMeshConstants;
import org.apache.eventmesh.runtime.core.protocol.http.async.AsyncContext;
import org.apache.eventmesh.runtime.core.protocol.http.processor.HandlerService;
import org.apache.eventmesh.runtime.core.protocol.http.processor.inf.EventProcessor;
import org.apache.eventmesh.runtime.core.protocol.http.processor.inf.HttpRequestProcessor;
import org.apache.eventmesh.runtime.metrics.http.HTTPMetricsServer;
import org.apache.eventmesh.runtime.util.RemotingHelper;
import org.apache.eventmesh.runtime.util.TraceUtils;
import org.apache.eventmesh.runtime.util.Utils;
import org.apache.eventmesh.trace.api.common.EventMeshTraceConstants;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.entity.ContentType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;


@Slf4j
public abstract class AbstractHTTPServer extends AbstractRemotingServer {

    private HandlerService handlerService;


    private HTTPMetricsServer metrics;

    private static final DefaultHttpDataFactory DEFAULT_HTTP_DATA_FACTORY = new DefaultHttpDataFactory(false);

    private final transient AtomicBoolean started = new AtomicBoolean(false);

    private final transient boolean useTLS;

    private Boolean useTrace = false; //Determine whether trace is enabled

    private final transient EventMeshHTTPConfiguration eventMeshHttpConfiguration;

    private final transient ThreadPoolExecutor asyncContextCompleteHandler =
        ThreadPoolFactory.createThreadPoolExecutor(10, 10, "EventMesh-http-asyncContext");

    private static final int MAX_CONNECTIONS = 20_000;

    static {
        DiskAttribute.deleteOnExitTemporaryFile = false;
    }

    protected final transient Map<String/* request code */, Pair<HttpRequestProcessor, ThreadPoolExecutor>>
        processorTable = new ConcurrentHashMap<>(64);

    protected final transient Map<String/* request uri */, Pair<EventProcessor, ThreadPoolExecutor>>
        eventProcessorTable = new ConcurrentHashMap<>(64);

    private HttpConnectionHandler httpConnectionHandler;

    private HTTPHandler httpHandler;

    public AbstractHTTPServer(final int port, final boolean useTLS,
        final EventMeshHTTPConfiguration eventMeshHttpConfiguration) {
        super();
        this.setPort(port);
        this.useTLS = useTLS;
        this.eventMeshHttpConfiguration = eventMeshHttpConfiguration;
    }


    public void setUseTrace(final Boolean useTrace) {
        this.useTrace = useTrace;
    }


    public Boolean getUseTrace() {
        return useTrace;
    }

    public void setHandlerService(final HandlerService handlerService) {
        this.handlerService = handlerService;
    }

    public HTTPMetricsServer getMetrics() {
        return metrics;
    }

    public void setMetrics(final HTTPMetricsServer metrics) {
        this.metrics = metrics;
    }

    public HandlerService getHandlerService() {
        return handlerService;
    }

    public void sendError(final ChannelHandlerContext ctx, final HttpResponseStatus status) {
        final FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        final HttpHeaders responseHeaders = response.headers();
        responseHeaders.add(
            HttpHeaderNames.CONTENT_TYPE, String.format("text/plain; charset=%s", EventMeshConstants.DEFAULT_CHARSET)
        );
        responseHeaders.add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        responseHeaders.add(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    public void sendResponse(final ChannelHandlerContext ctx, final DefaultFullHttpResponse response) {
        ctx.writeAndFlush(response).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (log.isWarnEnabled()) {
                    log.warn("send response to [{}] fail, will close this channel",
                        RemotingHelper.parseChannelRemoteAddr(f.channel()));
                }
                f.channel().close();
            }
        });
    }

    @Override
    public void start() throws Exception {

        initSharableHandlers();

        final Runnable runnable = () -> {
            final ServerBootstrap bootstrap = new ServerBootstrap();
            try {
                bootstrap.group(this.getBossGroup(), this.getIoGroup())
                    .channel(useEpoll() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                    .childHandler(new HttpsServerInitializer(useTLS ? SSLContextFactory.getSslContext(eventMeshHttpConfiguration) : null))
                    .childOption(ChannelOption.SO_KEEPALIVE, Boolean.TRUE);

                if (log.isInfoEnabled()) {
                    log.info("HTTPServer[port={}] started.", this.getPort());
                }

                bootstrap.bind(this.getPort())
                    .channel()
                    .closeFuture()
                    .sync();
            } catch (Exception e) {
                log.error("HTTPServer start error!", e);
                try {
                    shutdown();
                } catch (Exception ex) {
                    log.error("HTTPServer shutdown error!", ex);
                }
            }
        };

        final Thread thread = new Thread(runnable, "EventMesh-http-server");
        thread.setDaemon(true);
        thread.start();
        started.compareAndSet(false, true);
    }

    @Override
    public void shutdown() throws Exception {
        super.shutdown();
        started.compareAndSet(true, false);
    }

    public void registerProcessor(final Integer requestCode, final HttpRequestProcessor processor,
        final ThreadPoolExecutor executor) {
        AssertUtils.notNull(requestCode, "requestCode can't be null");
        AssertUtils.notNull(processor, "processor can't be null");
        AssertUtils.notNull(executor, "executor can't be null");
        this.processorTable.put(requestCode.toString(), new Pair<>(processor, executor));
    }

    public void registerProcessor(final String requestURI, final EventProcessor processor,
        final ThreadPoolExecutor executor) {
        AssertUtils.notNull(requestURI, "requestURI can't be null");
        AssertUtils.notNull(processor, "processor can't be null");
        AssertUtils.notNull(executor, "executor can't be null");
        this.eventProcessorTable.put(requestURI, new Pair<>(processor, executor));
    }

    /**
     * Validate request, return error status.
     *
     * @param httpRequest
     * @return if request is validated return null else return error status
     */
    private HttpResponseStatus validateHttpRequest(final HttpRequest httpRequest) {
        if (!started.get()) {
            return HttpResponseStatus.SERVICE_UNAVAILABLE;
        }

        if (!httpRequest.decoderResult().isSuccess()) {
            return HttpResponseStatus.BAD_REQUEST;
        }

        if (!HttpMethod.GET.equals(httpRequest.method()) && !HttpMethod.POST.equals(httpRequest.method())) {
            return HttpResponseStatus.METHOD_NOT_ALLOWED;
        }

        if (!ProtocolVersion.contains(httpRequest.headers().get(ProtocolKey.VERSION))) {
            return HttpResponseStatus.BAD_REQUEST;
        }

        return null;
    }

    /**
     * Inject ip and protocol version, if the protocol version is empty, set default to {@link ProtocolVersion#V1}.
     *
     * @param ctx
     * @param httpRequest
     */
    private void preProcessHttpRequestHeader(final ChannelHandlerContext ctx, final HttpRequest httpRequest) {
        final long startTime = System.currentTimeMillis();
        final HttpHeaders requestHeaders = httpRequest.headers();

        requestHeaders.set(EventMeshConstants.REQ_C2EVENTMESH_TIMESTAMP, startTime);
        if (StringUtils.isBlank(requestHeaders.get(ProtocolKey.VERSION))) {
            requestHeaders.set(ProtocolKey.VERSION, ProtocolVersion.V1.getVersion());
        }
        requestHeaders.set(ProtocolKey.ClientInstanceKey.IP,
            RemotingHelper.parseChannelRemoteAddr(ctx.channel()));
        requestHeaders.set(EventMeshConstants.REQ_SEND_EVENTMESH_IP, eventMeshHttpConfiguration.getEventMeshServerIp());
    }

    /**
     * Parse request body to map
     *
     * @param httpRequest
     * @return
     */
    private Map<String, Object> parseHttpRequestBody(final HttpRequest httpRequest) throws IOException {
        final long bodyDecodeStart = System.currentTimeMillis();
        final Map<String, Object> httpRequestBody = new HashMap<>();

        if (HttpMethod.GET.equals(httpRequest.method())) {
            new QueryStringDecoder(httpRequest.uri())
                .parameters()
                .forEach((key, value) -> httpRequestBody.put(key, value.get(0)));
        } else if (HttpMethod.POST.equals(httpRequest.method())) {
            final HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(DEFAULT_HTTP_DATA_FACTORY, httpRequest);
            for (final InterfaceHttpData parm : decoder.getBodyHttpDatas()) {
                if (InterfaceHttpData.HttpDataType.Attribute == parm.getHttpDataType()) {
                    final Attribute data = (Attribute) parm;
                    httpRequestBody.put(data.getName(), data.getValue());
                }
            }
            decoder.destroy();
        }
        metrics.getSummaryMetrics().recordDecodeTimeCost(System.currentTimeMillis() - bodyDecodeStart);
        return httpRequestBody;
    }

    @Sharable
    private class HTTPHandler extends ChannelInboundHandlerAdapter {

        /**
         * Is called for each message of type {@link HttpRequest}.
         *
         * @param ctx the {@link ChannelHandlerContext} which this {@link ChannelInboundHandlerAdapter} belongs to
         * @param msg the message to handle
         * @throws Exception is thrown if an error occurred
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof HttpRequest)) {
                return;
            }

            HttpRequest httpRequest = (HttpRequest) msg;

            if (Objects.nonNull(handlerService) && handlerService.isProcessorWrapper(httpRequest)) {
                handlerService.handler(ctx, httpRequest, asyncContextCompleteHandler);
                return;
            }

            try {

                Span span = null;
                preProcessHttpRequestHeader(ctx, httpRequest);

                final Map<String, Object> headerMap = Utils.parseHttpHeader(httpRequest);
                final HttpResponseStatus errorStatus = validateHttpRequest(httpRequest);
                if (errorStatus != null) {
                    sendError(ctx, errorStatus);
                    span = TraceUtils.prepareServerSpan(headerMap,
                        EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_SERVER_SPAN, false);
                    TraceUtils.finishSpanWithException(span, headerMap, errorStatus.reasonPhrase(), null);
                    return;
                }
                metrics.getSummaryMetrics().recordHTTPRequest();

                boolean useRequestURI = false;
                for (final String processURI : eventProcessorTable.keySet()) {
                    if (httpRequest.uri().startsWith(processURI)) {
                        useRequestURI = true;
                        break;
                    }
                }

                if (useRequestURI) {
                    if (useTrace) {
                        span.setAttribute(SemanticAttributes.HTTP_METHOD,
                            httpRequest.method() == null ? "" : httpRequest.method().name());
                        span.setAttribute(SemanticAttributes.HTTP_FLAVOR,
                            httpRequest.protocolVersion() == null ? "" : httpRequest.protocolVersion().protocolName());
                        span.setAttribute(SemanticAttributes.HTTP_URL,
                            httpRequest.uri());
                    }
                    final HttpEventWrapper httpEventWrapper = parseHttpRequest(httpRequest);

                    final AsyncContext<HttpEventWrapper> asyncContext =
                        new AsyncContext<>(httpEventWrapper, null, asyncContextCompleteHandler);
                    processHttpRequest(ctx, asyncContext);

                } else {
                    final HttpCommand requestCommand = new HttpCommand();
                    final Map<String, Object> bodyMap = parseHttpRequestBody(httpRequest);

                    final String requestCode = HttpMethod.POST.equals(httpRequest.method())
                        ? httpRequest.headers().get(ProtocolKey.REQUEST_CODE)
                        : MapUtils.getString(bodyMap, StringUtils.lowerCase(ProtocolKey.REQUEST_CODE), "");

                    requestCommand.setHttpMethod(httpRequest.method().name());
                    requestCommand.setHttpVersion(httpRequest.protocolVersion() == null ? ""
                        : httpRequest.protocolVersion().protocolName());
                    requestCommand.setRequestCode(requestCode);

                    HttpCommand responseCommand = null;

                    if (StringUtils.isBlank(requestCode)
                        || !processorTable.containsKey(requestCode)
                        || !RequestCode.contains(Integer.valueOf(requestCode))) {
                        responseCommand =
                            requestCommand.createHttpCommandResponse(EventMeshRetCode.EVENTMESH_REQUESTCODE_INVALID);
                        sendResponse(ctx, responseCommand.httpResponse());

                        span = TraceUtils.prepareServerSpan(headerMap,
                            EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_SERVER_SPAN, false);
                        TraceUtils.finishSpanWithException(span, headerMap,
                            EventMeshRetCode.EVENTMESH_REQUESTCODE_INVALID.getErrMsg(), null);
                        return;
                    }

                    try {
                        requestCommand.setHeader(Header.buildHeader(requestCode, headerMap));
                        requestCommand.setBody(Body.buildBody(requestCode, bodyMap));
                    } catch (Exception e) {
                        responseCommand = requestCommand.createHttpCommandResponse(EventMeshRetCode.EVENTMESH_RUNTIME_ERR);
                        sendResponse(ctx, responseCommand.httpResponse());

                        span = TraceUtils.prepareServerSpan(headerMap,
                            EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_SERVER_SPAN, false);
                        TraceUtils.finishSpanWithException(span, headerMap,
                            EventMeshRetCode.EVENTMESH_RUNTIME_ERR.getErrMsg(), e);
                        return;
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("{}", requestCommand);
                    }

                    final AsyncContext<HttpCommand> asyncContext =
                        new AsyncContext<>(requestCommand, responseCommand, asyncContextCompleteHandler);
                    processEventMeshRequest(ctx, asyncContext);
                }

            } catch (Exception ex) {
                log.error("AbstractHTTPServer.HTTPHandler.channelRead error", ex);
            } finally {
                ReferenceCountUtil.release(httpRequest);
            }
        }

        public void processHttpRequest(final ChannelHandlerContext ctx,
            final AsyncContext<HttpEventWrapper> asyncContext) {
            final HttpEventWrapper requestWrapper = asyncContext.getRequest();
            final String requestURI = requestWrapper.getRequestURI();
            String processorKey = "/";
            for (final String eventProcessorKey : eventProcessorTable.keySet()) {
                if (requestURI.startsWith(eventProcessorKey)) {
                    processorKey = eventProcessorKey;
                    break;
                }
            }

            final Pair<EventProcessor, ThreadPoolExecutor> choosed = eventProcessorTable.get(processorKey);
            try {
                choosed.getObject2().submit(() -> {
                    try {
                        final EventProcessor processor = choosed.getObject1();
                        if (processor.rejectRequest()) {
                            final HttpEventWrapper responseWrapper =
                                requestWrapper.createHttpResponse(EventMeshRetCode.EVENTMESH_REJECT_BY_PROCESSOR_ERROR);

                            asyncContext.onComplete(responseWrapper);

                            if (asyncContext.isComplete()) {
                                if (log.isDebugEnabled()) {
                                    log.debug("{}", asyncContext.getResponse());
                                }
                                sendResponse(ctx, asyncContext.getResponse().httpResponse());
                            }
                            return;
                        }

                        processor.processRequest(ctx, asyncContext);
                        if (!asyncContext.isComplete()) {
                            return;
                        }

                        metrics.getSummaryMetrics()
                            .recordHTTPReqResTimeCost(System.currentTimeMillis() - requestWrapper.getReqTime());

                        if (log.isDebugEnabled()) {
                            log.debug("{}", asyncContext.getResponse());
                        }

                        sendResponse(ctx, asyncContext.getResponse().httpResponse());
                    } catch (Exception e) {
                        log.error("process error", e);
                    }
                });
            } catch (RejectedExecutionException re) {
                final HttpEventWrapper responseWrapper = requestWrapper.createHttpResponse(EventMeshRetCode.OVERLOAD);
                asyncContext.onComplete(responseWrapper);
                metrics.getSummaryMetrics().recordHTTPDiscard();
                metrics.getSummaryMetrics().recordHTTPReqResTimeCost(
                    System.currentTimeMillis() - requestWrapper.getReqTime());
                try {
                    sendResponse(ctx, asyncContext.getResponse().httpResponse());
                } catch (Exception e) {
                    log.error("sendResponse error", e);
                }
            }

        }

        public void processEventMeshRequest(final ChannelHandlerContext ctx,
                                            final AsyncContext<HttpCommand> asyncContext) {
            final HttpCommand request = asyncContext.getRequest();
            final Pair<HttpRequestProcessor, ThreadPoolExecutor> choosed = processorTable.get(request.getRequestCode());
            try {
                choosed.getObject2().submit(() -> {
                    try {
                        final HttpRequestProcessor processor = choosed.getObject1();
                        if (processor.rejectRequest()) {
                            final HttpCommand responseCommand =
                                request.createHttpCommandResponse(EventMeshRetCode.EVENTMESH_REJECT_BY_PROCESSOR_ERROR);
                            asyncContext.onComplete(responseCommand);

                            if (asyncContext.isComplete()) {
                                sendResponse(ctx, responseCommand.httpResponse());
                                if (log.isDebugEnabled()) {
                                    log.debug("{}", asyncContext.getResponse());
                                }
                                final Map<String, Object> traceMap = asyncContext.getRequest().getHeader().toMap();
                                TraceUtils.finishSpanWithException(TraceUtils.prepareServerSpan(traceMap,
                                        EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_SERVER_SPAN,
                                        false),
                                    traceMap,
                                    EventMeshRetCode.EVENTMESH_REJECT_BY_PROCESSOR_ERROR.getErrMsg(), null);
                            }

                            return;
                        }

                        processor.processRequest(ctx, asyncContext);
                        if (!asyncContext.isComplete()) {
                            return;
                        }

                        metrics.getSummaryMetrics()
                            .recordHTTPReqResTimeCost(System.currentTimeMillis() - request.getReqTime());

                        if (log.isDebugEnabled()) {
                            log.debug("{}", asyncContext.getResponse());
                        }

                        sendResponse(ctx, asyncContext.getResponse().httpResponse());

                    } catch (Exception e) {
                        log.error("process error", e);
                    }
                });
            } catch (RejectedExecutionException re) {
                asyncContext.onComplete(request.createHttpCommandResponse(EventMeshRetCode.OVERLOAD));
                metrics.getSummaryMetrics().recordHTTPDiscard();
                metrics.getSummaryMetrics().recordHTTPReqResTimeCost(System.currentTimeMillis() - request.getReqTime());
                try {
                    sendResponse(ctx, asyncContext.getResponse().httpResponse());

                    final Map<String, Object> traceMap = asyncContext.getRequest().getHeader().toMap();

                    TraceUtils.finishSpanWithException(
                        TraceUtils.prepareServerSpan(traceMap,
                            EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_SERVER_SPAN,
                            false),
                        traceMap,
                        EventMeshRetCode.EVENTMESH_RUNTIME_ERR.getErrMsg(),
                        re);
                } catch (Exception e) {
                    log.error("processEventMeshRequest fail", re);
                }
            }
        }

        @Override
        public void channelReadComplete(final ChannelHandlerContext ctx) throws Exception {
            super.channelReadComplete(ctx);
            ctx.flush();
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            if (null != cause) {
                log.error("", cause);
            }

            if (null != ctx) {
                ctx.close();
            }
        }

        Map<String, String> extractFromRequest(final HttpRequest httpRequest) {
            return new HashMap<>();
        }
    }

    private HttpEventWrapper parseHttpRequest(final HttpRequest httpRequest) throws IOException, MethodNotSupportedException {
        final HttpEventWrapper httpEventWrapper = new HttpEventWrapper();
        httpEventWrapper.setHttpMethod(httpRequest.method().name());
        httpEventWrapper.setHttpVersion(httpRequest.protocolVersion().protocolName());
        httpEventWrapper.setRequestURI(httpRequest.uri());

        //parse http header
        for (final String key : httpRequest.headers().names()) {
            httpEventWrapper.getHeaderMap().put(key, httpRequest.headers().get(key));
        }

        final long bodyDecodeStart = System.currentTimeMillis();

        final FullHttpRequest fullHttpRequest = (FullHttpRequest) httpRequest;
        final Map<String, Object> bodyMap = new HashMap<>();

        if (HttpMethod.GET.equals(fullHttpRequest.method())) {
            new QueryStringDecoder(fullHttpRequest.uri()).parameters().forEach((key, value) -> bodyMap.put(key, value.get(0)));
        } else if (HttpMethod.POST.equals(fullHttpRequest.method())) {
            if (StringUtils.contains(httpRequest.headers().get("Content-Type"),
                ContentType.APPLICATION_JSON.getMimeType())) {
                final int length = fullHttpRequest.content().readableBytes();
                if (length > 0) {
                    final byte[] body = new byte[length];
                    fullHttpRequest.content().readBytes(body);
                    bodyMap.putAll(Objects.requireNonNull(JsonUtils.parseTypeReferenceObject(new String(body, Constants.DEFAULT_CHARSET),
                        new TypeReference<Map<String, Object>>() {
                        })));
                }
            } else {
                final HttpPostRequestDecoder decoder =
                    new HttpPostRequestDecoder(DEFAULT_HTTP_DATA_FACTORY, httpRequest);
                for (final InterfaceHttpData parm : decoder.getBodyHttpDatas()) {
                    if (InterfaceHttpData.HttpDataType.Attribute == parm.getHttpDataType()) {
                        final Attribute data = (Attribute) parm;
                        bodyMap.put(data.getName(), data.getValue());
                    }
                }
                decoder.destroy();
            }

        } else {
            throw new MethodNotSupportedException("UnSupported Method " + fullHttpRequest.method());
        }

        httpEventWrapper.setBody(Objects.requireNonNull(JsonUtils.toJSONString(bodyMap)).getBytes(StandardCharsets.UTF_8));

        metrics.getSummaryMetrics().recordDecodeTimeCost(System.currentTimeMillis() - bodyDecodeStart);

        return httpEventWrapper;
    }

    private void initSharableHandlers() {
        httpConnectionHandler = new HttpConnectionHandler();
        httpHandler = new HTTPHandler();
    }

    @Sharable
    private class HttpConnectionHandler extends ChannelDuplexHandler {

        public final transient AtomicInteger connections = new AtomicInteger(0);

        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                if (log.isWarnEnabled()) {
                    log.warn("client|http|channelActive|remoteAddress={}|msg=too many client({}) connect this eventMesh server",
                        RemotingHelper.parseChannelRemoteAddr(ctx.channel()), MAX_CONNECTIONS);
                }
                ctx.close();
                return;
            }
            super.channelActive(ctx);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            connections.decrementAndGet();
            super.channelInactive(ctx);
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
            if (evt instanceof IdleStateEvent) {
                final IdleStateEvent event = (IdleStateEvent) evt;
                if (event.state().equals(IdleState.ALL_IDLE)) {
                    final String remoteAddress = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
                    if (log.isInfoEnabled()) {
                        log.info("client|http|userEventTriggered|remoteAddress={}|msg={}",
                            remoteAddress, evt.getClass().getName());
                    }
                    ctx.close();
                }
            }

            ctx.fireUserEventTriggered(evt);
        }
    }

    private class HttpsServerInitializer extends ChannelInitializer<SocketChannel> {

        private final transient SSLContext sslContext;

        public HttpsServerInitializer(final SSLContext sslContext) {
            this.sslContext = sslContext;
        }

        @Override
        protected void initChannel(final SocketChannel channel) {
            final ChannelPipeline pipeline = channel.pipeline();

            if (sslContext != null && useTLS) {
                final SSLEngine sslEngine = sslContext.createSSLEngine();
                sslEngine.setUseClientMode(false);
                pipeline.addFirst(getWorkerGroup(), "ssl", new SslHandler(sslEngine));
            }

            pipeline.addLast(getWorkerGroup(),
                new HttpRequestDecoder(),
                new HttpResponseEncoder(),
                httpConnectionHandler,
                new HttpObjectAggregator(Integer.MAX_VALUE),
                httpHandler);
        }
    }

}
