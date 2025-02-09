package cn.edu.scut.agent;


import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import cn.edu.scut.util.FileUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

@Component
@Lazy
@Slf4j
public class MultiAgentBuffer implements InitializingBean {


    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    private int observationShape;
    private int offloadingShape;
    private int actionShape;

    @Value("${rl.buffer-size}")
    private int bufferSize;

    @Value("${rl.batch-size}")
    private int batchSize;
    @Value("${env.max-task-redundancy}")
    private int maxTaskRedundancy;
    @Value("${hadoop.hdfs.url}")
    private String hdfsUrl;

    @Getter
    private float[][][] observations;
    @Getter
    private int[][][] actions;
    @Getter
    private float[][][] rewards;
    @Getter
    private int[][][] availActions;
    @Getter
    private float[][][] nextObservations;

    private int index = 0;

    private int size = 0;

    @Autowired
    private FileSystem fileSystem;

    @Autowired
    private Random bufferRandom;

    @Value("${rl.observation.add-reliability-requirement}")
    private boolean addReliabilityRequirement;

    @Value("${env.use-task-reliability}")
    private boolean useTaskReliability;


    @Override
    public void afterPropertiesSet() {
        offloadingShape = edgeNodeNumber + 1;
        actionShape = offloadingShape * maxTaskRedundancy;
        observationShape = edgeNodeNumber * 6 + 3;
        if (addReliabilityRequirement) {
            observationShape += 1;
        }
        if (useTaskReliability) {
            observationShape += 1;
        }
        observations = new float[bufferSize][edgeNodeNumber][observationShape];
        actions = new int[bufferSize][edgeNodeNumber][maxTaskRedundancy];   // active redundancy decision
        availActions = new int[bufferSize][edgeNodeNumber][actionShape];    // DRL schedule
        rewards = new float[bufferSize][edgeNodeNumber][1];
        nextObservations = new float[bufferSize][edgeNodeNumber][observationShape];
    }

    public void insert(float[][] observation, int[][] action, int[][] availAction, float[][] reward, float[][] nextObservation) {
        observations[index] = observation;
        actions[index] = action;
        availActions[index] = availAction;
        rewards[index] = reward;
        nextObservations[index] = nextObservation;
        index = (index + 1) % bufferSize;
        size = Math.min(size + 1, bufferSize);
    }

    // off-policy
    public NDList sample(NDManager manager) {
        var list = new ArrayList<Integer>();
        for (int i = 0; i < size; i++) {
            list.add(i);
        }
        Collections.shuffle(list, bufferRandom);
        var batch = new int[batchSize];
        for (int i = 0; i < batchSize; i++) {
            batch[i] = list.get(i);
        }

        var ndStates = manager.zeros(new Shape(batchSize, edgeNodeNumber, observationShape), DataType.FLOAT32);
        var ndActions = manager.zeros(new Shape(batchSize, edgeNodeNumber, maxTaskRedundancy), DataType.INT32);
        var ndAvailActions = manager.zeros(new Shape(batchSize, edgeNodeNumber, actionShape), DataType.INT32);
        var ndRewards = manager.zeros(new Shape(batchSize, edgeNodeNumber, 1), DataType.FLOAT32);
        var ndNextStates = manager.zeros(new Shape(batchSize, edgeNodeNumber, observationShape), DataType.FLOAT32);

        for (int i = 0; i < batchSize; i++) {
            var ndIndex = new NDIndex(i);
            ndStates.set(ndIndex, manager.create(observations[batch[i]]));
            ndActions.set(ndIndex, manager.create(actions[batch[i]]));
            ndAvailActions.set(ndIndex, manager.create(availActions[batch[i]]));
            ndRewards.set(ndIndex, manager.create(rewards[batch[i]]));
            ndNextStates.set(ndIndex, manager.create(nextObservations[batch[i]]));
        }
        return new NDList(ndStates, ndActions, ndAvailActions, ndRewards, ndNextStates);
    }

    // on-policy
    public NDList sampleAll(NDManager manager) {
        var ndObservations = manager.zeros(new Shape(bufferSize, edgeNodeNumber, observationShape), DataType.FLOAT32);
        var ndActions = manager.zeros(new Shape(bufferSize, edgeNodeNumber, maxTaskRedundancy), DataType.INT32);
        var ndAvailActions = manager.zeros(new Shape(bufferSize, edgeNodeNumber, actionShape), DataType.INT32);
        var ndRewards = manager.zeros(new Shape(bufferSize, edgeNodeNumber, 1), DataType.FLOAT32);
        var ndNextObservations = manager.zeros(new Shape(bufferSize, edgeNodeNumber, observationShape), DataType.FLOAT32);
        for (int i = 0; i < bufferSize; i++) {
            try {
                var ndIndex = new NDIndex(i);
                ndObservations.set(ndIndex, manager.create(observations[i]));
                ndActions.set(ndIndex, manager.create(actions[i]));
                ndAvailActions.set(ndIndex, manager.create(availActions[i]));
                ndRewards.set(ndIndex, manager.create(rewards[i]));
                ndNextObservations.set(ndIndex, manager.create(nextObservations[i]));
            } catch (Exception e) {
                log.error("Error in sampleAll loop at iteration {}: {}", i, e.getMessage(), e);
                throw e;
            }
        }
        return new NDList(ndObservations, ndActions, ndAvailActions, ndRewards, ndNextObservations);
    }

