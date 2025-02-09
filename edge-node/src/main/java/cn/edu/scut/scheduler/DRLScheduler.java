package cn.edu.scut.scheduler;


import cn.edu.scut.agent.IMultiAgent;
import cn.edu.scut.bean.EdgeNodeSystem;
import cn.edu.scut.bean.Task;
import cn.edu.scut.config.Config;
import cn.edu.scut.service.TaskService;
import cn.edu.scut.service.TransitionService;
import cn.edu.scut.util.EnvUtils;
import com.alibaba.fastjson2.JSONArray;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@ConditionalOnProperty(name = "rl.name")
public class DRLScheduler implements IScheduler {

    // heuristic method
    @Autowired(required = false)
    private IMultiAgent agent;

    @Autowired
    private TransitionService transitionService;

    @Autowired
    private TaskService taskService;
    @Autowired
    private EnvUtils envUtils;
    @Autowired
    private Config config;
    @Autowired
    private EdgeNodeSystem edgeNodeSystem;
    
    @Override
    public int[] selectAction(Task task) {
        var availAction = envUtils.getRedundantAvailAction();
        //根据当前节点的名称，获取当前节点可到达对象
        List<Integer> target = edgeNodeSystem.getNeiborList();
        for(int i = 1; i <= config.getEdgeNodeNumber(); i++) {
            if(!target.contains(i)){
                envUtils.maskEdgeNode(i-1, availAction);
            }   
        }
        task.setAvailAction(JSONArray.toJSONString(availAction));
        var state = transitionService.getObservation_(task.getSource(), task.getTimeSlot());

        int[] actions = agent.selectAction(state, availAction, false, task.getId());
        task.setAction(JSONArray.toJSONString(actions));
        taskService.updateById(task);
        return actions;
    }
}