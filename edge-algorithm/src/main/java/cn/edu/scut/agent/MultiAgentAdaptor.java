package cn.edu.scut.agent;

import java.io.InputStream;

import ai.djl.translate.TranslateException;
import cn.edu.scut.bean.Task;

public class MultiAgentAdaptor implements IMultiAgent {
    @Override
    public int selectAction(float[] state, int[] availAction, boolean training) {
        return 0;
    }

    @Override
    public int[] selectAction(float[] state, int[] availAction, boolean training, int taskId) {
        return new int[0];
    }

    @Override
    public void train() throws TranslateException {

    }

    @Override
    public void saveModel(String flag) {

    }

    @Override
    public void loadModel(String flag) {

    }

    @Override
    public void saveHdfsModel(String flag) {

    }

    @Override
    public void loadHdfsModel(String flag) {

    }

    @Override
    public void loadSteamModel(InputStream inputStream, String fileName) {

    }

    @Override
    public int[] selectMultiAction(float[] state, int[] availAction, int passiveRedundancy, boolean training, int taskId) {
        return new int[0];
    }
}
