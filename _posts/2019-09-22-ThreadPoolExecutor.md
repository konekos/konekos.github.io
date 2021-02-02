---
layout: post
title: 超细致 ThreadPoolExecutor 线程池源码解析
categories: post
subcate: new
---

线程池是个比较重要的类，读源码了解其实现方式，并从中学习一些设计思想。废话不多说，开始。

## 构造器

选择参数列表最全的构造器

```java
public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        if (corePoolSize < 0 ||
            maximumPoolSize <= 0 ||
            maximumPoolSize < corePoolSize ||
            keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }
```

Java 代码示例

```java
public class ExecutorTest {

    private final static AtomicInteger COUNTER = new AtomicInteger(0);

    public static void main(String[] args) {
        BlockingQueue<Runnable> blockingQueue = new ArrayBlockingQueue<>(10);
        ExecutorService service = new ThreadPoolExecutor(5, 10, 0, TimeUnit.SECONDS, blockingQueue, r -> {
            Thread t = new Thread(r);
            t.setName("Thread-" + COUNTER.incrementAndGet());
            return t;
        }, ((r, executor) -> {
            //do nothing
        }));
        
        service.execute(()-> System.out.println(Thread.currentThread().getName()));
    }
}
```

- `corePoolSize` 
- `maximumPoolSize`
- `keepAliveTime`
- `unit`
- `workQueue`
- `threadFactory`
- `handler`

这里就先不具体介绍各个参数的意义了，如果完全不懂的话可以先去了解一下。

## Worker

`Worker`是`ThreadPoolExecutor`里核心的内部类，是线程池的工作线程对象。而我们的提交任务是作为`Runnable`由`Worker`去执行的。

```java
private final class Worker
        extends AbstractQueuedSynchronizer
        implements Runnable
    {

        private static final long serialVersionUID = 6138294804551838833L;

        //真正的线程对象，在构造器处用ThreadFactory把自身作为Runnable，new出Thread赋值。
        final Thread thread;
        //顾名思义，第一次的Runnable任务，唯一的构造器的唯一参数，可以是我们执行execute时候
        //传入的Runnable，也可以是null, 即我们以空任务为开始启动Worker
        Runnable firstTask;
        //完成的tasks计数，voliatile保证可见性。
        volatile long completedTasks;


        Worker(Runnable firstTask) {
            //AQS 设置初始state为1，防止在runWorker前中断。
            setState(-1);
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }

        //run方法为自身实例作为runnable执行ThreadPoolExecutor的runWorker方法，
        //调用线程start的上面的thread线程
        public void run() {
            runWorker(this);
        }

        // AQS 相关方法，0代表解锁状态，1代表加锁状态
        //是否加锁态，加锁状态代表正在执行task
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }
        // AQS 加锁的方法，CAS state 由0，变为1，初始态为-1，所以需要unlock一次，将state变为0再加锁。
        // 只能从0变为1才成功加锁，就是必须在state=0的解锁状态才能加锁，是不可重入的锁
        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }
        // 解锁方法 state=0
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }
```

如果对 AQS 不熟悉的话可能会不太理解加锁解锁，但是没有影响，简单认为这个是不可重入的排它锁好了。

## 除构造器外的Fields

```java
private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    //29 代表高三位存state，低29位存worker数量
    private static final int COUNT_BITS = Integer.SIZE - 3;
    //代表线程池的最大线程数是 2^29-1=536870911
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    // 111 00000000000000000000000000000
    private static final int RUNNING    = -1 << COUNT_BITS;
    // 000 00000000000000000000000000000
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    // 001 00000000000000000000000000000
    private static final int STOP       =  1 << COUNT_BITS;
    // 010 00000000000000000000000000000
    private static final int TIDYING    =  2 << COUNT_BITS;
    // 011 00000000000000000000000000000
    private static final int TERMINATED =  3 << COUNT_BITS;

    // ctl的后29位变0，得到state
    private static int runStateOf(int c)     { return c & ~CAPACITY; }、
    // 高3位变0，得到worker数量
    private static int workerCountOf(int c)  { return c & CAPACITY; }
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * Attempts to CAS-increment the workerCount field of ctl.
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * Attempts to CAS-decrement the workerCount field of ctl.
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * Decrements the workerCount field of ctl. This is called only on
     * abrupt termination of a thread (see processWorkerExit). Other
     * decrements are performed within getTask.
     */
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }
```

