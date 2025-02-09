package cn.edu.scut.agent.masac;

import ai.djl.Model;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.TrainingConfig;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@ConditionalOnProperty(name = "rl.name", havingValue = "masac")
public class MASACConfiguration implements InitializingBean {

    private int stateShape;

    @Value("${rl.hidden-shape}")
    private int hiddenShape;

    private int actionShape;

    @Value("${rl.learning-rate}")
    private float learningRate;

    @Value("${rl.alpha}")
    private float alpha;

    @Value("${env.edge-node-number}")
    private int edgeNodeNumber;

    @Value("${env.max-task-redundancy}") //这个值暂时为1
    private int maxTaskRedundancy;

    //这俩是我后加的
    @Value("${rl.observation.add-reliability-requirement}")
    private boolean addReliabilityRequirement;
    @Value("${env.use-task-reliability}")
    private boolean useTaskReliability;

    @Override
    public void afterPropertiesSet() {
        int offloadingShape = edgeNodeNumber + 1;
        actionShape = maxTaskRedundancy * offloadingShape; //1+节点数,0表示无作为
        stateShape = edgeNodeNumber * 6 + 3;
        if (addReliabilityRequirement) {
            stateShape += 1;
        }
        if (useTaskReliability) {
            stateShape += 1;
        }
    }

    /**
        * 创建一个网络块，两个全连接层作为隐藏层
        * 作为Critics和Actor的网络结构
        *
        * @param manager    NDManager对象，用于初始化网络块
        * @param outputDim  输出维度
        * @return  创建的网络块
        */
    public Block createNetwork(NDManager manager, int outputDim) {
        var block = new SequentialBlock();
        block.add(Linear.builder().setUnits(hiddenShape).build());
        block.add(Activation::relu);
        block.add(Linear.builder().setUnits(hiddenShape).build());
        block.add(Activation::relu);
        block.add(Linear.builder().setUnits(outputDim).build());
        block.initialize(manager, DataType.FLOAT32, new Shape(stateShape));
        return block;
    }

    @Bean
    public Optimizer optimizer() {
        return Optimizer.adam().optLearningRateTracker(Tracker.fixed(learningRate)).build();
    }

    @Bean
    public Model q1Model(NDManager manager) {
        var model = Model.newInstance("q1");
        model.setBlock(createNetwork(manager, actionShape));
        return model;
    }

    @Bean
    public Model q2Model(NDManager manager) {
        var model = Model.newInstance("q2");
        model.setBlock(createNetwork(manager, actionShape));
        return model;
    }

    @Bean
    public Model targetQ1Model(NDManager manager) {
        var model = Model.newInstance("targetQ1");
        model.setBlock(createNetwork(manager, actionShape));
        return model;
    }

    @Bean
    public Model targetQ2Model(NDManager manager) {
        var model = Model.newInstance("targetQ2");
        model.setBlock(createNetwork(manager, actionShape));
        return model;
    }

    @Bean
    public Model criticModel(NDManager manager) {
        var model = Model.newInstance("critic");
        model.setBlock(createNetwork(manager, 1));
        return model;
    }

    @Bean
    public Model actorModel(NDManager manager) {
        var model = Model.newInstance("actor");
        model.setBlock(createNetwork(manager, actionShape));
        return model;
    }

    @Bean
    public Model alphaModel(NDManager manager) {
        var model = Model.newInstance("alpha");
        var block = new AlphaBlock(alpha);
        block.initialize(manager, DataType.FLOAT32, new Shape(1));
        model.setBlock(block);
        return model;
    }

//    @Bean
//    public Model actorRedundancyModel(NDManager manager) {
//        var model = Model.newInstance("actor");
//        model.setBlock(createNetwork(manager, actionShape));
//        return model;
//    }
    @Bean
    public Loss loss() {
        return new Loss("null") {
            @Override
            public NDArray evaluate(NDList ndList, NDList ndList1) {
                return null;
            }
        };
    }

    @Bean
    public TrainingConfig trainingConfig(Optimizer optimizer, Loss loss) {
        return new DefaultTrainingConfig(loss)
                .optOptimizer(optimizer);
    }
}
