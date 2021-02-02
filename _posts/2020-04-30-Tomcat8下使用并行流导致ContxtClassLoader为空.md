---
layout: post
title: Tomcat8 下使用并行流导致 ContextClassLoader 为空
categories: post
subcate: new
---

项目使用 Tomcat8，某服务经常会重启后所有接口报错，WARN 日志如下：com.caucho.hessian.io.SerializerFactory.getDeserializer Hessian/Burlap:'XxDTO' is an unknown class in null:java.lang.ClassNotFoundException: 'XxDTO'，Http 接口经过统一异常处理后打印的 message 为 java.lang.ClassCastException: java.util.HashMap cannot be cast to 'XxDTO'。

## 1. 问题解决过程

找到 WARNING 日志位置，com.caucho.hessian.io.SerializerFactory，hessian 版本为 4.0.38：

```java
public class SerializerFactory extends AbstractSerializerFactory {
  public Deserializer getDeserializer(String type){
    	//...
      try {
        Class cl = Class.forName(type, false, _loader);
        deserializer = getDeserializer(cl);
      } catch (Exception e) {
        log.warning("Hessian/Burlap: '" + type + "' is an unknown class in " + _loader + ":\n" + e);
        _typeNotFoundDeserializerMap.put(type, PRESENT);
        log.log(Level.FINER, e.toString(), e);
        _unrecognizedTypeCache.put(type, new AtomicLong(1L));
      }
    	//...
  }
}

```

type 是 String 类型，该类的全限定名，通过 _loader 加载该类，由于抛出的异常为 ClassNotFoundException，只会是因为 _loader 为空导致。

接下来看 SerializerFactory 中类加载器 _loader 的赋值，可以看到是在实例化时传入的 ContextClassLoader。

```java
public class SerializerFactory extends AbstractSerializerFactory {	
  	public SerializerFactory() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public SerializerFactory(ClassLoader loader) {
        _loader = loader;
    }
}
```

ContextClassLoader 可以手动 set 赋值，如果不手动调用，它的值会从哪来呢？

可以从 Thread 初始化代码得知：

```java
class Thread {
        private void init(ThreadGroup g, Runnable target, String name,
                          long stackSize, AccessControlContext acc,
                          boolean inheritThreadLocals) {
            //...
            Thread parent = currentThread();

            if (security == null || isCCLOverridden(parent.getClass()))
                this.contextClassLoader = parent.getContextClassLoader();
            else
                this.contextClassLoader = parent.contextClassLoader;
            //...
        }
    }

```

可以看出 ContextClassLoader 是继承的父线程的。正常来说 ContextClassLoader 不应该为空，接下来就要通过 DEBUG 来找到实例化 SerializerFactory 的线程。

虽然问题不是 100% 重启复现，不过还是 DEBUG 到出问题的时候实例化线程名为 ForkJoinPool.commonPool-worker-xxx，是 ForkJoinPool 中的 ForkJoin线程。

这时如果对 Java8 较为熟悉，就应该知道默认使用 ForkJoinPool 的有 parallelStream 以及 CompletableFuture，则可以结合 SerializerFactory 的实例化时机与搜索这两个符号在业务代码的使用从而找到导致出现问题的业务代码的位置。

由于项目中的 redis 使用 hessian 做了一层序列化与反序列化的封装，所以调用 RedisService 的 get 操作时，会进行 SerializerFactory 的实例化。且由于该服务的所有接口要过登录拦截器，该拦截器中会有 reids get 操作，而所有接口不可用就是因为在拦截器中抛出了java.lang.ClassCastException: java.util.HashMap cannot be cast to 'XxDTO' 的异常。因此 SerializerFactory 在 Spring 容器启动完毕之前就已经实例化了，通过排查最终发现导致问题的代码，示例如下：

```java
@Service
class SomeService {

  @Autowired
  private RedisService redisService;

  @PostConstruct
  public void init(){
    boolean b = this.getB();
    if(b){
      List<String> list = this.getList();
      list.parallelStream().forEach(s->{
        redisService.set(s);
        //...
      });
    }
  }
}

```

