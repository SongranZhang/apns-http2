package com.linkedkeeper.apns;

import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by zhangsongran@linkedkeeper.com on 17/1/1.
 */
public class TestPromise {

    public static void main(String[] args) {
        try {
//            new TestPromise().testThread();
//            new TestPromise().testPromise();
            new TestPromise().testFuture();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void testFuture() throws Exception {
        final DefaultEventExecutor wang = new DefaultEventExecutor();
        final DefaultEventExecutor li = new DefaultEventExecutor();

        wang.submit(new Runnable() {
            @Override
            public void run() {
                System.out.println(Thread.currentThread().getName() + " 1. 这是一道简单的题");
            }
        });


        wang.submit(new Runnable() {
            @Override
            public void run() {

                Future<String> result = li.submit(new Callable<String>() {
                    @Override
                    public String call() throws Exception {
                        for (int i = 0; i <= 10000000; i++) {
                            for (int j = 0; j <= 1000000; j++) {
                                ;
                            }
                        }
                        System.out.println(Thread.currentThread().getName() + " 2. 这是一道复杂的题");
                        return null;
                    }
                });
                result.addListener(new GenericFutureListener<Future<? super String>>() {
                    @Override
                    public void operationComplete(Future<? super String> future) throws Exception {
                        System.out.println(Thread.currentThread().getName() + " 3. 复杂题执行结果");
                    }
                });
            }
        });

        wang.submit(new Runnable() {
            @Override
            public void run() {
                System.out.println(Thread.currentThread().getName() + " 3. 这是一道简单的题");
            }
        });
    }

    public void testPromise() throws Exception {
        final DefaultEventExecutor wang = new DefaultEventExecutor();
        final DefaultEventExecutor li = new DefaultEventExecutor();

        wang.execute(new Runnable() {
            @Override
            public void run() {
                System.out.println(Thread.currentThread().getName() + " 1. 这是一道简单的题");
            }
        });

        wang.execute(new Runnable() {
            @Override
            public void run() {
                final Promise<Integer> promise = wang.newPromise();
                promise.addListener(new GenericFutureListener<Future<? super Integer>>() {
                    @Override
                    public void operationComplete(Future<? super Integer> future) throws Exception {
                        System.out.println(Thread.currentThread().getName() + " 复杂题执行结果");
                    }
                });
                li.execute(new Runnable() {
                    @Override
                    public void run() {
                        System.out.println(Thread.currentThread().getName() + " 2. 这是一道复杂的题");
                        promise.setSuccess(10);
                    }
                });
            }
        });

        wang.execute(new Runnable() {
            @Override
            public void run() {
                System.out.println(Thread.currentThread().getName() + " 3. 这是一道简单的题");
            }
        });
    }

    public void testThread() throws Exception {
        final Person wang = new Person("wang");
        final Person li = new Person("li");
        li.start();                     //启动小王
        wang.start();                   //启动小李


        wang.submit(new Runnable() {    //提交一个简单的题
            @Override
            public void run() {
                System.out.println(Thread.currentThread().getName() + " 1. 这是一道简单的题");
            }
        });

        wang.submit(new Runnable() {   //提交一个复杂的题
            @Override
            public void run() {

                li.submit(new Runnable() {      //将复杂的题交给li来做
                    @Override
                    public void run() {
                        System.out.println(Thread.currentThread().getName() + " 2. 这是一道复杂的题");
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        wang.submit(new Runnable() {            //做完之后将结果作为Task返回给wang
                            @Override
                            public void run() {
                                System.out.println(Thread.currentThread().getName() + " 复杂题执行结果");

                            }
                        });
                    }
                });

            }
        });

        wang.submit(new Runnable() {            //提交一个简单的题
            @Override
            public void run() {
                System.out.println(Thread.currentThread().getName() + " 3. 这是一道简单的题");
            }
        });

    }

    public class Person extends Thread {
        BlockingQueue<Runnable> taskQueue;              //任务队列

        public Person(String name) {
            super(name);
            taskQueue = new LinkedBlockingQueue<>();
        }

        @Override
        public void run() {
            while (true) {                               //无限循环, 不断从任务队列取任务
                try {
                    Runnable task = taskQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        public void submit(Runnable task) {             //将任务提交到任务队列中去
            taskQueue.offer(task);
        }
    }

}
