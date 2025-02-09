package cn.edu.scut.agent.dgail;

import ai.djl.MalformedModelException;
import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.TrainingConfig;
import ai.djl.translate.NoopTranslator;
import ai.djl.translate.TranslateException;
import cn.edu.scut.agent.MultiAgentBuffer;
import cn.edu.scut.util.DJLUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import cn.edu.scut.agent.milp.MILPAgent;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


@Component
@Slf4j
@ConditionalOnProperty(name = "rl.name", havingValue = "dgail")
public class DGAILAgent extends MILPAgent implements InitializingBean {
    @Autowired
    private Random schedulerRandom;

    @Autowired
    private NDManager manager;

    @Autowired
    private MultiAgentBuffer buffer;

    @Value("${env.max-task-redundancy}")
    private int maxTaskRedundancy;

    @Autowired
    private Model actorModel;

    @Autowired
    private Model criticModel;

    private final Lock lock = new ReentrantLock();

    private int offloadingShape;

    // env
    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Autowired
    private Model diffusionModel;
    
    @Value("${dgail.diffusion-steps}")
    private int diffusionSteps;
    
    @Value("${dgail.beta-start}")
    private float betaStart;
    
    @Value("${dgail.beta-end}")
    private float betaEnd;
    
    @Value("${dgail.sample-diversity-weight}")
    private float sampleDiversityWeight;

    @Value("${rl.observation.add-reliability-requirement}")
    private boolean addReliabilityRequirement;
    
    @Value("${env.use-task-reliability}")
    private boolean useTaskReliability;

    private int observationShape;
    private int actionShape;

    @Autowired
    private TrainingConfig diffusionTrainingConfig;

    @Override
    public void afterPropertiesSet() throws Exception {
        super.afterPropertiesSet();
        
        offloadingShape = edgeNodeNumber + 1;
        actionShape = maxTaskRedundancy * offloadingShape;
        observationShape = edgeNodeNumber * 6 + 3;
        
        if (addReliabilityRequirement) {
            observationShape += 1;
        }
        if (useTaskReliability) {
            observationShape += 1;
        }
    }

    @Override
    public int[] selectAction(float[] state, int[] availAction, boolean training, int taskId) {
        return selectSingleAction(state, availAction, training, taskId);
    }

    private int[] selectSingleAction(float[] state, int[] availAction, boolean training, int taskId) {
        int action;
        try {
            lock.lock();
            var subManager = manager.newSubManager();
            try (subManager) {
                var predictor = actorModel.newPredictor(new NoopTranslator());
                try (predictor) {
                    NDArray out = null;
                    try {
                        out = predictor.predict(new NDList(subManager.create(state))).singletonOrThrow();
                    } catch (TranslateException e) {
                        log.error("predict error: {}", e.getMessage());
                    }
                    var bool = subManager.create(availAction).eq(0);
                    assert out != null;
                    out.set(bool, -1e8f);    // 0 is unreachable

                    action = DJLUtils.sampleMultinomialSecond(schedulerRandom, out) + 1;
                    log.info("=======action: {}", action);
                }
            }
        } finally {
            lock.unlock();
        }
        return new int[]{action};
    }

    public void train() {
        try {
            lock.lock();
            var subManager = manager.newSubManager();
            try (subManager) {
                var list = buffer.sampleAll(subManager);
                var states = list.get(0);
                var actions = list.get(1);
                var nextStates = list.get(4);
                
                var rewards = calculateCombinedReward(states, actions);
                
                trainDiffusionModel(states, actions);
                
                trainPolicyAndValue(states, actions, rewards, nextStates);
            }
        } catch (TranslateException e) {
            log.error("Training error: ", e);
        } finally {
            lock.unlock();
        }
    }

    private NDList generateDiffusionSamples(int batchSize) throws TranslateException {
        var subManager = manager.newSubManager();
        try (subManager) {
            var noise = subManager.randomNormal(
                new Shape(batchSize, observationShape + actionShape));
            
            var sample = noise;
            for (int t = diffusionSteps - 1; t >= 0; t--) {
                float alpha = getBeta(t);
                var timestep = subManager.create(new float[]{t / (float)diffusionSteps});
                var input = NDArrays.concat(new NDList(sample, timestep), 1);
                
                var predictor = diffusionModel.newPredictor(new NoopTranslator());
                try (predictor) {
                    var predictedNoise = predictor.predict(
                        new NDList(input)).singletonOrThrow();
                    
                    sample = denoisingStep(sample, predictedNoise, alpha);
                }
            }
            
            return new NDList(
                sample.get(":{}", observationShape),
                sample.get("{}:", actionShape)
            );
        }
    }
    

