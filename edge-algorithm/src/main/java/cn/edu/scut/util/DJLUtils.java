package cn.edu.scut.util;

import ai.djl.MalformedModelException;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.nn.Block;
import ai.djl.nn.ParameterList;
import cn.edu.scut.bean.EdgeNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Lazy
@Component
public class DJLUtils {

    public static int sampleMultinomial(Random random, NDArray prob) {
        int value = 0;
        long size = prob.size();
        float rnd = random.nextFloat();
        int i;
        for (i = 0; i < size; i++) {
            float cut = prob.getFloat(value);
            if (rnd > cut) {
                value++;
            } else {
                return value;
            }
            rnd -= cut;
            
        }
        if(i == size){
            return i;
        }else{
            throw new IllegalArgumentException("Invalid multinomial distribution , prob=" + prob + ", rnd=" + rnd);
        }
        
    }

    public static int sampleMultinomialSecond(Random random, NDArray prob) {
        long size = prob.size();
        HashMap<Integer, Float> hashMap = new HashMap<>();
        int i;
        for (i = 0; i < size; ++i) {
            float value = prob.getFloat(i);
            if (value != -1e8f) {
                hashMap.put(i, value);
            }
        }
        List<Map.Entry<Integer, Float>> sortedList = new ArrayList<>(hashMap.entrySet());
        sortedList.sort(Map.Entry.comparingByValue());   // 升序  随机性更好   最高91.0+%
        log.info("======sortedList: {}", sortedList);

        // 获取值最大的前两个索引
        int[] topTwoIndices = new int[2];
        topTwoIndices[0] = sortedList.get(0).getKey();
        topTwoIndices[1] = sortedList.get(1).getKey();
        log.info("=====topTwoIndices: {}", topTwoIndices);
        int selectedIndex = random.nextInt(topTwoIndices.length);
        return topTwoIndices[selectedIndex];
    }

    public static int sampleMultinomialSecondReliability(Random random, NDArray prob) {
        long size = prob.size();
        HashMap<Integer, Float> hashMap = new HashMap<>();
        int i;
        for (i = 0; i < size; ++i) {
            float value = prob.getFloat(i);
            if (value != -1e8f) {
                hashMap.put(i, value);
            }
        }
        List<Map.Entry<Integer, Float>> sortedList = new ArrayList<>(hashMap.entrySet());
        sortedList.sort(Map.Entry.comparingByValue());   // 升序  随机性更好   最高91.0+%
        log.info("======sortedList: {}", sortedList);

        // 获取值最大的前两个索引
        int[] topTwoIndices = new int[2];
        topTwoIndices[0] = sortedList.get(0).getKey();
        topTwoIndices[1] = sortedList.get(1).getKey();
        log.info("=====topTwoIndices: {}", topTwoIndices);
        // 随机选择topTwoIndices中的一个值
        int selectedIndex = random.nextInt(topTwoIndices.length);
        return topTwoIndices[selectedIndex];
    }

    public static int sampleMultinomialSecondExecutionQueue(Random random, NDArray prob) {
        RestTemplate restTemplate = new RestTemplate();
        long size = prob.size();
        HashMap<Integer, Float> hashMap = new HashMap<>();
        int i;
        for (i = 0; i < size; ++i) {
            float value = prob.getFloat(i);
            if (value != -1e8f) {
                hashMap.put(i, value);
            }
        }
        List<Map.Entry<Integer, Float>> sortedList = new ArrayList<>(hashMap.entrySet());
        sortedList.sort(Map.Entry.comparingByValue());   // 升序
        log.info("======sortedList: {}", sortedList);

        var selectedNodes = new HashSet<Integer>();
        selectedNodes.add(sortedList.get(0).getKey() + 1);
        selectedNodes.add(sortedList.get(1).getKey() + 1);

        var queue = new PriorityQueue<>(Comparator.comparingInt((Map<String, Object> o) -> (int) o.get("queue")));
        for (Integer edgeNodeId : selectedNodes) {
             var info = new HashMap<String, Object>();
            var queueSize = restTemplate.getForObject("http://edge-node-"+edgeNodeId+"/edgeNode/queue", Integer.class);
             log.info("========queueSize: {}", queueSize);
             info.put("queue", queueSize);
             info.put("edgeId", edgeNodeId);
             queue.add(info);

         }
        return (Integer) queue.poll().get("edgeId");
    }

    public static int sampleMultinomialThird(Random random, NDArray prob) {
        long size = prob.size();
        HashMap<Integer, Float> hashMap = new HashMap<>();
        int i;
        for (i = 0; i < size; ++i) {
            float value = prob.getFloat(i);
            if (value != -1e8f) {
                hashMap.put(i, value);
            }
        }
        List<Map.Entry<Integer, Float>> sortedList = new ArrayList<>(hashMap.entrySet());
        sortedList.sort(Map.Entry.comparingByValue());   // 升序
        log.info("======sortedList: {}", sortedList);

        // 将所有的key存放在topTwoIndices数组中
        int[] topIndices = new int[sortedList.size()];
        for (i = 0; i < sortedList.size(); i++) {
            topIndices[i] = sortedList.get(i).getKey();
        }

        // 随机选择topIndices中的一个值
        int selectedIndex = random.nextInt(topIndices.length);
        return topIndices[selectedIndex];
    }

