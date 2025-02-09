package cn.edu.scut.scheduler;

import cn.edu.scut.bean.EdgeNodeSystem;
import cn.edu.scut.bean.Task;
import cn.edu.scut.config.Config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
@Lazy
@Component
public class ReactiveScheduler implements IScheduler {

    @Value("${spring.application.name}")
    public String name;

    @Autowired
    private EdgeNodeSystem edgeNodeSystem;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Value("${heuristic.queue-coef}")
    private float queueCoef;

    @Autowired
    private Random schedulerRandom;

    @Autowired
    private Config config;


    @Override
    public int[] selectAction(Task task) {
        var reachableNodeSet = edgeNodeSystem.getNeiborList();
        var edgeNodeIds = new ArrayList<Integer>();
        for(int i=1;i<=reachableNodeSet.size();i++){
            edgeNodeIds.add(reachableNodeSet.get(i-1));
        }
        var selectedNodes = new HashSet<Integer>();
        // while true
        for (int i = 0; i < 1000; i++) {
            selectedNodes.add(edgeNodeIds.get(schedulerRandom.nextInt(edgeNodeIds.size())));
            if (selectedNodes.size() == 2) {
                break;
            }
        }

        var queue = new PriorityQueue<>(Comparator.comparingInt((Map<String, Object> o) -> (int) o.get("waitingTime")));
        for (Integer edgeNodeId : selectedNodes) {
            var info = new HashMap<String, Object>();
            var url = String.format("http://edge-node-%s/edgeNode/waitingTime", edgeNodeId);
            var queueSize = restTemplate.getForObject(url, Integer.class);
            info.put("waitingTime", queueSize);
            info.put("edgeId", edgeNodeId);
            queue.add(info);
        }
        return new int[]{(Integer) queue.poll().get("edgeId")};
    }

}