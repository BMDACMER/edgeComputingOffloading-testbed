package cn.edu.scut.bean;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Task {
    private Integer id;
    private Integer jobId;
    private Integer hop;
    private Integer finalExecutionClusterFound; // 0: false, 1: true
    private Integer timeSlot;
    // JSON string, to support array
    private Integer source;
    private Integer destination;
    private String action;
    private TaskStatus status;
    // KB
    private Long taskSize;
    private Integer taskComplexity;
    // cycle
    private Long cpuCycle;  // cpuCycle = taskSize * taskComplexity
    // s
    private Integer deadline;
    // ms
    private Integer transmissionTime;
    private Integer executionTime;
    private Integer transmissionWaitingTime;
    private Integer executionWaitingTime;

    @TableField(exist = false)
    private LocalDateTime arrivalTime;
    @TableField(exist = false)
    private LocalDateTime beginTransmissionTime;
    @TableField(exist = false)
    private LocalDateTime endTransmissionTime;
    @TableField(exist = false)
    private LocalDateTime beginExecutionTime;
    @TableField(exist = false)
    private LocalDateTime endExecutionTime;
    @TableField(exist = false)
    private Double transmissionFailureRate;
    @TableField(exist = false)
    private Double executionFailureRate;
    // JSON string, to support array
    private String availAction;
    // RD-DRL
    private Double taskReliability;
    private Double reliabilityRequirement;
    @TableField(exist = false)
    private Double executionReliability;
    @TableField(exist = false)
    private Double transmissionReliability;

    private String runtimeInfo;

    @TableField(exist = false)
    private Integer originalSource;

    //dynamic_redundancy_number值
    private Integer dynamicRedundancyNumber;
}