`ctl`是一个 32 位的整数，存放了线程池的状态和当前线程池中的线程数，高 3 位代表线程池状态，低 29 位代表线程数。通过位运算，一个变量就能控制线程池状态和worker数量。

接着看其他的Field

```java
	// 线程池的锁，用于在访问workers时,以及统计线程池相关数据（如pool size)
    // 和 shutdown等时候使用。
    private final ReentrantLock mainLock = new ReentrantLock();

    // 存放Worker的HashSet，必须加锁才能操作workers
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * Wait condition to support awaitTermination
     */
    private final Condition termination = mainLock.newCondition();

    //最大的pool size，指曾经达到的最大worker（HashSet size）的数量。
    private int largestPoolSize;

    //完成的task计数
    private long completedTaskCount;
	
	//核心线程允许不允许超时退出
 	private volatile boolean allowCoreThreadTimeOut;


    //下面是构造器的参数了，结合接下来讲的源码理解。
    private final BlockingQueue<Runnable> workQueue;

    private volatile ThreadFactory threadFactory;

    private volatile RejectedExecutionHandler handler;

    private volatile long keepAliveTime;

   

    private volatile int corePoolSize;

    private volatile int maximumPoolSize;
```

## execute方法

有了上面的铺垫，下面就容易理解execute方法了。

```java
public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        
        // 前面讲了，可以得到线程池状态以及worker数量
        int c = ctl.get();
        // 当前worker数量小于corePoolSize，则执行addWorker增加核心worker线程执行runnable
        // 传入的runnable作为worker的firstTask，添加成功则worker +1 ，并返回。
        // 如果添加失败，addWorker返回了fase，表明可能已经被其他线程增加到corePoolSize了，也可能是没有start成功，以及线程池SHUTDOWN了等，则继续往下执行。
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command, true))
                return;
            c = ctl.get();
        }
        // 到这里表明增加核心线程失败了

        // 如果线程池是RUNNING状态，则把这个runnable放入阻塞任务队列
        // （只有RUNNING状态，才能提交新任务入队列）
        if (isRunning(c) && workQueue.offer(command)) {
            // 到这里是添加到任务队列成功，表明任务队列还没满
            int recheck = ctl.get();

            // 线程池状态的double check，如果线程池不是RUNNING状态则把这个task从任务队列移除
            // 并执行拒绝策略 
            if (! isRunning(recheck) && remove(command))
                reject(command);
            // 线程数的double check，如果当前线程数为0，则addWorker
            // 什么时候为0呢，两种情况，corePoolSize=0 或 allowCoreThreadTimeOut = true
            // 可以将上面例子的corePoolSize改为0，你会发现main执行完就退出了
            // (看完全文思考为什么corePoolSize>0 的时候，main不会退出)
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }

        // 到这里是因为上面任务队列满了添加失败了，执行添加非核心的worker
        // 如果添加失败，则是因为达到了maximumPoolSize，则执行拒绝策略
        // 拒绝策略就是我们在构造器传入的handler，我们例子do nothing 其实就是线程池默认的拒绝策略
        // 拒绝策略的内容就不细讲了，只是几个内部类
        else if (!addWorker(command, false))
            reject(command);
    }
```

如果你对`corePoolSize` `maximumPoolSize` `workQueue`的理解比较准确的话应该很容易理解。

接下来看`addWorker`方法。

## addWorker方法

