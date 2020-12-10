---
layout: post
title: ClassLoader 与打破双亲委派模型
categories: post
subcate: new
---

## 双亲委托机制

1. 类加载器的委托是优先交给父类加载器先去尝试加载
2. 父加载器和子加载器是一种包含关系（父亲被包含，非继承）

## 三个类加载器的关系

```BootStrapClassLoader```: c++实现。

```ExtClassLoader``` 和 ```AppClassLoader```: 在 ```sun.misc.Launcher```类。

首先看```ClassLoader```类：

```java
public abstract class ClassLoader {

    private static native void registerNatives();
    static {
        registerNatives();
    }

    // The parent class loader for delegation
    // Note: VM hardcoded the offset of this field, thus all new fields
    // must be added *after* it.
    //这里即是所谓的父类，其实只是一个成员变量。子类的成员变量为父类。
    private final ClassLoader parent;
...
```

后两个类加载器：

```java
static class AppClassLoader extends URLClassLoader {
...
}
static class ExtClassLoader extends URLClassLoader {
...
}
```

继承关系

![image-20200115232749606]({{site.picpath}}/image-20200115232749606.png)

可以看到并不是extends的继承，所谓的父类加载器只是包含关系。

再看```Launcher```构造器

```java
public Launcher() {
        Launcher.ExtClassLoader var1;
        try {
            //初始化ExtClassLoader
            var1 = Launcher.ExtClassLoader.getExtClassLoader();
        } catch (IOException var10) {
            throw new InternalError("Could not create extension class loader", var10);
        }

        try {
            //这里把上面的 ExtClassLoader 传进来了。
            this.loader = Launcher.AppClassLoader.getAppClassLoader(var1);
        } catch (IOException var9) {
            throw new InternalError("Could not create application class loader", var9);
        }

        Thread.currentThread().setContextClassLoader(this.loader);
        String var2 = System.getProperty("java.security.manager");
        if (var2 != null) {
            SecurityManager var3 = null;
            if (!"".equals(var2) && !"default".equals(var2)) {
                try {
                    var3 = (SecurityManager)this.loader.loadClass(var2).newInstance();
                } catch (IllegalAccessException var5) {
                } catch (InstantiationException var6) {
                } catch (ClassNotFoundException var7) {
                } catch (ClassCastException var8) {
                }
            } else {
                var3 = new SecurityManager();
            }

            if (var3 == null) {
                throw new InternalError("Could not create SecurityManager: " + var2);
            }

            System.setSecurityManager(var3);
        }

    }
```

进入`getAppClassLoader`方法

```java
public static ClassLoader getAppClassLoader(final ClassLoader var0) throws IOException {
    final String var1 = System.getProperty("java.class.path");
    final File[] var2 = var1 == null ? new File[0] : Launcher.getClassPath(var1);
    return (ClassLoader)AccessController.doPrivileged(new PrivilegedAction<Launcher.AppClassLoader>() {
        public Launcher.AppClassLoader run() {
            URL[] var1x = var1 == null ? new URL[0] : Launcher.pathToURLs(var2);
            return new Launcher.AppClassLoader(var1x, var0);
        }
    });
}
```

发现刚才传入的```ExtClassLoader```作为构造器的第二个参数。一直找下去发现在```ClassLoader```的构造器赋值。

```java
private ClassLoader(Void unused, ClassLoader parent) {\
    	//
        this.parent = parent;
        if (ParallelLoaders.isRegistered(this.getClass())) {
            parallelLockMap = new ConcurrentHashMap<>();
            package2certs = new ConcurrentHashMap<>();
            domains =
                Collections.synchronizedSet(new HashSet<ProtectionDomain>());
            assertionLock = new Object();
        } else {
            // no finer-grained lock; lock on the classloader instance
            parallelLockMap = null;
            package2certs = new Hashtable<>();
            domains = new HashSet<>();
            assertionLock = this;
        }
    }
```

## 具体委托的过程

```java
protected Class<?> loadClass(String name, boolean resolve)
    throws ClassNotFoundException
{
    synchronized (getClassLoadingLock(name)) {
        // First, check if the class has already been loaded
        //首先检查是不是已经加载过了
        Class<?> c = findLoadedClass(name);
        if (c == null) {
            //c是null没有加载过
            long t0 = System.nanoTime();
            try {
                //如果parent不是空，就调用parent的load方法，parent
                //一样会执行到这里，递归调用直到parent为null
                if (parent != null) {
                    c = parent.loadClass(name, false);
                } else {
                    //parent是空，说明是Bootstrap ClassLoader
                    c = findBootstrapClassOrNull(name);
                }
            } catch (ClassNotFoundException e) {
                // ClassNotFoundException thrown if class not found
                // from the non-null parent class loader
            }
			//走到这里说明一直向上找到bootstrap都没有找到，则调用该类加载器的findClass方法。
            if (c == null) {
                // If still not found, then invoke findClass in order
                // to find the class.
                long t1 = System.nanoTime();
                c = findClass(name);

                // this is the defining class loader; record the stats
                sun.misc.PerfCounter.getParentDelegationTime().addTime(t1 - t0);
                sun.misc.PerfCounter.getFindClassTime().addElapsedTimeFrom(t1);
                sun.misc.PerfCounter.getFindClasses().increment();
            }
        }
        if (resolve) {
            resolveClass(c);
        }
        return c;
    }
}
```

## 打破双亲委托机制

自定义```MyClassLoader```，继承```ClassLoader```抽象类，重写```loadClass(String name, boolean resolve)```即可。

