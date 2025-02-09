package cn.edu.scut.agent.qwen;

import cn.edu.scut.agent.MultiAgentAdaptor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;


@Slf4j
@Component
public class QwenAgent extends MultiAgentAdaptor {

    @Value("${qwen.api-url}")
    private String apiUrl;

    @Value("${qwen.api-key}")
    private String apiKey;

    @Value("${qwen.model-name}")
    private String modelName;

    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Autowired
    private OkHttpClient httpClient;

    public String chat(String prompt) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", modelName);
        
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);
        
        requestBody.add("messages", messages);
        requestBody.addProperty("temperature", 0.99);
        requestBody.addProperty("top_p", 0.9);

        Gson gson = new Gson();
        var request = new Request.Builder()
            .url(apiUrl)
            .post(RequestBody.create(gson.toJson(requestBody), MediaType.parse("application/json")))
            .addHeader("Authorization", "Bearer " + apiKey)
            .build();

        try (var response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("API request error: {}", response.code());
                return "";
            }
            JsonObject jsonResponse = gson.fromJson(response.body().charStream(), JsonObject.class);
            return jsonResponse.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
        } catch (IOException e) {
            log.error("API exception: {}", e.getMessage());
            return "";
        }
    }

    public int selectAction(float[] state, List<Integer> availableActions) {
        var prompt = buildDecisionPrompt(state, availableActions);
        var response = chat(prompt);
        return parseActionFromResponse(response, availableActions);
    }

    private String buildDecisionPrompt(float[] state, List<Integer> availableActions) {
        int nodeId = (int)state[0];
        float taskSize = state[1];
        float cpuRequirement = state[2];
        
        float[] cpuCapacities = Arrays.copyOfRange(state, 3, 3+edgeNodeNumber);
        float[] cpuUtils = Arrays.copyOfRange(state, 3+edgeNodeNumber, 3+2*edgeNodeNumber);
        float[] failureRates = Arrays.copyOfRange(state, 3+2*edgeNodeNumber, 3+3*edgeNodeNumber);
        float[] waitTimes = Arrays.copyOfRange(state, 3+3*edgeNodeNumber, 3+4*edgeNodeNumber);
        
        float[] bandwidths = Arrays.copyOfRange(state, 3+4*edgeNodeNumber, 3+4*edgeNodeNumber+edgeNodeNumber);

        return String.format("""
            As an AI controller in edge computing environment, analyze following parameters to make optimal offloading decision:
            
            [Current Task]
            - Node ID: %d
            - Task size: %.2f MB
            - CPU requirement: %.2f cycles
            
            [Node Resources]
            %s
            
            [Network Conditions]
            %s
            
            [Available Actions]
            %s
            
            Decision Guidelines:
            1. Prefer nodes with higher bandwidth when task size is large
            2. Avoid nodes with CPU utilization over 80%%
            3. Consider execution failure rate for reliability
            4. Balance load across all available nodes
            5. Select action %d only when no suitable edge nodes
            
            Respond ONLY with the action index (0-%d). No explanations.
            """,
            nodeId, taskSize, cpuRequirement,
            formatNodeResources(cpuCapacities, cpuUtils, failureRates, waitTimes),
            formatNetworkConditions(bandwidths),
            availableActions,
            edgeNodeNumber,
            edgeNodeNumber
        );
    }

    private String formatNodeResources(float[] capacities, float[] utils, float[] failures, float[] waits) {
        var sb = new StringBuilder();
        for (int i = 0; i < edgeNodeNumber; i++) {
            sb.append(String.format("- Node %d: CPU %.1f/%.1f GHz | Fail: %.2f%% | Wait: %.1fms%n",
                    i, utils[i], capacities[i], failures[i]*100, waits[i]));
        }
        return sb.toString();
    }

    private String formatNetworkConditions(float[] bandwidths) {
        var sb = new StringBuilder();
        for (int i = 0; i < edgeNodeNumber; i++) {
            sb.append(String.format("- To Node %d: %.1f Mbps%n", i, bandwidths[i]));
        }
        return sb.toString();
    }

    private int parseActionFromResponse(String response, List<Integer> availableActions) {
        try {
            int action = Integer.parseInt(response.trim());
            if (action < 0 || action > edgeNodeNumber) {
                throw new NumberFormatException("out of scope");
            }
            return availableActions.contains(action) ? 
                action : availableActions.get(0);
        } catch (NumberFormatException e) {
            log.warn("error: {}", response);
            return availableActions.get(0);
        }
    }

    private String arrayToString(float[] array) {
        var sb = new StringBuilder("[");
        for (float v : array) {
            sb.append(String.format("%.2f, ", v));
        }
        return sb.substring(0, sb.length() - 2) + "]";
    }
} 