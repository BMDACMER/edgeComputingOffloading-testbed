package cn.edu.scut.controller;

import cn.edu.scut.bean.EdgeNode;
import cn.edu.scut.bean.RatcVo;
import cn.edu.scut.bean.Task;
import cn.edu.scut.service.EdgeNodeSystemService;
import cn.edu.scut.thread.ExecutionRunnable;
import lombok.extern.apachecommons.CommonsLog;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequestMapping(value = "/edgeNode", method = {RequestMethod.GET, RequestMethod.POST})
@CommonsLog
public class EdgeNodeController {

    @Autowired
    private EdgeNodeSystemService edgeNodeSystemService;

    @PostMapping("/startExecution")
    public String postMethodName(@RequestBody String entity) {
        log.info(entity);
        return entity;
    }
    

    @PostMapping("/task")
    public String receiveEdgeNodeTask(@RequestBody Task task) {
        log.info("receive task "+task.getJobId()+" from edge node: " + task.getSource() + " ,hop is: " + task.getHop());
        task.setArrivalTime(LocalDateTime.now());
        edgeNodeSystemService.processTaskFromUser(task);
        return "success";
    }

    @GetMapping("/queue")
    public Integer queue() {
        return edgeNodeSystemService.getEdgeNodeSystem().getExecutionQueue().getSize();
    }

    @GetMapping("/info")
    public EdgeNode info() {
        return edgeNodeSystemService.getEdgeNodeSystem().getEdgeNode();
    }

    @GetMapping("/waitingTime")
    public int waitingTime() {
        var queue = edgeNodeSystemService.getEdgeNodeSystem().getExecutionQueue().getExecutor().getQueue();
        int waitingTime = 0;
        for (Runnable runnable : queue) {
            var r = (ExecutionRunnable) runnable;
            waitingTime += r.getTask().getExecutionTime();
        }
        return waitingTime;
    }

    @GetMapping("/ratc")
    public RatcVo ratc() {
        var queue = edgeNodeSystemService.getEdgeNodeSystem().getExecutionQueue().getExecutor().getQueue();
        int waitingTime = 0;
        for (Runnable runnable : queue) {
            var r = (ExecutionRunnable) runnable;
            waitingTime += r.getTask().getExecutionTime();
        }
        var res = new RatcVo();
        res.setWaitingTime(waitingTime);
        var edgeNode = edgeNodeSystemService.getEdgeNodeSystem().getEdgeNode();
        res.setExecutionFailureRate(edgeNode.getExecutionFailureRate());
        res.setCapacity(edgeNode.getCapacity());
        res.setEdgeId(edgeNode.getEdgeNodeId());
        return res;
    }

    @GetMapping("/available")
    public Integer avail() {
        int queueSize = edgeNodeSystemService.getEdgeNodeSystem().getExecutionQueue().getSize();
        if (queueSize < edgeNodeSystemService.getEdgeNodeSystem().getExecutionQueueThreshold()) {
            return 1;
        } else {
            return 0;
        }
    }
}