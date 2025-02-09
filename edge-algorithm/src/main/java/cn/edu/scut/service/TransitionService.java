package cn.edu.scut.service;

import cn.edu.scut.bean.*;
import cn.edu.scut.config.Config;
import cn.edu.scut.util.ArrayUtils;
import cn.edu.scut.util.EnvUtils;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@Data
@Slf4j
public class TransitionService implements InitializingBean {
    private List<List<Integer>> result;

    @Value("${env.max-task-rate}")
    private double maxTaskRate;

    @Value("${env.max-cpu-core}")
    private int maxCpuCore;

    @Value("${env.max-task-size}")
    private long maxTaskSize;
    @Value("${env.max-task-complexity}")
    private long maxTaskComplexity;

    @Value("${env.max-transmission-rate}")
    private double maxTransmissionRate;

    @Value("${env.max-execution-failure-rate}")
    private double maxExecutionFailureRate;

    @Value("${env.edge-node-number}")
    private int agentNumber;

    @Value("${env.max-task-redundancy}")
    private int maxTaskRedundancy;

    @Value("${env.task-redundancy}")
    private int taskRedundancy;
    

    @Value("${env.use-redundancy}")
    private boolean useRedundancy;

    @Autowired
    private EdgeNodeService edgeNodeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private LinkService linkService;

    @Value("${env.use-poisson-reliability}")
    private boolean usePoissonReliability;

    @Value("${env.use-constant-reliability}")
    private boolean useConstantReliability;

    @Value("${env.min-transmission-reliability}")
    private float minTransmissionReliability;
    @Value("${env.max-transmission-reliability}")
    private float maxTransmissionReliability;

    @Value("${env.min-transmission-failure-rate}")
    private float minTransmissionFailureRate;

    @Value("${env.max-transmission-failure-rate}")
    private float maxTransmissionFailureRate;

    @Value("${env.max-execution-reliability}")
    private float maxExecutionReliability;

    private int offloadingShape;
    private int actionShape;

    @Value("${rl.add-local-reward:false}")
    private boolean addLocalReward;

    @Value("${rl.observation.add-reliability-requirement}")
    private boolean addReliabilityRequirement;

    @Value("${env.max-task-reliability-requirement}")
    private float maxTaskReliabilityRequirement;


    @Value("${env.use-task-reliability}")
    private boolean useTaskReliability;

    @Value("${env.max-task-reliability}")
    private float maxTaskReliability;

    @Value("${rl.local-reward:0.0}")
    private float localReward;

    @Value("${env.queue-coef:3.0}")
    private float queueCoef;

    @Value("${env.min-cpu-core:4}")
    private int minCpuCore;

    @Autowired
    private EnvUtils envUtils;
    @Autowired
    private Config config;
    @Autowired
    private RestTemplate restTemplate;

    @Override
    public void afterPropertiesSet() throws Exception {
        offloadingShape = agentNumber + 1;
        actionShape = offloadingShape * maxTaskRedundancy;
    }

    // execution and training
    public float[] getObservation(int source, int timeSlot) {
        var obsList = new ArrayList<Float>();
        addOneHot(obsList, source);
        addTaskInfo(obsList, source, timeSlot);
        addLinkInfo(obsList, source);
        addEdgeInfo(obsList);
        var node = edgeNodeService.getOne(new QueryWrapper<EdgeNode>().eq("edge_node_id", source).last("limit 1"));
        var exec_queue_threshold = Float.valueOf(node.getCpuNum()) / (float) minCpuCore *queueCoef;
        obsList.add(result.get(source-1).get(timeSlot-1).floatValue() / exec_queue_threshold);
        return ArrayUtils.toFloatArray(obsList);
    }

