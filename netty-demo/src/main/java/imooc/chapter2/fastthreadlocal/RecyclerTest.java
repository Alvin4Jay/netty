package imooc.chapter2.fastthreadlocal;

import io.netty.util.Recycler;
import io.netty.util.concurrent.FastThreadLocalThread;

/**
 * 对象池Recycler测试
 *
 * @author xuanjian
 */
public class RecyclerTest {

    private static final Recycler<User> RECYCLER = new Recycler<User>() {
        @Override
        protected User newObject(Handle<User> handle) {
            return new User(handle);
        }
    };

    public static class User {
        private Recycler.Handle<User> handle;

        public User(Recycler.Handle<User> handle) {
            this.handle = handle;
        }

        public void recycle() {
            handle.recycle(this);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        User user = RECYCLER.get();

        user.recycle(); // 同线程回收对象

        new FastThreadLocalThread(() -> {
            user.recycle();
        }).start(); // 异线程回收对象

        System.out.println(user == RECYCLER.get());
    }

}
