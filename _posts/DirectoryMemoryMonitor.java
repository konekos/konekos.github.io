package com.dafy.bs.gateway;

import io.netty.util.internal.PlatformDependent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import javax.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author huangjiashu
 * @date 2020/1/9
 **/
@Component
@Slf4j
public class DirectoryMemoryMonitor {

    private AtomicLong memory1;
    private long memory2;


    @PostConstruct
    public void init() {
        Field field1 = ReflectionUtils.findField(PlatformDependent.class, "DIRECT_MEMORY_COUNTER");
        Field field2 = ReflectionUtils.findField(PlatformDependent.class, "DIRECT_MEMORY_LIMIT");
        field1.setAccessible(true);
        field2.setAccessible(true);



        try {
            memory1 = (AtomicLong)field1.get(PlatformDependent.class);
            memory2 = (long)field1.get(PlatformDependent.class);
        } catch (Exception e) {
        }

        new Thread(()->{
        while (true) {
            log.info("memory: {}", memory1);
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        }).start();


    }

}
