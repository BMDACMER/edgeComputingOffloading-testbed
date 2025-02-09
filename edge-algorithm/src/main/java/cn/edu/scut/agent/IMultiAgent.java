package cn.edu.scut.agent;

import java.io.InputStream;

import ai.djl.translate.TranslateException;
import cn.edu.scut.bean.Task;

public interface IMultiAgent {

    int selectAction(float[] state, int[] availAction, boolean training);

    int[] selectAction(float[] state, int[] availAction, boolean training, int taskId);

    //
    int[] selectMultiAction(float[] state, int[] availAction, int passiveRedundancy, boolean training, int taskId);

    void train() throws TranslateException;

    void saveModel(String flag);

    void loadModel(String flag);

    void saveHdfsModel(String flag);

    void loadHdfsModel(String flag);

    void loadSteamModel(InputStream inputStream, String fileName);

}