项目中 redis 的 hessian 封装里，SerializerFactory 是作为单例使用的。可以看到通过 @PostConstruct 注解，在并行流里进行了 redis set 操作，set 操作需要获取序列化器，导致了 SerializerFactory 预先实例化且获取的 ContextClassLoader 为空。 之后在拦截器中 RedisService 调用get 方法，通过 SerializerFactory 获取反序列化器时，由于 ClassLoader 为空，则默认使用了 Map 类型的反序列化器（有兴趣可以看下 hessian 源码），而 Redis 中存的反序列化后的对象并不是 Map 类型，所以抛出转换异常。而且有 if 判断条件，导致不能每次重启都能复现。

最终取消使用并行流，问题解决。

## 2. 问题思考

为什么 ForkJoinPool 中的线程获取 ContextClassLoader 为空？首先看一下 ForkJoinPool 的源码。

普通线程池（ThreadPoolExecutor）的线程都是由 ThreadFactory 创建的，ForkJoinPool 也是如此，为其内部类 DefaultForkJoinWorkerThreadFactory。

```java

static final class DefaultForkJoinWorkerThreadFactory
  implements ForkJoinWorkerThreadFactory {
  public final ForkJoinWorkerThread newThread(ForkJoinPool pool) {
    return new ForkJoinWorkerThread(pool);
  }
}
```

创建的线程为 ForkJoinWorkerThread，调用了 Thread 的构造器，执行了 init，所以也是继承的父线程的（new 出这个类实例的线程）的类加载器。

```java
public class ForkJoinWorkerThread extends Thread {
		protected ForkJoinWorkerThread(ForkJoinPool pool) {
        // Use a placeholder until a useful name can be set in registerWorker
        super("aForkJoinWorkerThread");
        this.pool = pool;
        this.workQueue = pool.registerWorker(this);
    }
}
  
```

那为什么为空呢？

## 3. 问题根源

最后还是通过搜索得知原来是 Tomcat 替换了 ForkJoinPool 的 DefaultForkJoinWorkerThreadFactory 导致，目的是为了解决内存泄露的问题。

```java
class ForkJoinPool{
  private static ForkJoinPool makeCommonPool() {
    int parallelism = -1;
    ForkJoinWorkerThreadFactory factory = null;
    UncaughtExceptionHandler handler = null;
    try {  // ignore exceptions in accessing/parsing properties
        String pp = System.getProperty
            ("java.util.concurrent.ForkJoinPool.common.parallelism");
        String fp = System.getProperty
            ("java.util.concurrent.ForkJoinPool.common.threadFactory");
        String hp = System.getProperty
            ("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
        if (pp != null)
            parallelism = Integer.parseInt(pp);
        if (fp != null)
            factory = ((ForkJoinWorkerThreadFactory)ClassLoader.
                       getSystemClassLoader().loadClass(fp).newInstance());
        if (hp != null)
            handler = ((UncaughtExceptionHandler)ClassLoader.
                       getSystemClassLoader().loadClass(hp).newInstance());
    } catch (Exception ignore) {
    }
    if (factory == null) {
        if (System.getSecurityManager() == null)
            factory = defaultForkJoinWorkerThreadFactory;
        else // use security-managed default
            factory = new InnocuousForkJoinWorkerThreadFactory();
    }
    if (parallelism < 0 && // default 1 less than #cores
        (parallelism = Runtime.getRuntime().availableProcessors() - 1) <= 0)
        parallelism = 1;
    if (parallelism > MAX_CAP)
        parallelism = MAX_CAP;
    return new ForkJoinPool(parallelism, factory, handler, LIFO_QUEUE,
                            "ForkJoinPool.commonPool-worker-");
	}
}

```

Tomcat 通过修改系统属性 `java.util.concurrent.ForkJoinPool.common.threadFactory` 替换了线程工厂。

```java
public class SafeForkJoinWorkerThreadFactory implements ForkJoinWorkerThreadFactory {

    @Override
    public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
        return new SafeForkJoinWorkerThread(pool);
    }


    private static class SafeForkJoinWorkerThread extends ForkJoinWorkerThread {

        protected SafeForkJoinWorkerThread(ForkJoinPool pool) {
            super(pool);
            setContextClassLoader(ForkJoinPool.class.getClassLoader());
        }
    }
}
```

该类将创建出的 SafeForkJoinWorkerThread 线程的 ContextClassLoader 设为 ForkJoinPool 类的 ClassLoader，而 ForkJoinPool 是 java.util.concurrent 包里的类，在 rt.jar 里，根据双亲委托机制，该类由 BootStrapClassLoader 加载的，而此类加载器是纯 C++ 实现，没有具体的 Java Class，所以获取的 ClassLoader 实例 为 null。

