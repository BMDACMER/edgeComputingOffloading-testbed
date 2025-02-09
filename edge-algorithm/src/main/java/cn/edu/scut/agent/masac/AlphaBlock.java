package cn.edu.scut.agent.masac;

import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.AbstractBlock;
import ai.djl.nn.Parameter;
import ai.djl.training.ParameterStore;
import ai.djl.training.initializer.ConstantInitializer;
import ai.djl.util.PairList;

/**
 * AlphaBlock是一个继承自AbstractBlock的类，用于实现AlphaBlock的功能。
 */
public class AlphaBlock extends AbstractBlock {

    private Parameter logAlpha;

    /**
     * 构造函数，用于创建AlphaBlock对象。
     *
     * @param initAlpha 初始alpha值
     */
    public AlphaBlock(float initAlpha) {
        logAlpha = addParameter(Parameter.builder()
                .setName("alpha")
                .setType(Parameter.Type.WEIGHT)
                .optShape(new Shape(1))
                .optInitializer(new ConstantInitializer((float) Math.log(initAlpha)))
                .optRequiresGrad(true)
                .build());
    }

    /**
     * 执行AlphaBlock的前向传播。
     *
     * @param parameterStore 参数存储器
     * @param inputs 输入NDList
     * @param training 表示是否处于训练模式
     * @param params 附加参数
     * @return 包含logAlpha数组的输出NDList
     */
    @Override
    protected NDList forwardInternal(ParameterStore parameterStore, NDList inputs, boolean training, PairList<String, Object> params) {
        return new NDList(logAlpha.getArray());
    }

    /**
     * 获取输出形状。
     *
     * @param inputShapes 输入形状数组
     * @return 输出形状数组，包含一个形状为1的Shape对象
     */
    @Override
    public Shape[] getOutputShapes(Shape[] inputShapes) {
        return new Shape[]{new Shape(1)};
    }
}
