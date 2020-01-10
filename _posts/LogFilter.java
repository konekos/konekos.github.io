package com.dafy.bs.gateway.filter;

import org.springframework.cloud.gateway.filter.AdaptCachedBodyGlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * @author huangjiashu
 * @date 2020/1/10
 **/
public class LogFilter extends AdaptCachedBodyGlobalFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        return super.filter(exchange, chain);
    }
}