## 4. 类加载相关

在 Thread 中，ContextClassLoader 是其成员变量。ForkJoin 线程池中创建的线程，与普通线程池类似，也会有持续存在的核心线程不会销毁。这样ForkJoinPool 中的线程就持有从父线程继承的 ClassLoader 的强引用。

```java
/* The context ClassLoader for this thread */
private ClassLoader contextClassLoader;
```

而 ForkJoinPool 本身由 static 实例化，在整个 JVM 存活周期不会被销毁：

```java
static final ForkJoinPool common;
```

先复习一下类加载相关知识：

ClassLoader 内会存放所加载类的引用，Class 也会引用其 ClassLoader，通过调用 getClassLoader() 获取。Class 与 ClassLoader 是双向关联关系。

Java 自带的类加载器有 Bootstrap，Ext，与 App ClassLoader 三种，这3个 ClassLoader 被 JVM 内部强引用，不会被回收，因此这三种类加载器所加载的 Class 在 JVM 存活时不会被回收。

自定义类加载器所加载的类是可以被卸载的，如图所示，当 SampleObj 的类加载器与 SampleObj 所有的实例对象不再被引用，那么其 Class 也将不在被 root 引用，从而触发卸载类的卸载。

，![classloader]({{site.picpath}}/classloader.png)

Tomcat 的自定义类加载器为 ParallelWebappClassLoader，其生命周期等同 Tomcat 的生命周期。当 ForkJoinPool 中的线程持有对 ParallelWebappClassLoader 的引用时，会导致 ParallelWebappClassLoader 与其加载的类无法被回收。

那么什么时候会发生 ParallelWebappClassLoader 的 ClassLoader 的卸载呢，可能是 Tomcat 里的 webapp 重启的时候 ？？？暂时先不考虑那么多，如果以后碰到 Tomcat 下 metaspace 区类溢出的问题，也许可以提供一种思路。

## 5. SpringBoot 下的测试

经在 SpringBoot 2.3.2（tomcat-embed-core 9.0.37）测试，代码如下，在并行流下获取的  ContextClassLoader 并不是 null，而是 TomcatEmbeddedWebappClassLoader。

```java
@Controller
public class TestController {

    @RequestMapping("/test")
    @ResponseBody
    public String test() throws ExecutionException, InterruptedException {
        CompletableFuture.supplyAsync(() -> {
            System.out.println(Thread.currentThread());
            System.out.println(Thread.currentThread().getContextClassLoader());
            System.out.println(ClassLoader.getSystemClassLoader());
            return null;
        }).get();
        return "ok";
    }
}
```

Result:

```java
Thread[ForkJoinPool.commonPool-worker-4,5,main]
TomcatEmbeddedWebappClassLoader
  context: ROOT
  delegate: true
----------> Parent Classloader:
sun.misc.Launcher$AppClassLoader@18b4aac2

sun.misc.Launcher$AppClassLoader@18b4aac2
```

也就是说没有再使用 SafeForkJoinWorkerThreadFactory，而是直接从父线程继承的。

又用最早的 SpringBoot 1.0.0 版本试了下，发现获取的 ContextClassLoader 也是 Tomcat 7 的 WebappClassLoader。

为什么 SpringBoot 下没有再使用 SafeForkJoinWorkerThreadFactory 呢，个人猜测和 JVM 的生命周期有关。以 Tomcat 的方式启动 web 应用时，会有Tomcat 本身不停止，只重启其中的 web 应用的操作，而 ForkJoinPool 的生命周期是依附于 JVM 的，这样会导致类无法卸载的问题。而 SpringBoot 的重启是整个 JVM 的重启，不会有类似问题。

⚠️：以上只是个人猜想可能不对🐶。

## 6. 总结

本次遇到的问题还是有不少收获的，特别是问题不能 100% 复现，对 DEBUG 造成不小的麻烦，前期只能靠猜。最终终于 DEBUG 到一次线程名，看到是 ForkJoin 线程就豁然开朗了。

随后找到问题发生的根源，并对类加载机制 以及 ContextClassLoader 有了进一层的理解。最后在 SpringBoot 下测试相同的问题，并给出了猜想。

最后还是要多学习啊，基础要牢🐶。 



