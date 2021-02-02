---
layout: post
title: 记一次排查 rest template 超时失效
categories: post
subcate: new

---

## 1. 背景

某服务使用 Spring Rest Template 调用非常多的 http 接口后拼装数据返回给前端。由于调用接口数量过多，使用了线程池并发调用，CountdownLatch 同步等待，线程池配置 （core=200, max=400, queueSize=10000）。默认无配置时，Rest Template 的超时时间是无限的，由于接口提供方发布版本等原因，请求会无限 hang 住，导致核心线程数被占满，后续请求无限排队，从而前端超时。

解决方法当然是配置超时时间，在本地环境测试时，超时配置是生效的，但是发布到测试环境却不生效了。

## 2. 配置

配置的 rest template 如下，使用的是 httpclient 4.5.2，项目里实际是用 xml 配置的，由于依赖的SDK中也有 restTemplate 的配置，所以统一使用 @Qualifier 指定了 Bean：

```java
@Component
public class IRestTemplate {

    @Bean("httpRestTemplate")
    public RestTemplate httpRestTemplate(){

        // requestConfig
        RequestConfig requestConfig = RequestConfig.custom().
                .setConnectionRequestTimeout(30000)
                .setSocketTimeout(60000)
                .setConnectTimeout(5000).build();
        // clientConnectionManager
        PoolingHttpClientConnectionManager clientConnectionManager = new PoolingHttpClientConnectionManager();
        clientConnectionManager.setDefaultMaxPerRoute(50);
        clientConnectionManager.setMaxTotal(200);
        // closeableHttpClient
        CloseableHttpClient closeableHttpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManager(clientConnectionManager).build();
        // httpRequestFactory
        HttpComponentsClientHttpRequestFactory httpRequestFactory = new HttpComponentsClientHttpRequestFactory(closeableHttpClient);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(httpRequestFactory);
        return restTemplate;
    }
}
```

使用的是 pooling httpclient。简要介绍下各个参数的意义：

- connectTimeout 建立 TCP 连接的超时时间
- socketTimeout 在该时间内未接受到数据包则超时的时间（如果持续收到数据包即使总共的时间超过socketTimeout也不会超时）
- connectionRequestTimeout 获取连接的时间，在该时间内拿不到连接则超时
- defaultMaxPerRoute 每个 route 的最大连接数量（ route：代表一个 hostname 或 address）
- maxTotal 所有 route 的最大连接数

本地 junit 测试是没有问题的，问题出在发布到测试环境后。

## 3. 排查思路

根据 httpclient 源码，发现最终请求的入口在 org.apache.http.impl.conn.DefaultHttpClientConnectionOperator#connect：

```java
		@Override
    public void connect(
            final ManagedHttpClientConnection conn,
            final HttpHost host,
            final InetSocketAddress localAddress,
            final int connectTimeout,
            final SocketConfig socketConfig,
            final HttpContext context) throws IOException {
            	//...
            }
```

SocketConfig：

```java
@Immutable
public class SocketConfig implements Cloneable {

    public static final SocketConfig DEFAULT = new Builder().build();

    private final int soTimeout;
    private final boolean soReuseAddress;
    private final int soLinger;
    private final boolean soKeepAlive;
    private final boolean tcpNoDelay;
    private final int sndBufSize;
    private final int rcvBufSize;
    private int backlogSize;
```



入参中有我们设置的 connectTimeout，SocketConfig 中有 soTimout，这个就是我们设置的 socketTimeout：

使用 arthas 工具即可实时监控每次调用的入参，命令：

```bash
watch org.apache.http.impl.conn.DefaultHttpClientConnectionOperator connect "{params,returnObj}" -x 2  
```

由于测试环境使用的是非常精简的镜像，甚至没有 jdk，用 arthas 比较麻烦，好在可以访问公网，直接下载就好了：

```bash
## 1. jdk下载

wget \
--no-check-certificate \
--no-cookies \
--header \
"Cookie: oraclelicense=accept-securebackup-cookie" \
http://download.oracle.com/otn-pub/java/jdk/8u131-b11/d54c1d3a095b4ff2b6607d096fa80163/jdk-8u131-linux-x64.tar.gz

## 2. arthas下载

wget -O arthas.zip 'https://arthas.aliyun.com/download/latest_version?mirror=aliyun'

## 3. 解压

tar zxvf jdk-8u131-linux-x64.tar.gz
unzip arthas.zip 

## 4. 启动

./jdk1.8.0_131/bin/java -jar ./arthas-boot.jar 1
```

开始 watch，果然 timeout 都是 0

```bash
[arthas@1]$ watch org.apache.http.impl.conn.DefaultHttpClientConnectionOperator connect "{params,returnObj}" -x 2  
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 302 ms, listenerId: 1
method=org.apache.http.impl.conn.DefaultHttpClientConnectionOperator.connect location=AtExit
ts=2021-02-02 18:00:37; [cost=3.767811ms] result=@ArrayList[
    @Object[][
        @LoggingManagedHttpClientConnection[10.95.1.137:51982<->10.95.232.126:8080],
        @HttpHost[http://xxxxxx-service:8080],
        null,
        @Integer[0],
        @SocketConfig[[soTimeout=0, soReuseAddress=false, soLinger=-1, soKeepAlive=false, tcpNoDelay=true, sndBufSize=0, rcvBufSize=0, backlogSize=0]],
        @HttpClientContext[org.apache.http.client.protocol.HttpClientContext@182b8122],
    ],
    null,
]
```

接着打印阻塞线程的堆栈，首先使用 arthas 命令列出所有线程：

```
 thread -all 
```

然后用 thread  `ID` 命令打出堆栈：

