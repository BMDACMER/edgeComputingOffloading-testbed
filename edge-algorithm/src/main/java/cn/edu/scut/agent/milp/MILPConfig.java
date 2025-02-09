package cn.edu.scut.agent.milp;

import ai.djl.Model;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.nn.Activation;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.TrainingConfig;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "rl.name", havingValue = "milp")
public class MILPConfig implements InitializingBean {
    
    @Value("${rl.epoch}")
    private int epoch;

    @Value("${rl.learning-rate}")
    private float learningRate;
    
    @Value("${rl.discriminator-learning-rate}")
    private float discriminatorLearningRate;

    @Value("${rl.hidden-shape}")
    private int hiddenShape;

    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Value("${rl.batch-size}")
    private int batchSize;

    @Value("${rl.expert-trajectories-path}")
    private String expertTrajectoriesPath;

    @Value("${rl.discriminator-epochs}")
    private int discriminatorEpochs;

    private int observationShape;
    private int actionShape;

    @Override
    public void afterPropertiesSet() {
        observationShape = edgeNodeNumber * 6 + 3;
        actionShape = edgeNodeNumber + 1;
    }

    @Bean
    public Optimizer discriminatorOptimizer() {
        return Optimizer.adam()
                .optLearningRateTracker(Tracker.fixed(discriminatorLearningRate))
                .build();
    }

    @Bean
    public Model discriminatorModel(NDManager manager) {
        var model = Model.newInstance("discriminator");
        model.setBlock(createDiscriminatorNetwork(manager));
        return model;
    }

    private Block createDiscriminatorNetwork(NDManager manager) {
        var block = new SequentialBlock();
        block.add(Linear.builder().setUnits(hiddenShape).build());
        block.add(Activation::relu);
        block.add(Linear.builder().setUnits(hiddenShape).build());
        block.add(Activation::relu);
        block.add(Linear.builder().setUnits(1).build());
        block.add(Activation::sigmoid);
        
        block.initialize(manager, DataType.FLOAT32, new Shape(batchSize, observationShape + actionShape));
        return block;
    }

    @Bean
    public Loss binaryCrossEntropyLoss() {
        return Loss.sigmoidBinaryCrossEntropyLoss();
    }

    @Bean
    public TrainingConfig discriminatorTrainingConfig(
            Optimizer discriminatorOptimizer, 
            Loss binaryCrossEntropyLoss) {
        return new DefaultTrainingConfig(binaryCrossEntropyLoss)
                .optOptimizer(discriminatorOptimizer);
    }
} 