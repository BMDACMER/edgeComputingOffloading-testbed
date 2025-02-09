package cn.edu.scut;

import ai.djl.translate.TranslateException;
import cn.edu.scut.util.RunningPoint;
import cn.edu.scut.util.SpringBeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class ExperimentApp {
    public static void main(String[] args) throws TranslateException {
        var context = SpringApplication.run(ExperimentApp.class, args);
        SpringBeanUtils.setApplicationContext(context);
        var runningPoint =  context.getBean(RunningPoint.class);
        // waiting for edge-nodes and edge-controller start.
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        runningPoint.run();
        log.info("end of the experiment");
    }

}