例如：

```java
public class CustomClassLoader extends ClassLoader{
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        //自己实现从资源读取字节流返回Class
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> clazz = null;
		//java开头的，简单的认为是java自己的class，我们自己的class用自定义classLoader加载，
        //java自己的类还是用SystemClassLoader去加载。
        if (name.startsWith("java.")) {
            try {
                clazz = ClassLoader.getSystemClassLoader().loadClass(name);
                if (clazz != null) {
                    if (resolve) {
                        resolveClass(clazz);
                    }
                }
            } catch (Exception e) {
                //ignore
            }
        }
		//自己实现的加载Class的方法
        try {
            clazz = findClass(name);
        } catch (Exception e) {
            //...
        }
		//如果加载不了并且parent不是空，由父类去找
        if (clazz == null && getParent() != null) {
            getParent().loadClass(name);
        }
        return clazz;
    }
}
```

这样就简单地实现了从父类往子类找的类加载器。

可不可以用这种方式加载String？

答案是不行的，Java 对 java.lang 包做了保护。会抛出异常：

```
java.lang.SecurityException: Prohibited package name: java.lang
```

## 线程上下文类加载器

SPI（Service Provider Interface）

rt.jar里定义了规范，例如 JDBC，JNDI，JMS，都是接口。规范由各厂商自己去实现。

以JDBC MySQL为例。

```java
Connection conn = null;
Statement stmt = null;
Class.forName("com.mysql.jdbc.Driver");
conn = DriverManager.getConnection("...");
```

```Connection``` ```Statement```都是都是java.sql包下的，我们只是对MySQL的Driver反射了一下，```Connection``` ```Statement```这些不都是由`BootStrap ClassLoader`去加载吗，但是加载的这些空实现又有什么意义？显然，`BootStrap ClassLoader`并不能加载到具体实现，是由App去加载的。这样就违反了双亲委托机制。先看MySQL的Driver。

```java
public class Driver extends NonRegisteringDriver implements java.sql.Driver {
    //
    // Register ourselves with the DriverManager
    //
    static {
        try {
            java.sql.DriverManager.registerDriver(new Driver());
        } catch (SQLException E) {
            throw new RuntimeException("Can't register driver!");
        }
    }
```

反射会执行static，在```DriverManager```完成了注册。

```java
public static synchronized void registerDriver(java.sql.Driver driver,
            DriverAction da)
        throws SQLException {

        /* Register the driver if it has not already been added to our list */
        if(driver != null) {
            registeredDrivers.addIfAbsent(new DriverInfo(driver, da));
        } else {
            // This is for compatibility with the original DriverManager
            throw new NullPointerException();
        }

        println("registerDriver: " + driver);

    }
```

注册只是往`registeredDrivers`这个`CopyOnWriteArrayList`里add，没有其他操作。

继续看实际实现的`Driver`类是如何被加载的。

```java
 //  Worker method called by the public getConnection() methods.
    private static Connection getConnection(
        String url, java.util.Properties info, Class<?> caller) throws SQLException {
        /*
         * When callerCl is null, we should check the application's
         * (which is invoking this class indirectly)
         * classloader, so that the JDBC driver class outside rt.jar
         * can be loaded from here.
         */
        ClassLoader callerCL = caller != null ? caller.getClassLoader() : null;
        synchronized(DriverManager.class) {
            // synchronize loading of the correct classloader.
            if (callerCL == null) {
                callerCL = Thread.currentThread().getContextClassLoader();
            }
        }

        if(url == null) {
            throw new SQLException("The url cannot be null", "08001");
        }

        println("DriverManager.getConnection(\"" + url + "\")");

        // Walk through the loaded registeredDrivers attempting to make a connection.
        // Remember the first exception that gets raised so we can reraise it.
        SQLException reason = null;

        for(DriverInfo aDriver : registeredDrivers) {
            // If the caller does not have permission to load the driver then
            // skip it.
            if(isDriverAllowed(aDriver.driver, callerCL)) {
                try {
                    println("    trying " + aDriver.driver.getClass().getName());
                    Connection con = aDriver.driver.connect(url, info);
                    if (con != null) {
                        // Success!
                        println("getConnection returning " + aDriver.driver.getClass().getName());
                        return (con);
                    }
                } catch (SQLException ex) {
                    if (reason == null) {
                        reason = ex;
                    }
                }

            } else {
                println("    skipping: " + aDriver.getClass().getName());
            }

        }

        // if we got here nobody could connect.
        if (reason != null)    {
            println("getConnection failed: " + reason);
            throw reason;
        }

        println("getConnection: no suitable driver found for "+ url);
        throw new SQLException("No suitable driver found for "+ url, "08001");
    }
```

```java
private static boolean isDriverAllowed(Driver driver, ClassLoader classLoader) {
        boolean result = false;
        if(driver != null) {
            Class<?> aClass = null;
            try {
                aClass =  Class.forName(driver.getClass().getName(), true, classLoader);
            } catch (Exception ex) {
                result = false;
            }

             result = ( aClass == driver.getClass() ) ? true : false;
        }

        return result;
    }
```

```callerCL```是调用者的class Loader，如果是null，则是```Thread.currentThread().getContextClassLoader()```，显然是```AppClassLoader```。

在```isDriverAllowed```方法中，使用`callerCl`完成了对实际```Driver```实现的类加载。

Java 使用这种方式，解决了 Java SPI 与 双亲委派的矛盾。

