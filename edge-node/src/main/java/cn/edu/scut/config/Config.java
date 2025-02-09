package cn.edu.scut.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Random;

@ConfigurationProperties(prefix = "env")
@Configuration
@Slf4j
@Data
public class Config {

    // @Value("${env.reliability-seed}")
    Integer reliabilitySeed;

    // @Value("${env.scheduler-seed}")
    Integer schedulerSeed;

    Integer edgeNodeNumber;

    private Map<Integer, Integer[]> nodeCluster;

    private Integer taskRedundancy;

    private int dynamicRedundancyNumber;

    @Value("${spring.application.name}")
    String springApplicationName;

    @Bean
    public Random reliabilityRandom() {
        Integer id = Integer.parseInt(springApplicationName.split("-")[2]);
        return new Random(reliabilitySeed + id);
    }

    @Bean
    public Random schedulerRandom() {
        Integer id = Integer.parseInt(springApplicationName.split("-")[2]);
        return new Random(schedulerSeed + id);
    }

    @Bean
    @LoadBalanced
    RestTemplate restTemplate() {
        return new RestTemplate();
    }
}