    public float[] getObservation_(int source, int timeSlot) {
        var obsList = new ArrayList<Float>();
        addOneHot(obsList, source);
        addTaskInfo(obsList, source, timeSlot);
        addLinkInfo(obsList, source);
        addEdgeInfo(obsList);
        var node = edgeNodeService.getOne(new QueryWrapper<EdgeNode>().eq("edge_node_id", source).last("limit 1"));
        var exec_queue_threshold = Float.valueOf(node.getCpuNum()) / (float) minCpuCore *queueCoef;
        var waitingQueueSize = restTemplate.getForObject("http://edge-node-"+source+"/edgeNode/queue", Integer.class);
        obsList.add(waitingQueueSize.floatValue() / exec_queue_threshold);
        return ArrayUtils.toFloatArray(obsList);
    }

    // 1N edgeNodeNumber*1
    public void addOneHot(ArrayList<Float> obs, int source) {
        // one-hot vector N
        for (int j = 0; j < agentNumber; j++) {
            if (j + 1 == source) {
                obs.add(1.0f);
            } else {
                obs.add(0.0f);
            }
        }
    }

    // 2
    // Task size, complexity, reliability requirement, reliability
    public void addTaskInfo(ArrayList<Float> obs, int source, int timeSlot) {
        var task = taskService.getOne(new QueryWrapper<Task>().eq("source", source).eq("time_slot", timeSlot).last("limit 1"));
        // task dynamic 2
        if (task != null) {
            obs.add(Float.valueOf(task.getTaskSize()) / (float) (maxTaskSize * StoreConstants.Byte.value * StoreConstants.Kilo.value));
            obs.add(task.getTaskComplexity() / (float) maxTaskComplexity);
            if (addReliabilityRequirement) {
                obs.add(task.getReliabilityRequirement().floatValue() / maxTaskReliabilityRequirement);
            }
            if (useTaskReliability) {
                obs.add(task.getTaskReliability().floatValue() / maxTaskReliability);
            }
        } else {
            obs.add(0.0f);
            obs.add(0.0f);
            if (addReliabilityRequirement) {
                obs.add(0.0f);
            }
            if (useTaskReliability) {
                obs.add(0.0f);
            }
        }
    }

    // 2N edgeNodeNumber * 2
    public void addLinkInfo(ArrayList<Float> obs, int source) {
        var linkInfos = linkService.list(new QueryWrapper<Link>().eq("source", source));
        for (Link link : linkInfos) {
            obs.add((float) (link.getTransmissionRate() / (maxTransmissionRate * Constants.Mega.value * Constants.Byte.value)));
            if (usePoissonReliability) {
                obs.add(link.getTransmissionFailureRate().floatValue() - minTransmissionFailureRate / (maxTransmissionFailureRate - minTransmissionFailureRate));
            } else if (useConstantReliability) {
                obs.add((link.getLinkReliability().floatValue() - minTransmissionReliability) / (maxTransmissionReliability - minTransmissionReliability));
            } else {
                throw new RuntimeException(" add link info error!");
            }
        }
    }

    // 3N edgeNodeNumber * 3
    public void addEdgeInfo(ArrayList<Float> obs) {
        var edgeNodeInfos = edgeNodeService.list();
        // edge node static 3N
        for (EdgeNode edgeNode : edgeNodeInfos) {
            obs.add((float) (edgeNode.getCpuNum() / maxCpuCore));
            obs.add((float) (edgeNode.getTaskRate() / maxTaskRate));
            if (usePoissonReliability) {
                obs.add((float) (edgeNode.getExecutionFailureRate() / maxExecutionFailureRate));
            } else if (useConstantReliability) {
                obs.add((float) (edgeNode.getEdgeNodeReliability() / maxExecutionReliability));
            } else {
                throw new RuntimeException("error in add edge info");
            }
        }
    }
    
    public int[] getAction(int source, int timeSlot) {
        var task = taskService.getOne(new QueryWrapper<Task>().eq("source", source).eq("time_slot", timeSlot).last("limit 1"));
        if (task == null) {
            int[] action = new int[maxTaskRedundancy];
            // index
            Arrays.fill(action, offloadingShape - 1);
            return action;
        }
        var actionList = JSONArray.parseArray(task.getAction(), Integer.class);
        // index
        return actionList.stream().mapToInt(x -> x - 1).toArray();
    }

