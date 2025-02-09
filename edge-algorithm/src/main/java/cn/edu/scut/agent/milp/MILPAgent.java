package cn.edu.scut.agent.milp;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.training.TrainingConfig;
import ai.djl.translate.NoopTranslator;
import ai.djl.translate.TranslateException;
import cn.edu.scut.agent.MultiAgentAdaptor;
import cn.edu.scut.agent.MultiAgentBuffer;
import cn.edu.scut.util.DJLUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


@Component
@Slf4j
@ConditionalOnProperty(name = "rl.name", havingValue = "milp")
public class MILPAgent extends MultiAgentAdaptor implements InitializingBean {
    
    @Autowired
    private Random schedulerRandom;
    
    @Autowired
    private NDManager manager;
    
    @Autowired
    private MultiAgentBuffer buffer;
    
    @Autowired
    private Model actorModel;
    
    @Autowired
    private Model criticModel;
    
    @Autowired
    private Model discriminatorModel;
    
    @Autowired
    private TrainingConfig actorTrainingConfig;
    
    @Autowired
    private TrainingConfig criticTrainingConfig;
    
    @Autowired
    private TrainingConfig discriminatorTrainingConfig;
    
    @Value("${rl.discriminator-epochs}")
    private int discriminatorEpochs;
    
    @Value("${rl.expert-trajectories-path}")
    private String expertTrajectoriesPath;
    
    @Value("${rl.gamma}")
    private float gamma;
    
    @Value("${rl.batch-size}")
    private int batchSize;
    
    @Value("${rl.gae-lambda}")
    private float gaeLambda;
    
    @Value("${rl.use-gae}")
    private boolean useGae;
    
    private final Lock lock = new ReentrantLock();
    private NDArray expertStates;
    private NDArray expertActions;
    
    @Override
    public void afterPropertiesSet() throws Exception {
        loadExpertTrajectories();
    }
    
    private void loadExpertTrajectories() {
        // Load the expert trajectory data from the file
        try (var subManager = manager.newSubManager()) {
            var expertData = DJLUtils.loadNDArrayFromFile(
                expertTrajectoriesPath, subManager);
            expertStates = expertData.get(0);
            expertActions = expertData.get(1);
        }
    }
    
    @Override
    public int[] selectAction(float[] state, int[] availAction, 
            boolean training, int taskId) {
        try {
            lock.lock();
            var subManager = manager.newSubManager();
            try (subManager) {
                var predictor = actorModel.newPredictor(new NoopTranslator());
                try (predictor) {
                    NDArray out = predictor.predict(new NDList(subManager.create(state))).singletonOrThrow();
                    
                    var bool = subManager.create(availAction).eq(0);
                    out.set(bool, -1e8f);
                    
                    int action = DJLUtils.sampleMultinomialSecond(
                        schedulerRandom, out) + 1;
                    return new int[]{action};
                } catch (TranslateException e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            lock.unlock();
        }
    }
    
    public void train() throws TranslateException {
        try {
            lock.lock();
            var subManager = manager.newSubManager();
            try (subManager) {
                var list = buffer.sampleAll(subManager);
                var states = list.get(0);
                var actions = list.get(1);
                var rewards = list.get(3);
                var nextStates = list.get(4);

                trainDiscriminator(states, actions);

                var discriminatorRewards = getDiscriminatorRewards(states, actions);
                
                trainPolicyAndValue(states, actions, discriminatorRewards,
                    nextStates);
            }
        } finally {
            lock.unlock();
        }
    }
    
    private void trainDiscriminator(NDArray states, NDArray actions) {
        var discriminatorTrainer = discriminatorModel.newTrainer(
            discriminatorTrainingConfig);
        
        try (discriminatorTrainer) {
            for (int epoch = 0; epoch < discriminatorEpochs; epoch++) {
                var expertData = NDArrays.concat(
                    new NDList(expertStates, expertActions), 1);
                var generatedData = NDArrays.concat(
                    new NDList(states, actions), 1);
                
                var gradientCollector = discriminatorTrainer.newGradientCollector();
                try (gradientCollector) {
                    var expertPred = discriminatorTrainer.forward(
                        new NDList(expertData)).singletonOrThrow();
                    var expertLabels = expertData.getManager()
                        .ones(expertPred.getShape());
                    var expertLoss = discriminatorTrainer.getLoss()
                        .evaluate(new NDList(expertLabels), 
                            new NDList(expertPred));

                    var generatedPred = discriminatorTrainer.forward(
                        new NDList(generatedData)).singletonOrThrow();
                    var generatedLabels = generatedData.getManager()
                        .zeros(generatedPred.getShape());
                    var generatedLoss = discriminatorTrainer.getLoss()
                        .evaluate(new NDList(generatedLabels), 
                            new NDList(generatedPred));

                    var totalLoss = expertLoss.add(generatedLoss);
                    gradientCollector.backward(totalLoss);
                    discriminatorTrainer.step();
                }
            }
        }
    }
    
    protected NDArray getDiscriminatorRewards(NDArray states, NDArray actions) throws TranslateException {
        var discriminatorPredictor = discriminatorModel.newPredictor(new NoopTranslator());
        try (discriminatorPredictor) {
            var input = NDArrays.concat(new NDList(states, actions), 1);
            var discriminatorOutput = discriminatorPredictor.predict(
                new NDList(input)).singletonOrThrow();
            // Calculating rewards: -log(1-D(s,a))
            return discriminatorOutput.sub(1.0f).neg().log().neg();
        }
    }
    
    protected void trainPolicyAndValue(NDArray states, NDArray actions,
                                       NDArray rewards, NDArray nextStates) {
        var criticTrainer = criticModel.newTrainer(criticTrainingConfig);
        var actorTrainer = actorModel.newTrainer(actorTrainingConfig);

        try (criticTrainer; actorTrainer) {
            var stateValues = criticTrainer.evaluate(new NDList(states))
                .singletonOrThrow();
            var nextStateValues = criticTrainer.evaluate(new NDList(nextStates))
                .singletonOrThrow();

            NDArray advantages;
            if (useGae) {
                advantages = DJLUtils.getGae(rewards, stateValues, nextStateValues, 
                    manager, gamma, batchSize, gaeLambda);
            } else {
                var returns = DJLUtils.getReturn(rewards, nextStateValues, 
                    gamma, batchSize);
                advantages = returns.sub(stateValues);
            }
            var returns = advantages.add(stateValues);

            var gradientCollector = actorTrainer.newGradientCollector();
            try (gradientCollector) {
                var output = actorTrainer.forward(new NDList(states))
                    .singletonOrThrow();
                var probabilities = output.softmax(-1);
                var logProbabilities = output.logSoftmax(-1);
                var selectedLogProbs = logProbabilities.gather(actions, -1);
                
                var loss = selectedLogProbs.mul(advantages).mean().neg();
                gradientCollector.backward(loss);
                actorTrainer.step();
            }

            var criticGradientCollector = criticTrainer.newGradientCollector();
            try (criticGradientCollector) {
                var predictedValues = criticTrainer.forward(new NDList(states))
                    .singletonOrThrow();
                var valueLoss = predictedValues.sub(returns).pow(2)
                    .mean().mul(0.5f);
                criticGradientCollector.backward(valueLoss);
                criticTrainer.step();
            }
        }
    }
}
