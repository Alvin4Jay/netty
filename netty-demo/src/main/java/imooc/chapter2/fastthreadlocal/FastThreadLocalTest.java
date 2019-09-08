package imooc.chapter2.fastthreadlocal;

import io.netty.util.concurrent.FastThreadLocal;

import java.util.concurrent.TimeUnit;

/**
 * {@link FastThreadLocal} 测试
 *
 * @author xuanjian
 */
public class FastThreadLocalTest {

    private static FastThreadLocal<Object> threadLocal0 = new FastThreadLocal<Object>() {
        @Override
        protected Object initialValue() throws Exception {
            return new Object();
        }
    };

    private static FastThreadLocal<Object> threadLocal1 = new FastThreadLocal<Object>() {
        @Override
        protected Object initialValue() throws Exception {
            return new Object();
        }
    };

    public static void main(String[] args) {

        new Thread(() -> {
            Object o = threadLocal0.get();
            System.out.println(o);
            while (true) {
                threadLocal0.set(new Object());
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

        new Thread(() -> {
            Object o = threadLocal0.get();
            System.out.println(o);
            while (true) {
                System.out.println(threadLocal0.get() == o);
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }

}
