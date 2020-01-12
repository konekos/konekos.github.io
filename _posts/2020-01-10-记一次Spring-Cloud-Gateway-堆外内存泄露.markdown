## 背景

公司微服务架构使用 Spring Cloud Alibaba，选择 Spring Cloud Gateway 作为网关。测试环境曾出现多次网关503。```jps```发现进程没有挂掉。日志发现异常。

```

```

是OOM，先```jmap -heap pid```发现堆内存正常。接着定位到类 PlatformDependent 源码。

```java
private static void incrementMemoryCounter(int capacity) {
        if (DIRECT_MEMORY_COUNTER != null) {
            for (;;) {
                long usedMemory = DIRECT_MEMORY_COUNTER.get();
                long newUsedMemory = usedMemory + capacity;
                if (newUsedMemory > DIRECT_MEMORY_LIMIT) {
                    throw new OutOfDirectMemoryError("failed to allocate " + capacity
                            + " byte(s) of direct memory (used: " + usedMemory + ", max: " + DIRECT_MEMORY_LIMIT + ')');
                }
                if (DIRECT_MEMORY_COUNTER.compareAndSet(usedMemory, newUsedMemory)) {
                    break;
                }
            }
        }
    }
```

是 Netty 抛出的异常，堆外内存不够用了。这个异常曾出现多次，前几次```free```发现是机器物理内存不够，重启解决的。上线之后出现了问题发现机器内存是够的。说明发生了堆外内存泄露。

## 解决问题思路

堆外内存大小由 JVM参数 ```-XX：MaxDirectMemorySize``` 指定，如果没有指定，则**默认和最大堆内存（```-Xmx```）大小相同**。线上网关 503 重启后，开始在测试环境找出堆外内存泄露的原因。

参考相关博客得知，可以通过监控 PlatformDependent 类的静态变量 ```DIRECT_MEMORY_COUNTER``` 来实时获取堆外内存的占用。使用 Arthas 连接到测试环境网关，```getstatic PlatformDependent DIRECT_MEMORY_COUNTER```，该静态对象是 AtomicLong 类型，观看其 Long类型的value即可。在测试页面点了几个请求发现该值并没有增加，考虑可能是并发不够，写程序模拟并发，发现该值只增不降。说明随着请求不断增加，最终发生OOM。

## 解决问题步骤

在本地直接模拟 OOM 发生找出问题。

1. ```-XX：MaxDirectMemorySize=100M```将堆外内存限制调小到100M。

2. 写一个类监控堆外内存，每1s 打印一次 ```DIRECT_MEMORY_COUNTER```的值。

   ```java
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
    * @author hjs
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
   ```

   可以通过反射的方式获取，也可以直接使用 PlatformDependent 类的静态方法。

3. 使用 JVM 参数```-Dio.netty.leakDetectionLevel=paranoid```将 Netty 监控内存泄露的级别开到最高后模拟OOM，并没有发现警告日志。

   决定使用排除法解决。网关内自己实现的功能是比较少的，除了基础的路由外，功能目前只有鉴权、打印 request 与 response 日志、异常过滤。依次卸载上述功能，发现只有打印请求与响应的日志 Filter 会导致OOM 问题。

   Request 是使用 ```exchange.getAttribute("cachedRequestBodyObject")```获取的，Response 是使用的ServerHttpResponseDecorator类实现，继续使用排除法，发现是打印Response的问题，代码如下。

   ```java
   ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(response) {
               @Override
               public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                   if (body instanceof Flux) {
                       Flux<? extends DataBuffer> fluxBody = (Flux<? extends DataBuffer>) body;
                       return super.writeWith(fluxBody.map(dataBuffer -> {
                           // probably should reuse buffers
                           byte[] content = new byte[dataBuffer.readableByteCount()];
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
   ```

   可以发现这里拿出 buffer 进行读取后，就没有后续操作了，返回的是读取处理后的 content，尝试直接手动释放掉buffer，读取后的代码添加一行 ```DataBufferUtils.release(dataBuffer);```。继续测试发现，问题解决。

   ## 问题思考

   1. Netty buffer的释放机制。

      本次 OOM 原因与 Netty 的 UnpooledHeapByteBuf 有关，暂略。