```java
	// firstTask 即要提交的runnable，是可以null的
    // core 代表是否核心线程，刚才我们分析其实是当前worker数量<corePoolSize
    // 因为我们的worker也没有标记是否core的变量
    // 另外就是addWorker是多线程的，我们这里肯定要判断，core=true，就是以corePoolSize为边界
    // 如果是false，那就是以maximumPoolSize为边界
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // 这里的逻辑是不创建worker的条件
            // 1. 线程池状态>=SHUTDOWN （STOP, TIDYING, TERMINATED）
            // 2. 不能是 （线程池SHUTDOWN的状态 + firstTask==null + 任务队列不为空）这个状态，也就是这个状态的话是允许创建worker的
            // 2是什么意思呢，SHUTDOWN的意思是：不能再提交新的任务了，但是当前任务队列里的task是要执行完的，所以允许添加firstTask==null的worker
            // 什么时候firstTask==null呢，一个地方就是execute方法里double check woker数量那里，当然还有另外一些地方
            // 目的是当SHUTDOWN状态任务队列不为空的时候，一定要保留一个线程执行剩余的TASK
            // 理解SHUTDOWN的含义就容易理解这里了
            if (rs >= SHUTDOWN &&
                ! (rs == SHUTDOWN &&
                   firstTask == null &&
                   ! workQueue.isEmpty()))
                return false;
            //这里就是无限循环add了
            for (;;) {
                int wc = workerCountOf(c);
                // 这里就是刚讲的core的含义，使用哪个边界
                if (wc >= CAPACITY ||
                    wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                // 到这里就说明worker数量满足条件
                // 执行一次 CAS，成功就跳出最外面的循环了
                // CAS 失败了，说明是其他的线程 CAS成功了 要重试
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                c = ctl.get();  // Re-read ctl
                // 如果当前线程池的状态变了，则执行外层的循环
                if (runStateOf(c) != rs)
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
                // 如果是因为其他线程导致的CAS失败，则执行内层循环
            }
        }

        // 到这里，就说明符合条件，可以addWorker了

        // worker是否执行了start
        boolean workerStarted = false;
        // worker有没有被add到 上面说的 workers HashSet
        boolean workerAdded = false;
        Worker w = null;
        try {
            // 传入firstTask new一个Worker
            w = new Worker(firstTask);
            // worker的thread成员变量，用于执行start方法
            // 刚才讲的，Thread是用ThreadFactory创建的
            final Thread t = w.thread;
            if (t != null) {
                // 刚才说了，对workers的操作要进行加锁
                // shutdown线程池也是要加锁的，这里我们这个worker还没加到HashSet，如果我们这里获取到锁，则一定在shutdown之前add成功
                // 如果是shutdown执行完之后再执行这里，也是可以的，shutdown操作的是workers HashSet里的worker，我们这里的还没add进去
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // double check 线程池状态
                    int rs = runStateOf(ctl.get());

                    // 小于SHUTDOWN就是RUNNING，正常情况
                    // SHUNTDOWN且firstTask==null 就是刚才讲的情况，不接受新任务，但得保证workQueue的执行完
                    if (rs < SHUTDOWN ||
                        (rs == SHUTDOWN && firstTask == null)) {
                        // t不能是alive的
                        // 什么意思呢？你在ThreadFactory里把线程start了就懂了 哈哈
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        // 添加到HashSet
                        workers.add(w);
                        // 这里就是用来记录达到最大的worker数量了
                        int s = workers.size();
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }

                // 成功添加了worker，Worker的thread start，执行的是ThreadPoolExecutor的runWorker方法
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            // 如果worker启动失败了，执行addWorkerFailed
            if (! workerStarted)
                addWorkerFailed(w);
        }
        return workerStarted;
    }
```

简单看下`addWorkerFailed`

```java
private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // workers 移除刚添加的worker
            if (w != null)
                workers.remove(w);
            // worker数量-1
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }
```

## runWorker方法

worker里的thread，执行了start，调用的是`runWorker`方法。

```java
final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        //help gc
        w.firstTask = null;
        // 这里unlock是因为，new的Worker AQS state=-1，前面讲了必须是0才能加锁，而且0也可以被中断。
        w.unlock(); // allow interrupts
        // 代表被因为异常退出的
        boolean completedAbruptly = true;
        try {
            //无限循环 ，task 不为null则run task，如果task是null则调用 getTask 获取任务
            while (task != null || (task = getTask()) != null) {
                w.lock();

                // 如果线程池>=STOP，确保线程被中断。如果不是，确保没有被中断。
                // 第二个条件要重新判断中断状态，处理shutdownNow清理中断的race
                if ((runStateAtLeast(ctl.get(), STOP) ||
                     (Thread.interrupted() &&
                      runStateAtLeast(ctl.get(), STOP))) &&
                    !wt.isInterrupted())
                    wt.interrupt();
                try {
                    // 空实现，在runTask前，用于继承时自己实现
                    beforeExecute(wt, task);
                    // 捕捉的run方法的Throwable
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        // 也是空实现，在runTask后
                        afterExecute(task, thrown);
                    }
                } finally {
                    // task置null，继续下次循环
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }

            // 执行到这里说明不是因为发生异常执行的finally
            completedAbruptly = false;
        } finally {

            //执行到finally 说明
            // 1. getTask返回null了 退出了循环
            // 2. runTask的时候发生异常被中断
            processWorkerExit(w, completedAbruptly);
        }
    }
```