    public void load(String bufferPath){
        var path = Paths.get("results", "buffer", bufferPath);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        observations = (float[][][]) FileUtils.readObject(path.resolve("states.array"));
        actions = (int[][][]) FileUtils.readObject(path.resolve("actions.array"));
        availActions = (int[][][]) FileUtils.readObject(path.resolve("availActions.array"));
        rewards = (float[][][]) FileUtils.readObject(path.resolve("rewards.array"));
        nextObservations = (float[][][]) FileUtils.readObject(path.resolve("nextStates.array"));
        
        
        size = observations.length;
        
    }

    /**
     * Save buffer data to the filesystem.
     * 
     * @param flag ：An identifier to hold the data
     */
    public void save(String flag) {
        var path = Paths.get("results", "buffer", flag);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        FileUtils.writeObject(observations, path.resolve("states.array"));
        FileUtils.writeObject(actions, path.resolve("actions.array"));
        FileUtils.writeObject(availActions, path.resolve("availActions.array"));
        FileUtils.writeObject(rewards, path.resolve("rewards.array"));
        FileUtils.writeObject(nextObservations, path.resolve("nextStates.array"));
    }

    public void saveHdfs(String flag) {
        var path = Paths.get("results", "buffer", flag);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            FileUtils.writeObject(observations, path.resolve("states.array"));
            fileSystem.copyFromLocalFile(true, true, new Path(path.resolve("states.array").toString()), new Path(hdfsUrl + "/" + path.resolve("states.array")));

            FileUtils.writeObject(actions, path.resolve("actions.array"));
            fileSystem.copyFromLocalFile(true, true, new Path(path.resolve("actions.array").toString()), new Path(hdfsUrl + "/" + path.resolve("actions.array")));

            FileUtils.writeObject(availActions, path.resolve("availActions.array"));
            fileSystem.copyFromLocalFile(true, true, new Path(path.resolve("availActions.array").toString()), new Path(hdfsUrl + "/" + path.resolve("availActions.array")));

            FileUtils.writeObject(rewards, path.resolve("rewards.array"));
            fileSystem.copyFromLocalFile(true, true, new Path(path.resolve("rewards.array").toString()), new Path(hdfsUrl + "/" + path.resolve("rewards.array")));

            FileUtils.writeObject(nextObservations, path.resolve("nextStates.array"));
            fileSystem.copyFromLocalFile(true, true, new Path(path.resolve("nextStates.array").toString()), new Path(hdfsUrl + "/" + path.resolve("nextStates.array")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void loadHdfs(String bufferPath) {
        var path = Paths.get("results", "buffer", bufferPath);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            fileSystem.copyToLocalFile(false, new Path(hdfsUrl + "/" + path.resolve("states.array")), new Path(path.resolve("states.array").toString()), false);
            observations = (float[][][]) FileUtils.readObject(path.resolve("states.array"));

            fileSystem.copyToLocalFile(false, new Path(hdfsUrl + "/" + path.resolve("actions.array")), new Path(path.resolve("actions.array").toString()), false);
            actions = (int[][][]) FileUtils.readObject(path.resolve("actions.array"));

            fileSystem.copyToLocalFile(false, new Path(hdfsUrl + "/" + path.resolve("availActions.array")), new Path(path.resolve("availActions.array").toString()), false);
            availActions = (int[][][]) FileUtils.readObject(path.resolve("availActions.array"));

            fileSystem.copyToLocalFile(false, new Path(hdfsUrl + "/" + path.resolve("rewards.array")), new Path(path.resolve("rewards.array").toString()), false);
            rewards = (float[][][]) FileUtils.readObject(path.resolve("rewards.array"));

            fileSystem.copyToLocalFile(false, new Path(hdfsUrl + "/" + path.resolve("nextStates.array")), new Path(path.resolve("nextStates.array").toString()), false);
            nextObservations = (float[][][]) FileUtils.readObject(path.resolve("nextStates.array"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        index = bufferSize;
        size = bufferSize;
    }
}
