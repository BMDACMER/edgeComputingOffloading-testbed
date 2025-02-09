package cn.edu.scut.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;

import cn.edu.scut.scheduler.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import cn.edu.scut.bean.Constants;
import cn.edu.scut.bean.EdgeNode;
import cn.edu.scut.bean.EdgeNodeSystem;
import cn.edu.scut.bean.Link;
import cn.edu.scut.bean.Task;
import cn.edu.scut.config.Config;
import cn.edu.scut.queue.ExecutionQueue;
import cn.edu.scut.queue.TransmissionQueue;
import cn.edu.scut.util.EnvUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Setter
@Getter
@Service
@Slf4j
@RefreshScope
public class EdgeNodeSystemService implements InitializingBean {

    @Value("${spring.application.name}")
    private String name;
    @Value("${env.seed}")
    private Integer seed;
    @Value("${env.cpu-capacity}")
    private Integer cpuCapacity;
    @Value("${env.scheduler}")
    private String scheduler;
    @Value("${env.min-cpu-core}")
    private int minCpuCore;
    @Value("${env.queue-coef}")
    private float queueCoef;
    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;
    private int offloadingShape;

    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private EdgeNodeSystem edgeNodeSystem;
    @Lazy
    @Autowired
    private DRLScheduler DRLScheduler;
    @Autowired(required = false)
    private QwenScheduler qwenScheduler;

    @Lazy
    @Autowired
    private RandomScheduler randomScheduler;

    @Lazy
    @Autowired
    private ReliabilityTwoChoice reliabilityTwoChoice;

    @Lazy
    @Autowired
    private ReactiveScheduler reactiveScheduler;

    @Lazy
    @Autowired
    private ESFScheduler esfScheduler;

    @Autowired
    private TwoChoiceScheduler twoChoiceScheduler;

    @Autowired
    private EdgeNodeService edgeNodeService;

    @Autowired
    private LinkService linkService;

    @Autowired
    private TaskService taskService;
    @Autowired
    private Config config;
    @Autowired
    private EnvUtils envUtils;

    @Value("${env.restart-time}")
    private int restartTime;
    @Value("${env.task-redundancy}")
    private int taskRedundancy;

    @Override
    public void afterPropertiesSet() {
        offloadingShape = edgeNodeNumber + 1;
    }


    @Async
    public void processTaskFromUser(Task task) {
        if(task.getFinalExecutionClusterFound()==1){
            //任务已经找到最终执行集群，现在发送的是冗余任务
            //直接执行,无需预测下一跳
            task.setEndTransmissionTime(LocalDateTime.now());
            edgeNodeSystem.getExecutionQueue().add(task);
            return;
        }
        // 兼容 redundancy task
        int[] actions = switch (scheduler) {
            case "rl" -> DRLScheduler.selectAction(task);
            case "qwen" -> qwenScheduler.selectAction(task);
            case "random" -> randomScheduler.selectAction(task);
            case "reactive" -> reactiveScheduler.selectAction(task);
            case "reliability-two-choice" -> reliabilityTwoChoice.selectAction(task);
            case "esf" -> esfScheduler.selectAction(task);
            case "two-choice" -> twoChoiceScheduler.selectAction(task);
            default -> throw new RuntimeException("no scheduler");
        };
         log.info("========actions comes from edge-node --> EdgeNodeSystemService.java {}", actions);
         log.info("actions.length = {}", actions.length);
        task.setAction(JSONArray.toJSONString(actions));
        taskService.updateById(task);
        for (int i = 0; i < actions.length; i++) {
            // 不做卸载决策的动作，虚拟节点 n+1
            if (actions[i] == offloadingShape) {
                if (i == 0) {
                    throw new RuntimeException("the action of the first task can not be offloading shape!!!");
                }
                break;
            }
            Task copyTask;
            // primary task
            if (i == 0) {
                copyTask = task;
            } else {
                // backup task
                copyTask = JSONObject.parseObject(JSONObject.toJSONString(task), Task.class);
                // store backup task in database
                copyTask.setId(null);
                taskService.save(copyTask);
            }
            processEachTask(copyTask, actions[i], i);
        }
    }

    private void processEachTask(Task task, Integer action, int index) {
        try{
        var id = Integer.parseInt(name.split("-")[2]);
        task.setDestination(action); //将任务的目的地设置为下一跳
        if (action.equals(id)) {
            if(task.getFinalExecutionClusterFound()==0){
                task.setFinalExecutionClusterFound(1);
             }
            task.setEndTransmissionTime(LocalDateTime.now()); //用于记录任务的执行等待时间
            double executionTime = task.getCpuCycle().doubleValue() / edgeNodeSystem.getEdgeNode().getCapacity().doubleValue() * 1000; //本次执行时间
            task.setExecutionTime(task.getExecutionTime() + (int) executionTime); //执行时间累加
            edgeNodeSystem.getExecutionQueue().add(task);
        } else {
            task.setHop(task.getHop() + 1);
            task.setSource(id); //将任务的来源设置为当前节点
            edgeNodeSystem.getTransmissionQueueMap().get(action).add(task);
        }
        }catch (Exception e){
            log.error("error in processEachTask: {}", e.getMessage());
            log.error("task info: {}",task);
            log.error("action: {}",action);
        }
    }

    public void init() {
        // SQL
        var id = Integer.parseInt(name.split("-")[2]);
        var edgeNodeConfig = edgeNodeService.getOne(new QueryWrapper<EdgeNode>().eq("edge_node_id", id));
        EdgeNode edgeNode = new EdgeNode();
        edgeNode.setEdgeNodeId(id);
        edgeNode.setCpuNum(edgeNodeConfig.getCpuNum());
        edgeNode.setExecutionFailureRate(edgeNodeConfig.getExecutionFailureRate());
        edgeNode.setTaskRate(edgeNodeConfig.getTaskRate());
        edgeNode.setCapacity(edgeNodeConfig.getCpuNum() * Constants.Giga.value * cpuCapacity);
        // RD-DRL
        edgeNode.setEdgeNodeReliability(edgeNodeConfig.getEdgeNodeReliability());
        edgeNodeSystem.setEdgeNode(edgeNode);
        edgeNodeSystem.setExecutionQueue(new ExecutionQueue());
        log.info("edge-node-{} edge config: {}", id, edgeNode);

        var transmissionQueueMap = new HashMap<Integer, TransmissionQueue>();
        var linkMap = new HashMap<Integer, Link>();
        List<Integer> neiborList = new ArrayList<>();
        // SQL
        var links = linkService.list(new QueryWrapper<Link>().eq("source", id));
        for (Link link : links) {
            transmissionQueueMap.put(link.getDestination(), new TransmissionQueue());
            linkMap.put(link.getDestination(), link);
            //Neighbor node
            if (!link.getTransmissionRate().equals(0.0)) {
                neiborList.add(link.getDestination());
            }
            
        }
        edgeNodeSystem.setTransmissionQueueMap(transmissionQueueMap);
        edgeNodeSystem.setLinkMap(linkMap);
        // availAction
        edgeNodeSystem.setExecutionQueueThreshold(Float.valueOf(edgeNode.getCpuNum()) / (float) minCpuCore * queueCoef);
        edgeNodeSystem.setNeiborList(neiborList);
        log.info("edge-node-{} link config: {}", id, links);
        log.info("load edge nodes and links configuration completed");
    }
}
