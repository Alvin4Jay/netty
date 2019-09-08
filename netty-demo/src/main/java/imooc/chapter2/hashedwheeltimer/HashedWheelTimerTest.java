package imooc.chapter2.hashedwheeltimer;

import io.netty.util.HashedWheelTimer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link HashedWheelTimer}测试
 *
 * @author xuanjian
 */
public class HashedWheelTimerTest {

    public static void main(String[] args) throws InterruptedException {

        final HashedWheelTimer timer = new HashedWheelTimer();
        final CountDownLatch countDownLatch = new CountDownLatch(1);

        timer.newTimeout(timeout -> {
            System.out.println(Thread.currentThread().getName() + "---executed...");
            countDownLatch.countDown();
        }, 3, TimeUnit.SECONDS);

        countDownLatch.await();
        timer.stop();
    }

}