```
[arthas@1]$ thread 672
"threadPoolTaskExecutor-137" Id=672 RUNNABLE (in native)
    at java.net.SocketInputStream.socketRead0(Native Method)
    at java.net.SocketInputStream.socketRead(SocketInputStream.java:116)
    at java.net.SocketInputStream.read(SocketInputStream.java:171)
    at java.net.SocketInputStream.read(SocketInputStream.java:141)
    at org.apache.http.impl.io.SessionInputBufferImpl.streamRead(SessionInputBufferImpl.java:139)
    at org.apache.http.impl.io.SessionInputBufferImpl.fillBuffer(SessionInputBufferImpl.java:155)
    at org.apache.http.impl.io.SessionInputBufferImpl.readLine(SessionInputBufferImpl.java:284)
    at org.apache.http.impl.conn.DefaultHttpResponseParser.parseHead(DefaultHttpResponseParser.java:140)
    at org.apache.http.impl.conn.DefaultHttpResponseParser.parseHead(DefaultHttpResponseParser.java:57)
    at org.apache.http.impl.io.AbstractMessageParser.parse(AbstractMessageParser.java:261)
    at org.apache.http.impl.DefaultBHttpClientConnection.receiveResponseHeader(DefaultBHttpClientConnection.java:165)
    at org.apache.http.impl.conn.CPoolProxy.receiveResponseHeader(CPoolProxy.java:167)
    at org.apache.http.protocol.HttpRequestExecutor.doReceiveResponse(HttpRequestExecutor.java:272)
    at org.apache.http.protocol.HttpRequestExecutor.execute(HttpRequestExecutor.java:124)
    at org.apache.http.impl.execchain.MainClientExec.execute(MainClientExec.java:271)
    at org.apache.http.impl.execchain.ProtocolExec.execute(ProtocolExec.java:184)
    at org.apache.http.impl.execchain.RetryExec.execute(RetryExec.java:88)
    at org.apache.http.impl.execchain.RedirectExec.execute(RedirectExec.java:110)
    at org.apache.http.impl.client.InternalHttpClient.doExecute$original$kzmia4f7(InternalHttpClient.java:184)
    at org.apache.http.impl.client.InternalHttpClient.doExecute$original$kzmia4f7$accessor$gBiLZujY(InternalHttpClient.java)
    at org.apache.http.impl.client.InternalHttpClient$auxiliary$0FyLgMLc.call(Unknown Source)
    at org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstMethodsInter.intercept(InstMethodsInter.java:93)
    at org.apache.http.impl.client.InternalHttpClient.doExecute(InternalHttpClient.java)
    at org.apache.http.impl.client.CloseableHttpClient.execute(CloseableHttpClient.java:82)
    at org.apache.http.impl.client.CloseableHttpClient.execute(CloseableHttpClient.java:55)
    at org.springframework.http.client.HttpComponentsClientHttpRequest.executeInternal(HttpComponentsClientHttpRequest.java:89)
    at org.springframework.http.client.AbstractBufferingClientHttpRequest.executeInternal(AbstractBufferingClientHttpRequest.java:48)
    at org.springframework.http.client.AbstractClientHttpRequest.execute(AbstractClientHttpRequest.java:53)
    at org.springframework.web.client.RestTemplate.doExecute$original$pcjvD7zD(RestTemplate.java:652)
    at org.springframework.web.client.RestTemplate.doExecute$original$pcjvD7zD$accessor$jZtFad3G(RestTemplate.java)
    at org.springframework.web.client.RestTemplate$auxiliary$rk2rqVCj.call(Unknown Source)
    at org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstMethodsInter.intercept(InstMethodsInter.java:93)
    at org.springframework.web.client.RestTemplate.doExecute(RestTemplate.java)
    at org.springframework.web.client.RestTemplate.execute(RestTemplate.java:613)
    at org.springframework.web.client.RestTemplate.postForEntity(RestTemplate.java:407)
```

由于无限时间的超时导致阻塞在 socketRead0 上了。

## 4. 问题解决

最开始一直觉得是 Bean 加载有问题，因为最开始 watch 的时候 timeout 就已经是0了，但在本地 juint测试后，超时时间是正常的，也就是 Bean 加载其实没什么问题。这时候会想是不是 httpclient 的 bug 呢，搜了一下确实有超时失效之类的 bug ，https://issues.apache.org/jira/plugins/servlet/mobile#issue/HTTPCLIENT-1478，但是这个 bug 在使用 SSL factory 以及 proxy 的时候才会发生，项目里没有用代理也没有 https。

这个时候居然没有没有往应用运行时候配置被改上面去想。。可能是下意识里觉得不会有人这么做吧，不会吧不会吧……

然后第二天又 watch 了一次，居然发现第一次请求的超时时间是正常的！这样第一个超时变 0 的请求估计会有猫腻。DefaultHttpClientConnectionOperator connect 方法入参中是有 HttpHost 的，可以看到请求路径。最后定位到第一个 timeout 变 0 的接口路径，并找到该代码的位置，然后发现果然……

```java
				final HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        CloseableHttpClient client = HttpClientBuilder.create()
                .setRedirectStrategy(new LaxRedirectStrategy())
                .build();
        factory.setHttpClient(client);

        restTemplate.setRequestFactory(factory);
```

这是某接口方法内的一段代码，restTemplate 的配置被改掉了。

## 5. 总结

最开始是有看 resttemplate 的 Api 的，由于不太熟悉 resttemplate 和 httpclient 之间的集成，以及没弄太明白  defaultMaxPerRoute 和 maxTotal 两个参数的含义，导致思路不太对。下次遇到类似的问题，可以代码全局搜一下 Api 中的 set 方法，排除一下人为操作……







