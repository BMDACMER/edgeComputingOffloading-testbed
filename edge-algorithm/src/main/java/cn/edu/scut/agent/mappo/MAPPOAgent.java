package cn.edu.scut.agent.mappo;

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
import cn.edu.scut.agent.MultiAgentAdaptor;
import cn.edu.scut.agent.MultiAgentBuffer;
import cn.edu.scut.service.TaskService;
import cn.edu.scut.util.DJLUtils;
import cn.edu.scut.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FileSystem;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
@ConditionalOnProperty(name = "rl.name", havingValue = "mappo")
public class MAPPOAgent extends MultiAgentAdaptor implements InitializingBean {

    @Autowired
    private Random schedulerRandom;

    @Autowired
    private NDManager manager;

    @Autowired
    private MultiAgentBuffer buffer;

    @Value("${rl.use-normalized-reward}")
    private boolean useNormalizedReward;

    @Value("${rl.epoch}")
    private int epoch;

    @Value("${rl.clip}")
    private float clip;

    @Value("${rl.use-entropy}")
    private boolean useEntropy;

    @Value("${rl.entropy-coef}")
    private float entropyCoef;

    @Value("${rl.gamma}")
    private float gamma;

    @Value("${env.time-slot-number}")
    private int timeSlotNumber;

    @Value("${rl.use-gae}")
    private boolean useGae;

    @Value("${rl.gae-lambda}")
    private float gaeLambda;

    @Value("${spring.application.name}")
    String name;

    @Value("${env.use-redundancy}")
    private boolean useRedundancy;

    @Value("${env.max-task-redundancy}")
    private int maxTaskRedundancy;

    @Value("${rl.batch-size}")
    private int batchSize;

    @Autowired
    private Model actorModel;

    @Autowired
    private Model criticModel;

    @Autowired
    private TrainingConfig trainingConfig;

    private final Lock lock = new ReentrantLock();

    private int offloadingShape;

    @Autowired
    private EnvUtils envUtils;


    // env
    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Override
    public void afterPropertiesSet() {
        offloadingShape = edgeNodeNumber + 1;
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
                    log.info("======bool: {}", bool);
                    assert out != null;
                    log.info("======before out: {}", out);
                    out.set(bool, -1e8f);    // 等于0的为不可达
                    log.info("======after out: {}", out);
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
                log.info("list: {}", list);
                var states = list.get(0);
                var actions = list.get(1);
                if (useRedundancy) { // 已弃用
                    actions = actions.expandDims(-1);  // 表示在 actions 张量的最后一个维度上增加一个维度，这个新维度的大小为 1
                }
                var availActions = list.get(2);
                var rewards = list.get(3);
                var nextStates = list.get(4);
                // Trick: normalized reward
                if (useNormalizedReward) {
                    var mean = rewards.mean();
                    var std = rewards.sub(mean).pow(2).mean().sqrt().add(1e-5f);
                    rewards = rewards.sub(mean).div(std);
                }
                
                //训练器
                var criticTrainer = criticModel.newTrainer(trainingConfig);
                var actorTrainer = actorModel.newTrainer(trainingConfig);

                try (criticTrainer; actorTrainer) {
                    var stateValues = criticTrainer.evaluate(new NDList(states)).singletonOrThrow();
                    var nextStateValues = criticTrainer.evaluate(new NDList(nextStates)).singletonOrThrow();
                    // gae
                    NDArray advantages;
                    if (useGae) {
                        advantages = DJLUtils.getGae(rewards, stateValues, nextStateValues, subManager, gamma, batchSize, gaeLambda);
                    } else {
                        var returns = DJLUtils.getReturn(rewards, nextStateValues, gamma, batchSize);
                        advantages = returns.sub(stateValues);
                    }
                    var targets = advantages.add(stateValues);

                    var out = actorTrainer.evaluate(new NDList(states)).singletonOrThrow();
                    var index1 = availActions.eq(0);
                    out.set(index1, -1e5f);
                    if (useRedundancy) {
                        out = out.reshape(new Shape(batchSize, edgeNodeNumber, maxTaskRedundancy, offloadingShape));
                    }
                    var logProb = out.logSoftmax(-1);
                    var oldLogProbTaken = logProb.gather(actions, -1);
                    if (useRedundancy) {
                        oldLogProbTaken = oldLogProbTaken.sum(new int[]{-1});
                    }
                    for (int i = 0; i < epoch; i++) {
                        var gradientCollector = actorTrainer.newGradientCollector();
                        try (gradientCollector) {
                            var output = actorTrainer.forward(new NDList(states)).singletonOrThrow();
                            var index = availActions.eq(0);
                            output.set(index, -1e5f);
                            if (useRedundancy) {
                                output = output.reshape(new Shape(batchSize, edgeNodeNumber, maxTaskRedundancy, offloadingShape));
                            }
                            var probabilities = output.softmax(-1);
                            // attention！！！
                            var logProbabilities = output.logSoftmax(-1);
                            var logProbTaken = logProbabilities.gather(actions, -1);
                            if (useRedundancy) {
                                logProbTaken = logProbTaken.sum(new int[]{-1});
                            }
                            // PPO
                            var ratios = logProbTaken.sub(oldLogProbTaken).exp();  // (50,7,1)
                            //
                            var surr1 = ratios.mul(advantages);
                            var surr2 = ratios.clip(1 - clip, 1 + clip).mul(advantages);
                            // maximizing
                            var loss = NDArrays.minimum(surr1, surr2).mean().neg();
                            // Trick: entropy
                            if (useEntropy) {
                                // entropy definition:
                                var entropy = probabilities.mul(logProbabilities).neg();
                                if (useRedundancy) {
                                    entropy = entropy.sum(new int[]{-1});   // -1 表示对数组的最后一个维度进行求和
                                }
                                // maximizing entropy
                                var entropyLoss = entropy.mean().mul(entropyCoef).neg();
                                loss = loss.add(entropyLoss);
                            }
                            log.info("=======loss: {}", loss);
                            gradientCollector.backward(loss);
                            actorTrainer.step();
                        }

                        // update critic
                        var criticGradientCollector = criticTrainer.newGradientCollector();
                        try (criticGradientCollector) {
                            var newValues = criticTrainer.forward(new NDList(states)).singletonOrThrow();
                            var criticLoss = newValues.sub(targets).pow(2).mean().mul(0.5f);
                            criticGradientCollector.backward(criticLoss);
                            criticTrainer.step();
                        }
                    }
                }
            }
        } finally {
            lock.unlock();
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
