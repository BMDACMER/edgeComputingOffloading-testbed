package cn.edu.scut.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class RunningPointTest {
    @Value("${env.runner}")
    private String runningType;

    @Value("${env.seed}")
    private int seed;

    @Value("${Spring.application.name}")
    private String appName;

    @Test
    public void run() {
        System.out.println(runningType);
        System.out.println(123);
        System.out.println(seed);
        System.out.println(appName);
        }


}