---
layout: post
title: CompletableFuture 使用介绍
categories: post
subcate: new
---

## 基本用法

`CompletableFuture` 比`Future`功能更强。

```java
public class CompletableFutureTest {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        CompletableFuture<Integer> completableFuture = new CompletableFuture<>();

        new Thread(() -> {
            int i = get();
            completableFuture.complete(i);
        }).start();

        System.out.println("non block");

        //Optional.ofNullable(completableFuture.get()).ifPresent(System.out::println);

        completableFuture.whenComplete((i, t) -> {
            Optional.ofNullable(i).ifPresent(System.out::println);
            Optional.ofNullable(t).ifPresent(Throwable::printStackTrace);
        });
        System.out.println("non block");
    }

    private static int get() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return 1;
    }
}
```

`get()`阻塞等待结果。

`whenComplete`是非阻塞的，传入`BiConsumer`，泛型类型和`Throwable`两个参数的lamda表达式。

## supplyAsync

一般很少使用new的方式使用，直接使用静态方法。

```java
public class CompletableFutureTest2 {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        CompletableFuture.supplyAsync(CompletableFutureTest2::get)
                .whenComplete((i, t) -> {
                    Optional.ofNullable(i).ifPresent(System.out::println);
                    Optional.ofNullable(t).ifPresent(Throwable::printStackTrace);
                });
//                .join();
//                .get();
        System.out.println("non-block");
        //dont exit
        Thread.currentThread().join();
    }

    private static int get() {
        try {
            System.out.println("Daemon-"+Thread.currentThread().isDaemon());
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return 1;
    }
}
```

输出

```
Daemon-true Name-ForkJoinPool.commonPool-worker-1
non-block
1
```

`supplyAsync` 默认是往内置的`ForkJoinPool`提交`isDaemon`为true的线程。

所以我们要让main join自己，使main不会退出。不然我们提交的Runnable还没执行到，main就退出了。

`supplyAsync`也可以手动指定线程池，如果其`ThreadFactory`内返回的线程是非守护线程，也是不会退出的。

我们当然也可以手动使用`get()`或`join()`阻塞住。

## 流水线工作

get()之后，将get的结果乘以1000

```java
public class CompletableFutureTest3 {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        CompletableFuture.supplyAsync(CompletableFutureTest3::get)
                .thenApply(CompletableFutureTest3::multiply)
                .whenComplete((i, t) -> {
                    Optional.ofNullable(i).ifPresent(System.out::println);
                    Optional.ofNullable(t).ifPresent(Throwable::printStackTrace);
                }).join();
    }

    private static int get() {
        try {
            System.out.println("Daemon-" + Thread.currentThread().isDaemon() + " Name-" + Thread.currentThread().getName());
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return 1;
    }

    private static int multiply(int i) {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return i * 1000;
    }
}
```

`thenApply`使用前一步的返回值作为参数。

如果我们对一组 int 进行这个操作，并且将结果求和，如何做呢？

```java
public class CompletableFutureTest3 {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        int sum = list.stream().map(i -> CompletableFuture.supplyAsync(() -> iGet(i)))
                .map(cf -> cf.thenApply(CompletableFutureTest3::multiply))
                .map(cf->cf.whenComplete((i,t)->{
                    Optional.ofNullable(i).ifPresent(System.out::println);
                    Optional.ofNullable(t).ifPresent(Throwable::printStackTrace);
                }))
                .map(CompletableFuture::join)
                .mapToInt(value -> value).sum();
        System.out.println("sum: " + sum);
    }
    
    private static int iGet(int i) {
        try {
            System.out.println("i=" + i + " Name: " + Thread.currentThread().getName());
            Thread.sleep(i * 100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return i;
    }

    private static int multiply(int i) {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return i * 1000;
    }
}
```

输出

```
i=1 Name: ForkJoinPool.commonPool-worker-1
1000
i=2 Name: ForkJoinPool.commonPool-worker-1
2000
i=3 Name: ForkJoinPool.commonPool-worker-1
3000
i=4 Name: ForkJoinPool.commonPool-worker-1
4000
i=5 Name: ForkJoinPool.commonPool-worker-1
5000
i=6 Name: ForkJoinPool.commonPool-worker-1
6000
i=7 Name: ForkJoinPool.commonPool-worker-1
7000
i=8 Name: ForkJoinPool.commonPool-worker-1
8000
i=9 Name: ForkJoinPool.commonPool-worker-1
9000
i=10 Name: ForkJoinPool.commonPool-worker-1
10000
sum: 55000
```

结合Stream，很轻松就做到了。根据结果是串行的。

## 常用 API

- `supplyAsync` 
- `thenApply`  value
- `whenComplete`
- `whenCompleteAsync`
- `handle` value , throwable
- `thenRun` runnable
- `thenAccpet` consumer 返回void
- `thenCompose`
- `thenCombine`
- `thenAcceptBoth`

带`Async`的都是提交异步的方法。

```java
CompletableFuture.supplyAsync(() -> 1)
                .thenCompose(i -> CompletableFuture.supplyAsync(() -> 10 * i))
                .thenAccept(System.out::println);

        CompletableFuture.supplyAsync(() -> 1)
                .thenCombine(CompletableFuture.supplyAsync(() -> 2), Integer::sum)
                .thenAccept(System.out::println);

        CompletableFuture.supplyAsync(() -> 1)
                .thenAcceptBoth(CompletableFuture.supplyAsync(() -> 2), (r1, r2) -> {
                    System.out.println(r1+r2);
                });
```

- `runAfterBoth`
- `applyToEither`
- `acceptEither`
- `runAfterEither`
- `anyOf`
- `allOf`

```java
public static void main(String[] args) {
        CompletableFuture.supplyAsync(() -> 1)
                .runAfterBoth(CompletableFuture.supplyAsync(() -> 1), () -> System.out.println("done")).join();


        //两个有一个结束即可
        CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 1;
        })
                .applyToEither(CompletableFuture.supplyAsync(() -> 2), i -> i * 10)
                .thenAccept(System.out::println);

        //不需要返回值
        CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 1;
        }).acceptEither(CompletableFuture.supplyAsync(() -> 2), System.out::println);

        //不消费either的结果
        CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return 1;
        }).runAfterEither(CompletableFuture.supplyAsync(() -> 2), ()-> System.out.println("done")).join();


        //所有的执行完
        List<CompletableFuture<Integer>> collect = Arrays.asList(1, 2, 3, 4, 5).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> get(i))).collect(Collectors.toList());
        CompletableFuture[] futures = new CompletableFuture[collect.size()];
        CompletableFuture.allOf(collect.toArray(futures))
                .thenRun(()-> System.out.println("done")).join();

        //任意一个执行完
        List<CompletableFuture<Integer>> collect1 = Arrays.asList(1, 2, 3, 4, 5).stream()
                .map(i -> CompletableFuture.supplyAsync(() -> get(i))).collect(Collectors.toList());
        CompletableFuture[] futures1 = new CompletableFuture[collect1.size()];
        CompletableFuture.anyOf(collect1.toArray(futures1))
                .thenRun(() -> System.out.println("done")).join();
    }
```

