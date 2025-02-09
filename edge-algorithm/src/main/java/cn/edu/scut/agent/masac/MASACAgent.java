package cn.edu.scut.agent.masac;

import ai.djl.MalformedModelException;
import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.training.TrainingConfig;
import ai.djl.translate.NoopTranslator;
import ai.djl.translate.TranslateException;
import cn.edu.scut.agent.MultiAgentAdaptor;
import cn.edu.scut.agent.MultiAgentBuffer;
import cn.edu.scut.service.TaskService;
import cn.edu.scut.util.DJLUtils;
import cn.edu.scut.util.EnvUtils;
import cn.edu.scut.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
@ConditionalOnProperty(name = "rl.name", havingValue = "masac")
public class MASACAgent extends MultiAgentAdaptor implements InitializingBean {

    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Value("${rl.alpha}")
    private float alpha;

    @Value("${rl.gamma}")
    private float gamma;

    @Value("${rl.use-soft-update}")
    private boolean useSoftUpdate;

    @Value("${rl.tau}")
    private float tau;

    @Value("${rl.use-adaptive-alpha}")
    private boolean useAdaptiveAlpha;

    @Value("${rl.use-normalized-reward}")
    private boolean useNormalizedReward;

    @Autowired
    private Random schedulerRandom;

    @Autowired
    private Model q1Model;
    @Autowired
    private Model q2Model;

    @Autowired
    private Model targetQ1Model;
    @Autowired
    private Model targetQ2Model;
//    @Autowired
//    private Model actorRedundancyModel;    // passive redundancy decision

    @Autowired
    private Model actorModel;

    @Autowired
    private Model criticModel;

    @Autowired
    private MultiAgentBuffer buffer;

    @Autowired
    private NDManager manager;

    private float targetEntropy;

    @Autowired
    private Model alphaModel;

    @Autowired
    private TaskService taskService;

    @Autowired
    private EnvUtils envUtils;

    @Value("${spring.application.name}")
    String name;

    @Autowired
    private FileSystem fileSystem;

    @Value("${hadoop.hdfs.url}")
    private String hdfsUrl;

    @Value("${rl.use-cql}")
    private boolean useCql;

    @Value("${rl.cql-weight}")
    private float cqlWeight;

    @Value("${rl.use-addition-critic}")
    private boolean useAdditionCritic;

    @Value("${rl.target-entropy-coef}")
    private float targetEntropyCoef;

//    @Value("${env.task-redundancy}")
//    private int taskRedundancy;
//
//    @Value("${env.dynamic-redundancy-number}")
//    private int dynamicRedundancyNumber;

    @Autowired
    private TrainingConfig trainingConfig;

    private final Lock lock = new ReentrantLock();

    private int actionShape;

    private int offloadingShape;

    @Override
    public void afterPropertiesSet() {
//        TARGET_ENTROPY = (float) Math.log(1.0 / ACTION_SHAPE) * 0.98f;
        // We use 0.6 because the recommended 0.98 will cause alpha explosion.
        actionShape = edgeNodeNumber + 1;
        offloadingShape = actionShape;
        targetEntropy = -(float) Math.log(1.0 / actionShape) * targetEntropyCoef;

    }

    @Override
    public int[] selectAction(float[] state, int[] availAction, boolean training, int taskId) {
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
                    out.set(bool, -1e8f);    // 等于0的为不可达
                    var prob = out.softmax(-1);

                    action = DJLUtils.sampleMultinomial(schedulerRandom, prob)  + 1;
                }
            }
        } finally {
            lock.unlock();
        }
        return new int[]{action};
    }

//    @Override
//    public int selectPassiveRedundancy(float[] state, boolean training, int taskId) {
//        return selectRedundancy(state, training, taskId);
//    }
//
//    private int selectRedundancy(float[] state, boolean training, int taskId) {
//        int action;
//        try {
//            lock.lock();
//            var subManager = manager.newSubManager();
//            try (subManager) {
//                var predictor = actorRedundancyModel.newPredictor(new NoopTranslator());
//                try (predictor) {
//                    NDArray out = null;
//                    try {
//                        out = predictor.predict(new NDList(subManager.create(state))).singletonOrThrow();
//                    } catch (TranslateException e) {
//                        log.error("predict error: {}", e.getMessage());
//                    }
//                    assert out != null;
////                    var prob = out.softmax(-1);
////                    log.info("=====redundancy===  out: {}", out);
//                    var prob = Activation.sigmoid(out);
////                    log.info("#########passive redundancy prob {}", prob);
//                    float[] prob2 = prob.toFloatArray();
//                    if (prob2[0] > 0.5f) {
//                        action = 1;
//                    }else {
//                        action = 0;
//                    }
//                }
//            }
//        } finally {
//            lock.unlock();
//        }
//        return action;
//    }

    public int selectAction(float[] state, int[] availAction, boolean training) {
        int action = 0;
        var subManager = manager.newSubManager();
        try (subManager) {
            var predictor = actorModel.newPredictor(new NoopTranslator());
            try (predictor) {
                try {
                    var out = predictor.predict(new NDList(subManager.create(state))).singletonOrThrow();
                    var bool = subManager.create(availAction).eq(0);
                    out.set(bool, -1e8f);
                    var prob = out.softmax(-1);
                    action = DJLUtils.sampleMultinomial(schedulerRandom, prob);
                } catch (TranslateException e) {
                    log.error("predict error: {}", e.getMessage());
                }
            }
        }
        return action;
    }

