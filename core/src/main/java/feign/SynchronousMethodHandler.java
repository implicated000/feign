/**
 * Copyright 2012-2019 The Feign Authors
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.Request.Options;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static feign.ExceptionPropagationPolicy.UNWRAP;
import static feign.FeignException.errorExecuting;
import static feign.FeignException.errorReading;
import static feign.Util.checkNotNull;
import static feign.Util.ensureClosed;

final class SynchronousMethodHandler implements MethodHandler {

    private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

    private final MethodMetadata metadata;
    private final Target<?> target;
    private final Client client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final RequestTemplate.Factory buildTemplateFromArgs;
    private final Options options;
    private final Decoder decoder;
    private final ErrorDecoder errorDecoder;
    private final boolean decode404;
    private final boolean closeAfterDecode;
    private final ExceptionPropagationPolicy propagationPolicy;

    private SynchronousMethodHandler(Target<?> target, Client client, Retryer retryer,
                                     List<RequestInterceptor> requestInterceptors, Logger logger,
                                     Logger.Level logLevel, MethodMetadata metadata,
                                     RequestTemplate.Factory buildTemplateFromArgs, Options options,
                                     Decoder decoder, ErrorDecoder errorDecoder, boolean decode404,
                                     boolean closeAfterDecode, ExceptionPropagationPolicy propagationPolicy) {
        this.target = checkNotNull(target, "target");
        this.client = checkNotNull(client, "client for %s", target);
        this.retryer = checkNotNull(retryer, "retryer for %s", target);
        this.requestInterceptors =
                checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
        this.logger = checkNotNull(logger, "logger for %s", target);
        this.logLevel = checkNotNull(logLevel, "logLevel for %s", target);
        this.metadata = checkNotNull(metadata, "metadata for %s", target);
        this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
        this.options = checkNotNull(options, "options for %s", target);
        this.errorDecoder = checkNotNull(errorDecoder, "errorDecoder for %s", target);
        this.decoder = checkNotNull(decoder, "decoder for %s", target);
        this.decode404 = decode404;
        this.closeAfterDecode = closeAfterDecode;
        this.propagationPolicy = propagationPolicy;
    }

    @Override
    public Object invoke(Object[] argv) throws Throwable {
        // RequestTemplate 是 HTTP请求内容的抽象
        RequestTemplate template = buildTemplateFromArgs.create(argv);
        // Options 定义了连接超时时间、请求超时时间、是否允许重定向
        Options options = findOptions(argv);
        // 重试设置
        Retryer retryer = this.retryer.clone();
        // 成功返回，失败抛异常
        while (true) {
            try {
                return executeAndDecode(template, options);
            } catch (RetryableException e) {
                try {
                    // 判断是否继续重试
                    retryer.continueOrPropagate(e);
                } catch (RetryableException th) {
                    Throwable cause = th.getCause();
                    if (propagationPolicy == UNWRAP && cause != null) {
                        throw cause;
                    } else {
                        throw th;
                    }
                }
                if (logLevel != Logger.Level.NONE) {
                    logger.logRetry(metadata.configKey(), logLevel);
                }
                // 重试
                continue;
            }
        }
    }

    Object executeAndDecode(RequestTemplate template, Options options) throws Throwable {
        // RequestTemplate 转换为 Request
        Request request = targetRequest(template);

        // 打印请求参数
        if (logLevel != Logger.Level.NONE) {
            logger.logRequest(metadata.configKey(), logLevel, request);
        }

        // 打印接口响应时间
        Response response;
        long start = System.nanoTime();
        try {
            // 发起请求
            response = client.execute(request, options);
        } catch (IOException e) {
            if (logLevel != Logger.Level.NONE) {
                logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime(start));
            }
            // 抛出重试异常 RetryableException()
            throw errorExecuting(request, e);
        }
        long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        boolean shouldClose = true;
        try {
            if (logLevel != Logger.Level.NONE) {
                response =
                        logger.logAndRebufferResponse(metadata.configKey(), logLevel, response, elapsedTime);
            }
            if (Response.class == metadata.returnType()) {
                if (response.body() == null) {
                    return response;
                }
                if (response.body().length() == null ||
                        response.body().length() > MAX_RESPONSE_BUFFER_SIZE) {
                    shouldClose = false;
                    return response;
                }
                // Ensure the response body is disconnected
                // 读取body字节数组，返回response
                byte[] bodyData = Util.toByteArray(response.body().asInputStream());
                return response.toBuilder().body(bodyData).build();
            }
            // 处理 2XX
            if (response.status() >= 200 && response.status() < 300) {
                // 接口返回void
                if (void.class == metadata.returnType()) {
                    return null;
                }
                // 解码response，直接调用decoder解码
                else {
                    Object result = decode(response);
                    shouldClose = closeAfterDecode;
                    return result;
                }
            }
            // 处理 404
            else if (decode404 && response.status() == 404 && void.class != metadata.returnType()) {
                Object result = decode(response);
                shouldClose = closeAfterDecode;
                return result;
            }
            // 其他返回码，使用errorDecoder解析，抛出异常
            else {
                throw errorDecoder.decode(metadata.configKey(), response);
            }
        } catch (IOException e) {
            if (logLevel != Logger.Level.NONE) {
                logger.logIOException(metadata.configKey(), logLevel, e, elapsedTime);
            }
            throw errorReading(request, response, e);
        } finally {
            if (shouldClose) {
                // 关流
                ensureClosed(response.body());
            }
        }
    }

    long elapsedTime(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    Request targetRequest(RequestTemplate template) {
        for (RequestInterceptor interceptor : requestInterceptors) {
            interceptor.apply(template);
        }
        return target.apply(template);
    }

    Object decode(Response response) throws Throwable {
        try {
            return decoder.decode(response, metadata.returnType());
        } catch (FeignException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DecodeException(response.status(), e.getMessage(), response.request(), e);
        }
    }

    Options findOptions(Object[] argv) {
        if (argv == null || argv.length == 0) {
            return this.options;
        }
        return (Options) Stream.of(argv)
                .filter(o -> o instanceof Options)
                .findFirst()
                .orElse(this.options);
    }

    static class Factory {

        private final Client client;
        private final Retryer retryer;
        private final List<RequestInterceptor> requestInterceptors;
        private final Logger logger;
        private final Logger.Level logLevel;
        private final boolean decode404;
        private final boolean closeAfterDecode;
        private final ExceptionPropagationPolicy propagationPolicy;

        Factory(Client client, Retryer retryer, List<RequestInterceptor> requestInterceptors,
                Logger logger, Logger.Level logLevel, boolean decode404, boolean closeAfterDecode,
                ExceptionPropagationPolicy propagationPolicy) {
            this.client = checkNotNull(client, "client");
            this.retryer = checkNotNull(retryer, "retryer");
            this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors");
            this.logger = checkNotNull(logger, "logger");
            this.logLevel = checkNotNull(logLevel, "logLevel");
            this.decode404 = decode404;
            this.closeAfterDecode = closeAfterDecode;
            this.propagationPolicy = propagationPolicy;
        }

        public MethodHandler create(Target<?> target,
                                    MethodMetadata md,
                                    RequestTemplate.Factory buildTemplateFromArgs,
                                    Options options,
                                    Decoder decoder,
                                    ErrorDecoder errorDecoder) {
            return new SynchronousMethodHandler(target, client, retryer, requestInterceptors, logger,
                    logLevel, md, buildTemplateFromArgs, options, decoder,
                    errorDecoder, decode404, closeAfterDecode, propagationPolicy);
        }
    }
}
