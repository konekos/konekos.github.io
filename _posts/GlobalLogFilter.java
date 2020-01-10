package com.dafy.bs.gateway.filter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dafy.bs.common.api.HttpLog;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import io.netty.buffer.ByteBufUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;

/**
 * @author fuyongde
 * @date 2019/9/23
 */
@Slf4j
@Component
public class GlobalLogFilter implements GatewayFilter, Ordered {

    private static Logger logger = LoggerFactory.getLogger("logreport");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        log.info(JSON.toJSONString(exchange.getAttributes()));
        Object requestBody = exchange.getAttribute("cachedRequestBodyObject");

        URI uri = exchange.getRequest().getURI();
        String requestPath = exchange.getRequest().getPath().value();
        MultiValueMap<String, String> params = exchange.getRequest().getQueryParams();
        String remoteAddress = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        HttpLog.Params param = HttpLog.Params.builder().queryParams(params).requestBody(requestBody).build();
        String userAgent = JSON.toJSONString(exchange.getRequest().getHeaders().get("User-Agent"));
        String deviceType = JSON.toJSONString(exchange.getRequest().getHeaders().get("deviceType"));
        String appVersion = JSON.toJSONString(exchange.getRequest().getHeaders().get("appVersion"));
        String mid = JSON.toJSONString(exchange.getRequest().getHeaders().get("mid"));

        long startTime = System.currentTimeMillis();

        ServerHttpResponse response = exchange.getResponse();
        DataBufferFactory bufferFactory = response.bufferFactory();


        ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (HttpStatus.OK.equals(getStatusCode()) && body instanceof Flux) {
                    Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                    List<String> list = Lists.newArrayList();
                    return super.writeWith(fluxBody.buffer().map(
                            dataBuffers -> {
                                dataBuffers.forEach(
                                        dataBuffer -> {
                                            try {
                                                byte[] content = new byte[dataBuffer.readableByteCount()];
                                                dataBuffer.read(content);
                                                DataBufferUtils.release(dataBuffer);
                                                list.add(new String(content, StandardCharsets.UTF_8));
                                            } catch (Exception e) {
                                                logger.info("打印body失败：{}", Throwables.getStackTraceAsString(e));
                                            }
                                        }
                                );
                                String responseData = String.join("", list);
                                long endTime = System.currentTimeMillis();
                                reportLog(requestPath, JSON.toJSONString(param), remoteAddress, startTime, endTime, responseData, userAgent, deviceType, appVersion, mid, null, null, null);
                                byte[] uppedContent = new String(responseData.getBytes(), Charset.forName("UTF-8")).getBytes();
                                response.getHeaders().setContentLength(uppedContent.length);
                                return bufferFactory.wrap(uppedContent);
                            }));
                }
                return super.writeWith(body);
            }
        };

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    Flux<? extends DataBuffer> fluxBody = (Flux<? extends DataBuffer>) body;
                    return super.writeWith(fluxBody.map(dataBuffer -> {
                        // probably should reuse buffers
                        byte[] content = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(content);
                        String response = new String(content, Charset.forName("UTF-8"));
                        DataBufferUtils.release(dataBuffer);
                        long endTime = System.currentTimeMillis();
                        reportLog(requestPath, JSON.toJSONString(param), remoteAddress, startTime, endTime, response, userAgent, deviceType, appVersion, mid, null, null, null);
                        return bufferFactory.wrap(content);
                    }));
                } else {
                    return super.writeWith(body);
                }
            }
        };

        return chain.filter(exchange.mutate().response(decorator).build());
    }

    private void reportLog(String url, String arguments, String ip, long startTime, long endTime, String
            response, String userAgent,
                           String deviceType, String appVersion, String mid, Integer maintainerId, Integer maintainerRole, Long
                                   adminUserId) {
        HttpLog httpLog = HttpLog.builder()
                .id(UUID.randomUUID().toString().replaceAll("-", "").toLowerCase())
                .url(url)
                .ip(ip)
                .arguments(arguments)
                .startTime(startTime)
                .endTime(endTime)
                .duration(endTime - startTime)
                .response(response)
                .userAgent(userAgent)
                .deviceType(deviceType)
                .appVersion(appVersion)
                .mid(mid)
                .maintainerId(String.valueOf(maintainerId))
                .maintainerRole(String.valueOf(maintainerRole))
                .adminUserId(String.valueOf(adminUserId))
                .build();
        logger.info(JSONObject.toJSONString(httpLog));
    }

    @Override
    public int getOrder() {
        return -5;
    }

    public interface BodyHandlerFunction extends
            BiFunction<ServerHttpResponse, Publisher<? extends DataBuffer>, Mono<Void>> {
    }

    /**
     * ServerHttpResponse包装类，通过BodyHandlerFunction处理响应body
     *
     * @author
     * @see [相关类/方法]
     * @since [产品/模块版本]
     */
    public class BodyHandlerServerHttpResponseDecorator
            extends ServerHttpResponseDecorator {

        /**
         * body 处理拦截器
         */
        private BodyHandlerFunction bodyHandler = initDefaultBodyHandler();

        /**
         * 构造函数
         *
         * @param bodyHandler
         * @param delegate
         */
        public BodyHandlerServerHttpResponseDecorator(
                BodyHandlerFunction bodyHandler, ServerHttpResponse delegate) {
            super(delegate);
            if (bodyHandler != null) {
                this.bodyHandler = bodyHandler;
            }
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            //body 拦截处理器处理响应
            return bodyHandler.apply(getDelegate(), body);
        }

        @Override
        public Mono<Void> writeAndFlushWith(
                Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return writeWith(Flux.from(body).flatMapSequential(p -> p));
        }

        /**
         * 默认body拦截处理器
         *
         * @return
         */
        private BodyHandlerFunction initDefaultBodyHandler() {
            return (resp, body) -> resp.writeWith(body);
        }
    }


}
