package cn.edu.scut;

import cn.edu.scut.utils.SpringBeanUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.Arrays;
import java.util.stream.StreamSupport;


@EnableDiscoveryClient
@SpringBootApplication
@EnableAsync
@Slf4j
public class ControllerApp {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(ControllerApp.class, args);
        SpringBeanUtils.setApplicationContext(context);
    }

}