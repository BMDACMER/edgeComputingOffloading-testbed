package cn.edu.scut.thread;

import cn.edu.scut.bean.EdgeNodeSystem;
import cn.edu.scut.bean.Link;
import cn.edu.scut.bean.Task;
import cn.edu.scut.bean.TaskStatus;
import cn.edu.scut.service.TaskService;
import lombok.Setter;
import lombok.extern.apachecommons.CommonsLog;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Random;

@Component
@Scope("prototype")
@Setter
@Slf4j
@RefreshScope
@CommonsLog
public class TransmissionRunnable implements Runnable {
    // prototype
    private Task task;
    @Value("${env.use-poisson-reliability}")
    private boolean usePoissonReliability;

    @Value("${env.use-constant-reliability}")
    private boolean useConstantReliability;

    @Autowired
    private EdgeNodeSystem edgeNodeSystem;
    @Autowired
    private Random reliabilityRandom;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private TaskService taskService;

    @Override
    public void run() {
        task.setBeginTransmissionTime(LocalDateTime.now());
        double transmissionWaitingTime =  Duration.between(task.getArrivalTime(), task.getBeginTransmissionTime()).toMillis(); //本次传输等待时间
        task.setTransmissionWaitingTime(task.getTransmissionWaitingTime() + (int) transmissionWaitingTime); //传输等待时间累加
        var destination = task.getDestination();
        Link link = edgeNodeSystem.getLinkMap().get(destination);
        double transmissionTime = task.getTaskSize() / link.getTransmissionRate() * 1000; //本次传输时间
        task.setTransmissionTime(task.getTransmissionTime() + (int) transmissionTime); //传输时间累加
        try {
            Thread.sleep((int) transmissionTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        task.setEndTransmissionTime(LocalDateTime.now());
        

        // reliability
        var isFailure = false;
        if (usePoissonReliability) {
            double transmissionFailureRate = link.getTransmissionFailureRate();
            double reliability = Math.exp(- transmissionTime / 1000.0 * transmissionFailureRate);
            if (reliabilityRandom.nextDouble() > reliability) {
                isFailure = true;
            }
        } else if (useConstantReliability) {
            if (reliabilityRandom.nextDouble() > link.getLinkReliability()) {
                isFailure = true;
            }
        } else {
            throw new RuntimeException("error in reliability model!");
        }

        if (isFailure) {
            task.setStatus(TaskStatus.TRANSMISSION_FAILURE);
            // fixed null type exception: we will calculate reward based on the total time.
            task.setExecutionWaitingTime(0);
            task.setExecutionTime(0);
            taskService.updateById(task);
        } else {
            String url = String.format("http://edge-node-%s/edgeNode/task", destination);
            restTemplate.postForObject(url, task, String.class);
            log.info(("transmission success, task " + task.getJobId() + " is sent to " + destination));
        }
    }
}