    public static void softUpdate(Block network, Block targetNetwork, float tau) {
        var parameters = network.getParameters();
        var targetParameters = targetNetwork.getParameters();
        for (String key : parameters.keys()) {
            var array = parameters.get(key).getArray();
            var targetArray = targetParameters.get(key).getArray();
            var mixArray = targetArray.mul(1.0f - tau).add(array.mul(tau));
            targetParameters.get(key).getArray().set(mixArray.toFloatArray());
        }
    }

    public static void hardUpdate(Block network, Block targetNetwork) {
        var parameters = network.getParameters();
        var targetParameters = targetNetwork.getParameters();
        for (String key : parameters.keys()) {
            targetParameters.get(key).getArray().set(parameters.get(key).getArray().toFloatArray());
        }
    }


    /**
     * Save the file to the specified path
     * @param path The path where the model will be saved.
     * @param actorBlock The actor block representing the model.
     * @throws RuntimeException if an error occurs while saving the model.
     */
    public static void saveModel(Path path, Block actorBlock) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (var fileOutputStream = new FileOutputStream(path.toFile());
             var dataOutputStream = new DataOutputStream(fileOutputStream)) {
            actorBlock.saveParameters(dataOutputStream);
        } catch (IOException e) {
            log.error("save model error");
            throw new RuntimeException(e);
        }
    }

    public static void loadModel(Path path, Block actorBlock, NDManager manager) {
        try (var fileInputStream = new FileInputStream(path.toFile());
             var dataInputStream = new DataInputStream(fileInputStream)) {
            actorBlock.loadParameters(manager, dataInputStream);
            ParameterList parameters = actorBlock.getParameters();
            List<String> keys = parameters.keys();
            for (String key : keys) {
                var data = parameters.get(key).getArray();
                data.setRequiresGradient(true);
            }
        } catch (IOException e) {
            log.error("read file error");
            throw new RuntimeException(e);
        } catch (MalformedModelException e) {
            log.error("load model error");
            throw new RuntimeException(e);
        }
    }

    public static void loadStreamModel(InputStream inputStream, Block actorBlock, NDManager manager) {
        try (var dataInputStream = new DataInputStream(inputStream)) {
            log.info("load stream model.");
            actorBlock.loadParameters(manager, dataInputStream);
            ParameterList parameters = actorBlock.getParameters();
            List<String> keys = parameters.keys();
            for (String key : keys) {
                var data = parameters.get(key).getArray();
                data.setRequiresGradient(true);
            }
        } catch (IOException e) {
            log.error("IOException");
            throw new RuntimeException(e);
        } catch (MalformedModelException e) {
            log.error("MalformedModelException");
            throw new RuntimeException(e);
        }
    }

    // edge computing, truncate episode, no done
    public static NDArray getReturn(NDArray rewards, NDArray nextStateValues, float gamma, int episodeLimit) {
        var res = rewards.zerosLike();
        for (long i = episodeLimit - 1; i >= 0; i--) {
            NDArray nextReturn;
            if (i == episodeLimit - 1) {
                nextReturn = nextStateValues.get(episodeLimit - 1);
            } else {
                nextReturn = res.get(i + 1);
            }
            var currentReturn = rewards.get(i).add(nextReturn.mul(gamma));
            res.set(new NDIndex(i), currentReturn);
        }
        return res;
    }

    public static NDArray getReturn(NDArray rewards, float gamma, int episodeLimit) {
        var res = rewards.zerosLike();
        for (long i = episodeLimit - 1; i >= 0; i--) {
            NDArray nextReturn;
            if (i == episodeLimit - 1) {
                nextReturn = rewards.get(i);
            } else {
                nextReturn = res.get(i + 1);
            }
            var currentReturn = rewards.get(i).add(nextReturn.mul(gamma));
            res.set(new NDIndex(i), currentReturn);
        }
        return res;
    }


    public static NDArray getGae(NDArray rewards, NDArray stateValues, NDArray nextStateValues, NDManager manager, float gamma, int episodeLimit, float gaeLambda) {
        var advantages = rewards.zerosLike();
        var deltas = rewards.add(nextStateValues.mul(gamma)).sub(stateValues);
        var nextAdvantage = manager.create(0.0f);
        try {
            for (long i = episodeLimit - 1; i >= 0; i--) {
                var advantage = deltas.get(i).add(nextAdvantage.mul(gamma).mul(gaeLambda));
                advantages.set(new NDIndex(i), advantage);
                nextAdvantage = advantage;
            }
        } catch (Exception e) {
            log.error("error is {}", e.getMessage());
            log.error("advantages's shape is {}", advantages.getShape());
            log.error("deltas's shape is {}", deltas.getShape());
            log.error("nextAdvantage's shape is {}", nextAdvantage.getShape());
        }
        
        return advantages;
    }

    public static NDList loadNDArrayFromFile(String filePath, NDManager manager) {
        try (var fileInputStream = new FileInputStream(filePath);
             var dataInputStream = new DataInputStream(fileInputStream)) {
            
            int statesDim = dataInputStream.readInt();
            int actionsDim = dataInputStream.readInt();
            
            float[] statesData = new float[statesDim];
            for (int i = 0; i < statesDim; i++) {
                statesData[i] = dataInputStream.readFloat();
            }
            
            float[] actionsData = new float[actionsDim];
            for (int i = 0; i < actionsDim; i++) {
                actionsData[i] = dataInputStream.readFloat();
            }
            
            NDArray states = manager.create(statesData);
            NDArray actions = manager.create(actionsData);
            
            return new NDList(states, actions);
        } catch (IOException e) {
            log.error("Failed to load NDArray from file: {}", filePath);
            throw new RuntimeException("Failed to load NDArray from file", e);
        }
    }
}
