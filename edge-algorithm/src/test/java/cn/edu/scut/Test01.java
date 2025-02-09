package cn.edu.scut;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

@Slf4j
public class Test01 {
    public static void main(String[] args) {
        System.out.println("test JDK");
    }

    @Test
    public void testStringMatch() {
//        String str1 = "Hello";
//        String str2 = "Hello";
//
//        System.out.println(str1.equals(str2)); // true
//        System.out.println(str1 == str2); // true (因为字符串常量池的优化)

        MyClass obj1 = new MyClass();
        MyClass obj2 = new MyClass();

        System.out.println(obj1.equals(obj2)); // 使用equals()比较内容
        System.out.println(obj1 == obj2); // 使用==比较引用
    }

    @Test
    public void testTile() {
        var manager = NDManager.newBaseManager();
        NDArray array = manager.create(new float[] {0f, 1f, 2f, 3f});
        log.info("array.tile: {}", array.repeat(0, 3));
        log.info("array.tile: {}", array.repeat(0, 3).reshape(new Shape(4, 3)));
        log.info("array.tile: {}", array.repeat(0, 3).reshape(new Shape(4, 3)).transpose());
    }

    /**
     * 判断对象是否为空
     * @param obj
     * @return
     */
    public static boolean isNull(Object obj){
        if(obj==null){
            return true;
        }
        if("".equals(obj)){
            return true;
        }
        return false;
    }

    @Test
    public void testNull() {
        MyClass myClass = new MyClass();
        System.out.println(isNull(myClass));
    }
}

class MyClass {
    private int value;

    // 覆盖equals()方法，比较对象的内容
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true; // 同一内存地址，相等
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false; // 类型不同，不相等
        }
        MyClass myClass = (MyClass) obj;
        return value == myClass.value; // 比较内容
    }
}