    /**
     * The corresponding task is found according to the source node and time slot, and the available action array is obtained.
     * @param source
     * @param timeSlot
     * @return available action
     */
    public int[] getAvailAction(int source, int timeSlot) {
        // var task = taskService.getOne(new QueryWrapper<Task>().eq("source", source).eq("time_slot", timeSlot).last("limit 1"));
        // if (task == null) {
        //     return envUtils.getNonTaskRedundantAvailAction();
        // }
        // var data = JSONArray.parseArray(task.getAvailAction(), Integer.class);
        // return data.stream().mapToInt(x -> x).toArray();

        var availAction = envUtils.getRedundantAvailAction();
        var links = linkService.list(new QueryWrapper<Link>().eq("source", source));
        for (Link link : links) {
            //非邻居节点
            if (link.getTransmissionRate().equals(0.0)) {
                envUtils.maskEdgeNode(link.getDestination(), availAction);
            }
        }
        return availAction;
    }

    public float getReward(int source, int timeSlot) {
        float reward = 0.0f;
        var tasks = taskService.list(new QueryWrapper<Task>().eq("source", source).eq("time_slot", timeSlot));
        for (Task task : tasks) {
            if (task.getStatus().equals(TaskStatus.SUCCESS)) {
                reward += 1.0f;
            } else if (task.getStatus().equals(TaskStatus.EXECUTION_FAILURE) || task.getStatus().equals(TaskStatus.TRANSMISSION_FAILURE) || task.getStatus().equals(TaskStatus.DROP)) {
                reward -= 1.0f;
                // redundancy
            } else if (task.getStatus().equals(TaskStatus.CANCEL)) {
                // do nothing
                reward += 0.0f;
            } else {
                throw new RuntimeException("task status error");
            }
            // redundancy
            if (addLocalReward) {
                // 鼓励本地计算
                //将task的runtimeInfo转换为int
                Integer originalSource = Integer.parseInt(task.getRuntimeInfo());
                if (originalSource.equals(task.getDestination())) {
                    reward += localReward;
                }
            }
        }
        return reward;
    }

    public static boolean isNull(Object obj){
        if(obj==null){
            return true;
        }
        if("".equals(obj)){
            return true;
        }
        return false;
    }


    public float[] getCurObservation(int node, Task task) {
        var obsList = new ArrayList<Float>();
        addOneHot(obsList, node);
        addTaskInfo_(obsList, task);
        addLinkInfo(obsList, node);
        addEdgeInfo(obsList);
        return ArrayUtils.toFloatArray(obsList);
    }

    public void addTaskInfo_(ArrayList<Float> obs, Task task) {
        if (task != null) {
            obs.add(Float.valueOf(task.getTaskSize()) / (float) (maxTaskSize * StoreConstants.Byte.value * StoreConstants.Kilo.value));
            obs.add(task.getTaskComplexity() / (float) maxTaskComplexity);
            if (addReliabilityRequirement) {
                obs.add(task.getReliabilityRequirement().floatValue() / maxTaskReliabilityRequirement);
            }
            if (useTaskReliability) {
                obs.add(task.getTaskReliability().floatValue() / maxTaskReliability);
            }
        } else {
            obs.add(0.0f);
            obs.add(0.0f);
            if (addReliabilityRequirement) {
                obs.add(0.0f);
            }
            if (useTaskReliability) {
                obs.add(0.0f);
            }
        }
    }

    public float[] getNormalizedState(int nodeId, Task task) {
        float[] rawState = getCurObservation(nodeId, task);
        
        float[] normalized = new float[rawState.length];
        for (int i = 0; i < rawState.length; i++) {
            normalized[i] = normalizeValue(rawState[i], i);
        }
        return normalized;
    }

    private float normalizeValue(float value, int index) {
        if (index % 5 == 1) {
            return value / 100f;
        }
        if (index % 5 == 2) {
            return Math.min(value / 10f, 1.0f);
        }
        return value;
    }

}