//    @Override
//    public int[] selectMultiAction(float[] state, int[] availAction, int passiveRedundancy, boolean training, int taskId) {
//        var actions = new int[taskRedundancy];
//        log.info("1=====actions: {}", actions);
//        boolean flag = false;
//        // 如果最大任务冗余为3，offloadShape为5，则actions数组为[5,5,5]
//        Arrays.fill(actions, offloadingShape);
//        log.info("2=====actions: {}", actions);
//        //获取任务
//        var task = taskService.getById(taskId);
//        //记录已经分配的节点
//        var set = new HashSet<Integer>();
//        var manager = NDManager.newBaseManager();
//        try (manager) {
//            var predictor = actorModel.newPredictor(new NoopTranslator());
//            try (predictor) {
//                NDArray out;
//                try {
//                    out = predictor.predict(new NDList(manager.create(state))).singletonOrThrow();
//                } catch (TranslateException e) {
//                    throw new RuntimeException(e);
//                }
//                // 复制taskRedundancy次预测结果，并把形状设置成Shape(taskRedundancy, offloadingShape)
//                log.info("==========out: {}", out);  // [17]
//                var multiOut = out.repeat(0, taskRedundancy).reshape(new Shape(offloadingShape, taskRedundancy)).transpose();  // [3,17]
//                log.info("======multiOut: {}", multiOut);
//
//                if (availAction[availAction.length - 1] == 1) {
//                    availAction[availAction.length - 1] = 0;
//                }
//
//                var availAction_t = manager.create(availAction);
//                for (int i = 0; i < taskRedundancy; i++) {
//                    var out_t = out;
//                    var bool_t = availAction_t.eq(0);
//                    out_t.set(bool_t, -1e8f);
//                    var prob_t = out_t.softmax(-1);
//                    // edge node 从 1 开始计数
//                    // 获取第i个冗余任务的执行节点
//                    var edgeNodeId_t = DJLUtils.sampleMultinomial(schedulerRandom, prob_t) + 1;
//                    set.add(edgeNodeId_t);
//                    actions[i] = edgeNodeId_t;
//                    //防止下一轮选到同样的节点
//                    availAction_t.set(new NDIndex(edgeNodeId_t - 1), 0);
//
//                    if(edgeNodeId_t == offloadingShape){
//                        log.info("active redundancy may be wrong,set: {}", set);
//                    }else{
//                        dynamicRedundancyNumber = i+1;
//                    }
//
//
//                    if (envUtils.isMeetReliabilityRequirement(set, task)) {
//                        flag = true;
//                        break;
//                    }
//                }
//
//                if (!flag && passiveRedundancy==1) {   // Active redundancy has all failed and passive redundancy is started
//                    for (int i = dynamicRedundancyNumber; i < taskRedundancy; i++) {
//                        var out_t = out;
//                        var bool_t = availAction_t.eq(0);
//                        out_t.set(bool_t, -1e8f);
//                        var prob_t = out_t.softmax(-1);
//
//                        var edgeNodeId_t = DJLUtils.sampleMultinomial(schedulerRandom, prob_t) + 1;
//                        set.add(edgeNodeId_t);
//                        if(edgeNodeId_t == offloadingShape){
//                            log.info("passive redundancy may be wrong,set: {}", set);
//                        }
//                        actions[i] = edgeNodeId_t;
//
//                        if (envUtils.isMeetReliabilityRequirement(set, task)) {
//                            break;
//                        }
//                    }
//                }
//
//            }
//        }
//        return actions;
//    }

    public void train() {
        var subManager = manager.newSubManager();
        try (subManager) {
            NDList list = buffer.sample(subManager);
            var states = list.get(0);
            var actions = list.get(1);
            var availActions = list.get(2);
            var passiveRedundancyActions = list.get(3);
            var rewards = list.get(4);
            var nextStates = list.get(5);

            if (useNormalizedReward) {
                var mean = rewards.mean();
                var std = rewards.sub(mean).pow(2).mean().sqrt().add(1e-5f);
                rewards = rewards.sub(mean).div(std);
            }

            var q1Trainer = q1Model.newTrainer(trainingConfig);
            var q2Trainer = q2Model.newTrainer(trainingConfig);
            var actorTrainer = actorModel.newTrainer(trainingConfig);
            var criticTrainer = criticModel.newTrainer(trainingConfig);
            var targetQ1Predictor = targetQ1Model.newPredictor(new NoopTranslator());
            var targetQ2Predictor = targetQ2Model.newPredictor(new NoopTranslator());
            var alphaTrainer = alphaModel.newTrainer(trainingConfig);
            NDArray alphaValue;
            if (useAdaptiveAlpha) {
                alphaValue = alphaModel.getBlock().getParameters().get("alpha").getArray().duplicate().exp();
            } else {
                alphaValue = subManager.create(alpha);
            }
            try (q1Trainer; q2Trainer; actorTrainer; criticTrainer; targetQ1Predictor; targetQ2Predictor; alphaTrainer) {
                var actorOut = actorTrainer.evaluate(new NDList(nextStates)).singletonOrThrow();
                var nextLogProbabilities = actorOut.logSoftmax(-1);
                NDArray nextTargetQ1;
                NDArray nextTargetQ2;
                try {
                    nextTargetQ1 = targetQ1Predictor.predict(new NDList(nextStates)).singletonOrThrow();
                    nextTargetQ2 = targetQ2Predictor.predict(new NDList(nextStates)).singletonOrThrow();
                } catch (TranslateException e) {
                    throw new RuntimeException(e);
                }
                var targetQ = nextLogProbabilities.exp().mul(NDArrays.minimum(nextTargetQ1, nextTargetQ2).sub(nextLogProbabilities.mul(alphaValue)));
                var avgTargetQ = targetQ.sum(new int[]{-1}, true);
                var target = rewards.add(avgTargetQ.mul(gamma));
                var q1Value = q1Trainer.evaluate(new NDList(states)).singletonOrThrow();
                var q2Value = q2Trainer.evaluate(new NDList(states)).singletonOrThrow();
                var lobProbabilityValue = actorTrainer.evaluate(new NDList(states)).singletonOrThrow().logSoftmax(-1);

                if (useAdaptiveAlpha) {
                    var alphaGradientCollector = alphaTrainer.newGradientCollector();
                    try (alphaGradientCollector) {
                        var entropy = lobProbabilityValue.exp().mul(lobProbabilityValue).sum(new int[]{-1}).mean().neg();
                        var logAlpha = alphaModel.getBlock().getParameters().get("alpha").getArray();
                        var loss = logAlpha.mul(entropy.sub(targetEntropy));
                        alphaGradientCollector.backward(loss);
                        alphaTrainer.step();
                    }
                }

                var actorGradientCollector = actorTrainer.newGradientCollector();
                try (actorGradientCollector) {
                    var qMin = NDArrays.minimum(q1Value, q2Value);
                    var lobProbabilities = actorTrainer.forward(new NDList(states)).singletonOrThrow().logSoftmax(-1);
                    var loss = lobProbabilities.exp().mul(qMin.sub(lobProbabilities.mul(alphaValue))).sum(new int[]{-1}).mean().neg();
                    // //Caused by: java.lang.IllegalStateException: Gradient values are all zeros, please call gradientCollector.backward() onyour target NDArray (usually loss), before calling step() 
                    // //为了解决这个问题，在loss上添加一个操作，使得loss不为0
                    // loss.add( manager.create(1e-10f));
                    actorGradientCollector.backward(loss);
                    actorTrainer.step();
                }

                var q1GradientCollector = q1Trainer.newGradientCollector();
                try (q1GradientCollector) {
                    var q1 = q1Trainer.forward(new NDList(states)).singletonOrThrow();
                    var q1Action = q1.gather(actions, -1);
                    var loss1 = q1Action.sub(target).pow(2).mean();
                    if (useCql) {
                        var cqlLoss1 = (q1.exp().sum().log().mean()).sub(q1Action.mean());
                        loss1.add(cqlLoss1.mul(cqlWeight));
                    }
                    q1GradientCollector.backward(loss1);
                    q1Trainer.step();
                }
                var q2GradientCollector = q2Trainer.newGradientCollector();
                try (q2GradientCollector) {
                    var q2 = q2Trainer.forward(new NDList(states)).singletonOrThrow();
                    var q2Action = q2.gather(actions, -1);
                    var loss2 = q2Action.sub(target).pow(2).mean();
                    if (useCql) {
                        var cqlLoss2 = (q2.exp().sum().log().mean()).sub(q2Action.mean());
                        loss2.add(cqlLoss2.mul(cqlWeight));
                    }
                    q2GradientCollector.backward(loss2);
                    q2Trainer.step();
                }
                if (useAdditionCritic) {
                    var criticGradientCollector = criticTrainer.newGradientCollector();
                    try (criticGradientCollector) {
                        var value = criticTrainer.evaluate(new NDList(states)).singletonOrThrow();
                        var loss = value.sub(target).pow(2).mean();
                        criticGradientCollector.backward(loss);
                        criticTrainer.step();
                    }
                }
            }
            if (useSoftUpdate) {
                DJLUtils.softUpdate(q1Model.getBlock(), targetQ1Model.getBlock(), tau);
                DJLUtils.softUpdate(q1Model.getBlock(), targetQ2Model.getBlock(), tau);
            }
        }
    }

    /**
        * 保存模型到results/model/path/...
        *
        * @param path 模型保存的路径
        */
    public void saveModel(String path) {
        String basePath = "results/model/" + path + "/";
        var actorPath = basePath + "actor.param";
        var criticPath = basePath + "critic.param";
        try {
            Files.createDirectories(Paths.get(actorPath).getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        DJLUtils.saveModel(Paths.get(actorPath), actorModel.getBlock());
        DJLUtils.saveModel(Paths.get(criticPath), criticModel.getBlock());
    }

    public void loadModel(String path) {
        String basePath = "results/model/" + path + "/";
        var actorPath = basePath + "actor.param";
        var criticPath = basePath + "critic.param";
        DJLUtils.loadModel(Paths.get(actorPath), actorModel.getBlock(), manager);
        DJLUtils.loadModel(Paths.get(criticPath), criticModel.getBlock(), manager);
    }

    // edge-experiment
    @Override
    public void saveHdfsModel(String flag) {
        String basePath = "results/model/" + flag;
        try {
            actorModel.save(Paths.get(basePath), null);
            criticModel.save(Paths.get(basePath), null);
        } catch (IOException e) {
            log.error("save model error: {}", e.getMessage());
        }
        var actorPath = basePath + "/actor-0000.params";
        var criticPath = basePath + "/critic-0000.params";
        try {
            if (fileSystem.exists(new Path(hdfsUrl + "/" + basePath))) {
                fileSystem.delete(new Path(hdfsUrl + "/" + basePath), true);
            }
        } catch (IOException e) {
            log.error("delete file in file system error: {}", e.getMessage());
        }
        try {
            fileSystem.copyFromLocalFile(true, true, new Path(actorPath), new Path(hdfsUrl + "/" + actorPath));
            fileSystem.copyFromLocalFile(true, true, new Path(criticPath), new Path(hdfsUrl + "/" + criticPath));
        } catch (IOException e) {
            log.error("file system save file error: {}", e.getMessage());
        }
    }

    // edge-node, edge-experiment
    @Override
    public void loadHdfsModel(String flag) {
        String basePath = "results/model/" + flag;
        try {
            var actorPath = basePath + "/actor-0000.params";
            var criticPath = basePath + "/critic-0000.params";
            fileSystem.copyToLocalFile(false, new Path(hdfsUrl + "/" + actorPath), new Path(actorPath), true);
            fileSystem.copyToLocalFile(false, new Path(hdfsUrl + "/" + criticPath), new Path(criticPath), true);
        } catch (IOException e) {
            log.info("load hdfs model: {}", e.getMessage());
        }
        try {
            actorModel.load(Paths.get(basePath));
            criticModel.load(Paths.get(basePath));
        } catch (IOException | MalformedModelException e) {
            throw new RuntimeException(e);
        }
        FileUtils.recursiveDelete(Paths.get(basePath).toFile());
    }

    @Override
    public void loadSteamModel(InputStream inputStream, String fileName) {
        switch (fileName) {
            case "actor.param" -> {
                try {
                    actorModel.load(inputStream);
                } catch (IOException | MalformedModelException e) {
                    log.info("load stream model error: {}", e.getMessage());
                }
            }
            case "critic.param" -> {
                try {
                    criticModel.load(inputStream);
                } catch (IOException | MalformedModelException e) {
                    log.info("load stream model error: {}", e.getMessage());
                }
            }
            default -> throw new RuntimeException("fileName error!");
        }
    }
}