接着先看下`getTask`方法

## getTask方法

```java
	// 这个方法有以下情况  前4种都是返回null让worker退出
    // 1. 通过调用setMaximumPoolSize使超出了maximumPoolSize
    // 2. 线程池STOP状态，STOP状态任务队列里的也要丢弃
    // 3. SHUTDOWN且queue是空的，这个应该懂了吧！
    // 4. 超时退出。allowCoreThreadTimeOut || workerCount > corePoolSize
    // 5. 阻塞直到获取到任务返回。< corePoolSize
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?


        //无限循环
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            // 这里是上面的2和3
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                //CAS 减少worker数量，是改的ctl，返回null后，worker会执行exit方法
                decrementWorkerCount();
                return null;
            }
            // 获取worker数量
            int wc = workerCountOf(c);
            // worker是否可以超过， set allowCoreThreadTimeOut=true 或者 当前worker > corePoolSize 都可以超时
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            // 这里是1和4
            // 第二个条件是什么意思呢？就是如果你当前worker数量=0，任务队列还不是空的话，是不能返回null的。
            // timedOut的值，最开始设置了是false，所以就算timed=true，第一次进来是直接跳过这里的
            if ((wc > maximumPoolSize || (timed && timedOut))
                && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                //这里是CAS减少worker数量失败了的重试
                continue;
            }

            try {
                // 这里就是根据是否允许超时，选用两个方法
                // 允许超时，则用keepAliveTime做超时时间，如果当前任务队列没有任务，超过这个时间就返回null了
                // 不允许超时，则当前worker数量< corePoolSize take会一直阻塞住，直到拿到runnable
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                    workQueue.take();
                // 表明拿到了runnable，返回执行runTask
                if (r != null)
                    return r;
                // 表明是发生了超时返回的null 进入下一次循环
                timedOut = true;
            } catch (InterruptedException retry) {
                // 表明线程被中断了，把timedOut设置为false重试
                timedOut = false;
            }
        }
    }
```

## processWorkerExit方法

再看下刚才的`processWorkerExit`，线程退出的方法。

```java
private void processWorkerExit(Worker w, boolean completedAbruptly) {
        // 如果是异常导致的退出，那么是没执行ctl的worker数量-1的
        // （另外一种情况是getTask返回null，执行了worker数量-1）
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        //对worker的操作都要加锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 线程池完成任务数量 累加worker完成的量
            completedTaskCount += w.completedTasks;
            // 把worker从HashSet remove掉
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }
        // 尝试终止，只是尝试而已，如果是RUNNING，直接return。和线程池shutdown等方法有关，有兴趣的读者可以自己看下。
        tryTerminate();

        int c = ctl.get();
        
        // 如果是RUNNING 或者 SHUTDOWN状态
        if (runStateLessThan(c, STOP)) {
            // 不是因为异常退出的（就是返回null的）
            if (!completedAbruptly) {
                // min值 最小线程数的check
                // allowCoreThreadTimeOut为true的话是0，否则是corePoolSize，
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;

                // min = 0，当前任务队列不是空的话 0->1 
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                
                // 如果当前work >= min 返回
                // 如果此时 min=1 ，worker数量为0，就会走到下面加一个worker
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            
            //异常退出的 直接加一个worker
            addWorker(null, false);
        }
    }
```

## 总结

本文较为细致地解读了线程池的部分源码，其他方法读者有兴趣可以自己查看。

留几个面试问题看你有没有读明白！

1. 核心线程具体是为什么不会退出的？
2. Java 线程池有哪些关键属性，说说它们的具体含义。
3. 我们线程池execute方法发生异常了会发生什么？
4. 说说线程池中的线程创建时机。
5. 什么时候会执行拒绝策略？