    private NDArray denoisingStep(NDArray sample, NDArray predictedNoise, float alpha) {
        float sqrtAlpha = (float) Math.sqrt(alpha);
        float sqrtOneMinusAlpha = (float) Math.sqrt(1 - alpha);
        return sample.mul(sqrtAlpha).sub(predictedNoise.mul(sqrtOneMinusAlpha));
    }
    

    private float getBeta(int timestep) {
        float t = timestep / (float)(diffusionSteps - 1);
        return betaStart + (betaEnd - betaStart) * t;
    }

    private NDArray calculateCombinedReward(NDArray states, NDArray actions) 
            throws TranslateException {
        var gailReward = super.getDiscriminatorRewards(states, actions);
        
        var diffusionSamples = generateDiffusionSamples((int) states.getShape().get(0));
        
        var similarityReward = calculateSimilarityReward(
            states, actions, diffusionSamples.get(0), diffusionSamples.get(1));
        
        var diversityReward = calculateDiversityReward(actions);
        
        return gailReward
            .add(similarityReward)
            .add(diversityReward.mul(sampleDiversityWeight));
    }
    
    private NDArray calculateSimilarityReward(
            NDArray states, NDArray actions, NDArray genStates, NDArray genActions) {
        var stateSimilarity = calculateCosineSimilarity(states, genStates);
        var actionSimilarity = calculateCosineSimilarity(actions, genActions);
        return stateSimilarity.add(actionSimilarity).mul(0.5f);
    }

    private NDArray calculateCosineSimilarity(NDArray a, NDArray b) {
        var normA = a.norm(new int[]{1}, true);
        var normB = b.norm(new int[]{1}, true);
        return a.mul(b).sum(new int[]{1})
            .div(normA.mul(normB).add(1e-8f));
    }
    
    private NDArray calculateDiversityReward(NDArray actions) {
        var distances = actions.matMul(actions.transpose())
            .mul(-2)
            .add(actions.pow(2).sum(new int[]{1}, true))
            .add(actions.pow(2).sum(new int[]{1}, true).transpose());
        
        return distances.mean(new int[]{1});
    }
    
    private void trainDiffusionModel(NDArray states, NDArray actions) {
        var trainer = diffusionModel.newTrainer(diffusionTrainingConfig);
        try (trainer) {
            var combined = NDArrays.concat(new NDList(states, actions), 1);
            
            for (int t = 0; t < diffusionSteps; t++) {
                float alpha = getBeta(t);
                var noise = manager.randomNormal(combined.getShape());
                var noisyData = combined.mul((float)Math.sqrt(1 - alpha))
                    .add(noise.mul((float)Math.sqrt(alpha)));
                
                var timestep = manager.create(new float[]{t / (float)diffusionSteps});
                var input = NDArrays.concat(new NDList(noisyData, timestep), 1);
                
                var gradientCollector = trainer.newGradientCollector();
                try (gradientCollector) {
                    var predicted = trainer.forward(new NDList(input))
                        .singletonOrThrow();
                    var loss = predicted.sub(noise).pow(2).mean();
                    gradientCollector.backward(loss);
                    trainer.step();
                }
            }
        }
    }

    public void saveModel(String flag) {
        String basePath = "/root/results/model/" + flag;
        try {
            actorModel.save(Paths.get(basePath), null);
            criticModel.save(Paths.get(basePath), null);
        } catch (IOException e) {
            log.error("save model: {}", e.getMessage());
        }
    }

    public void loadModel(String flag) {
        String basePath = "/root/results/model/" + flag;
        try {
            actorModel.load(Paths.get(basePath));
            criticModel.load(Paths.get(basePath));
        } catch (IOException | MalformedModelException e) {
            log.error("load model: {}", e.getMessage());
        }
    }
}
