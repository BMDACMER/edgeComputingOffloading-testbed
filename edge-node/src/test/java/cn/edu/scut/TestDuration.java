package cn.edu.scut;

//import org.apache.tomcat.jni.Time;

import java.time.Duration;
import java.time.LocalDateTime;

import static java.lang.Thread.sleep;

//import static org.apache.tomcat.jni.Time.*;

public class TestDuration {
    private LocalDateTime beginExecutionTime;
    private LocalDateTime endTransmissionTime;
    private int executionWaitingTime;

    // 构造方法等...

    // 设置开始执行时间
    public void setBeginExecutionTime(LocalDateTime beginExecutionTime) {
        this.beginExecutionTime = beginExecutionTime;
    }

    // 设置结束传输时间
    public void setEndTransmissionTime(LocalDateTime endTransmissionTime) {
        this.endTransmissionTime = endTransmissionTime;
    }

    // 获取执行等待时间
    public int getExecutionWaitingTime() {
        return executionWaitingTime;
    }

    // 设置执行等待时间
    public void setExecutionWaitingTime(int executionWaitingTime) {
        this.executionWaitingTime = executionWaitingTime;
    }

    // 测试Duration并累加执行等待时间的方法
    public void testDuration() throws InterruptedException {
        // 设置开始执行时间
        setBeginExecutionTime(LocalDateTime.now());

        // 执行一些任务（这里假设有耗时的任务）
        sleep(2000);

        // 设置结束传输时间
        setEndTransmissionTime(LocalDateTime.now());

        // 计算本次执行等待时间
        double executionWaitingTime = Duration.between(beginExecutionTime, endTransmissionTime).toMillis();

        // 执行等待时间累加
        setExecutionWaitingTime(getExecutionWaitingTime() + (int) executionWaitingTime);

        // 输出结果（可根据需要调整）
        System.out.println("本次执行等待时间：" + executionWaitingTime + " 毫秒");
        System.out.println("累计执行等待时间：" + getExecutionWaitingTime() + " 毫秒");
    }

    public static void main(String[] args) throws InterruptedException {
        TestDuration task = new TestDuration();
        task.testDuration();
    }
}
