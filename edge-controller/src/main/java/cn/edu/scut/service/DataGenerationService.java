package cn.edu.scut.service;

import cn.edu.scut.bean.*;
import cn.edu.scut.config.SpringConfig;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.math3.distribution.UniformIntegerDistribution;
import org.apache.commons.math3.distribution.UniformRealDistribution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSONObject;


@Service
@Slf4j
@RefreshScope
public class DataGenerationService {
    @Value("${env.min-transmission-failure-rate}")
    private double minTransmissionFailureRate;
    @Value("${env.max-transmission-rate}")
    private double maxTransmissionRate;
    @Value("${env.deadline}")
    private int deadline;
    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;
    @Value("${env.max-transmission-reliability}")
    private double maxTransmissionReliability;

    @Autowired
    private UniformRealDistribution executionFailureRandom;

    @Autowired
    private UniformIntegerDistribution cpuCoreRandom;

    @Autowired
    private UniformRealDistribution taskRateRandom;

    @Autowired
    private UniformRealDistribution transmissionRateRandom;

    @Autowired
    private UniformRealDistribution transmissionFailureRateRandom;

    @Autowired
    private UniformIntegerDistribution taskSizeRandom;

    @Autowired
    private UniformIntegerDistribution taskComplexityRandom;

    @Autowired
    private EdgeNodeService edgeNodeService;

    @Autowired
    private LinkService linkService;

    @Autowired
    private UniformRealDistribution executionReliabilityRandom;
    @Autowired
    private UniformRealDistribution transmissionReliabilityRandom;
    @Autowired
    private UniformRealDistribution taskReliabilityRandom;
    @Autowired
    private UniformRealDistribution taskReliabilityRequirementRandom;
    @Autowired
    private UniformRealDistribution linkExistenceRandom;
    @Value("${env.link-existence-rate}")
    private float linkExistenceRate;
    @Autowired
    private SpringConfig config;

    /**
     * The links between edge nodes and edge nodes are generated and saved to the database
     */
    public void generateEdgeLink() {
        for (int i = 1; i <= edgeNodeNumber; i++) {
            EdgeNode edgeNode = new EdgeNode();
            edgeNode.setEdgeNodeId(i);
            edgeNode.setExecutionFailureRate(executionFailureRandom.sample());
            // reliable edge computing
            edgeNode.setEdgeNodeReliability(executionReliabilityRandom.sample());
            edgeNode.setCpuNum(cpuCoreRandom.sample() * 4L);
            edgeNode.setTaskRate(taskRateRandom.sample());
            edgeNodeService.save(edgeNode);
            for (int j = 1; j <= i; j++) {
                Link link = new Link();
                link.setSource(i);
                link.setDestination(j);
                //Whether there is a link is judged, and if less than, there is no link, which is reflected by the transmission rate of 0
                if(i == j){
                    link.setTransmissionRate(maxTransmissionRate * Constants.Giga.value * Constants.Byte.value);
                    link.setTransmissionFailureRate(minTransmissionFailureRate);
                    // reliable edge computing
                    link.setLinkReliability(maxTransmissionReliability);
                }else if (linkExistenceRandom.sample() < linkExistenceRate) {
                    link.setTransmissionRate(0.0 * Constants.Mega.value * Constants.Byte.value);
                    link.setTransmissionFailureRate(1.0);
                    // reliable edge computing
                    link.setLinkReliability(0.0);
                }else{
                    double transmissionRate = transmissionRateRandom.sample() * Constants.Mega.value * Constants.Byte.value;
                    link.setTransmissionRate(transmissionRate);
                    link.setTransmissionFailureRate(transmissionFailureRateRandom.sample());
                    // reliable edge computing
                    link.setLinkReliability(transmissionReliabilityRandom.sample());
                }
                linkService.save(link);
                //If it is not the same node, a reverse link is generated and saved to the database
                if(i != j){
                    Link reverseLink = JSONObject.parseObject(JSONObject.toJSONString(link), Link.class);
                    reverseLink.setId(null);
                    try{
                    reverseLink.setSource(j);
                    reverseLink.setDestination(i);
                    linkService.save(reverseLink);
                    log.info("generate reverse link: {}", reverseLink);
                    }catch(Exception e){
                        log.error("generate reverse link error", e);
                        log.error("link: {}", link);
                        log.error("reverse link: {}", reverseLink);
                    }
                }
            }
        }
    }

    public Task generateTask(Integer edgeNodeId) {
        Task task = new Task();
        task.setHop(0);
        task.setFinalExecutionClusterFound(0);
        task.setTaskSize(((long) taskSizeRandom.sample()) * StoreConstants.Kilo.value * StoreConstants.Byte.value);
        task.setTaskComplexity(taskComplexityRandom.sample());
        task.setCpuCycle(task.getTaskComplexity() * task.getTaskSize());
        task.setDeadline(deadline);
        task.setSource(edgeNodeId);
        task.setStatus(TaskStatus.NEW);
        // reliable edge computing
        task.setTaskReliability(taskReliabilityRandom.sample());
        task.setReliabilityRequirement(taskReliabilityRequirementRandom.sample());

        task.setTransmissionTime(0);
        task.setTransmissionWaitingTime(0);
        task.setExecutionTime(0);
        task.setExecutionWaitingTime(0);
        return task;
    }
}