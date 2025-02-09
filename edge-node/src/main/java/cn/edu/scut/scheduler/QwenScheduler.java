package cn.edu.scut.scheduler;

import cn.edu.scut.agent.qwen.QwenAgent;
import cn.edu.scut.bean.Task;
import cn.edu.scut.service.TransitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class QwenScheduler implements IScheduler {

    // State vector
    private static final int STATE_FEATURES_PER_NODE = 5;
    
    @Autowired
    private QwenAgent qwenAgent;

    @Autowired
    private TransitionService transitionService;

    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Value("${network.max-bandwidth}")
    private float maxBandwidth = 1000.0f;

    @Override
    public int[] selectAction(Task task) {
        if (task == null) {
            return generateDefaultAction();
        }

        try {
            float[] state = transitionService.getObservation_(
                task.getSource(), 
                task.getTimeSlot()
            );
            
            String prompt = buildDecisionPrompt(task, state);
            String response = qwenAgent.chat(prompt);
            
            return processModelResponse(response, task);
            
        } catch (Exception e) {
            log.error("Qwen exception: {}", e.getMessage());
            return generateFallbackAction();
        }
    }

    private String buildDecisionPrompt(Task task, float[] state) {
        return String.format("""
            Edge Computing Scheduling Request (Node %d):
            
            [Current Task]
            - Data Size: %.2f MB
            - CPU Requirement: %.2f GHz
            - Deadline: %d ms
            
            [Node Resources]
            %s
            
            [Network Conditions]
            %s
            
            Select the optimal node ID (0-%d):
            """,
            task.getSource(),
            task.getTaskSize() / 1e6f,
            task.getCpuCycle() / 1e9f,
            task.getDeadline(),
            formatNodeResources(state),
            formatNetworkConditions(state),
            edgeNodeNumber - 1
        );
    }

    private String formatNodeResources(float[] state) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < edgeNodeNumber; i++) {
            int offset = i * STATE_FEATURES_PER_NODE;
            float cpuUtil = state[offset + 1] * 100;
            float failureRate = (1 - state[offset + 4]) * 100;
            sb.append(String.format("- Node %d: CPU %.1f%% | Failure: %.1f%%%n", 
                i, cpuUtil, failureRate));
        }
        return sb.toString();
    }

    private String formatNetworkConditions(float[] state) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < edgeNodeNumber; i++) {
            int offset = i * STATE_FEATURES_PER_NODE;
            float bandwidth = state[offset + 3] * maxBandwidth;
            sb.append(String.format("- To Node %d: %.1f Mbps%n", i, bandwidth));
        }
        return sb.toString();
    }

    private int[] processModelResponse(String response, Task task) {
        Set<Integer> actions = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("\\b\\d+\\b");
        Matcher matcher = pattern.matcher(response);
        
        while (matcher.find() && actions.size() < 2) {
            try {
                int nodeId = Integer.parseInt(matcher.group());
                if (isValidNode(nodeId)) {
                    actions.add(nodeId);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid node ID: {}", matcher.group());
            }
        }
        
        return finalizeSelection(actions);
    }

    private boolean isValidNode(int nodeId) {
        return nodeId >= 0 && nodeId < edgeNodeNumber;
    }

    private int[] finalizeSelection(Set<Integer> candidates) {
        if (candidates.isEmpty()) {
            return new int[]{findFallbackNode()};
        }
        return new int[]{candidates.iterator().next()};
    }

    private int[] generateDefaultAction() {
        return new int[]{edgeNodeNumber};
    }

    private int[] generateFallbackAction() {
        return new int[]{findFallbackNode()};
    }

    private int findFallbackNode() {
        try {
            float[] state = transitionService.getObservation_(0, 0);
            Map<Integer, Float> nodeScores = new HashMap<>();
            
            for (int i = 0; i < edgeNodeNumber; i++) {
                int offset = i * STATE_FEATURES_PER_NODE;
                float cpuUtil = state[offset + 1];
                float bandwidth = state[offset + 3];
                
                float score = (1 - cpuUtil) * 0.6f + bandwidth * 0.4f;
                nodeScores.put(i, score);
            }
            
            return nodeScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
            
        } catch (Exception e) {
            log.error("Fallback node selection failed: {}", e.getMessage());
            return 0;
        }
    }
} 
