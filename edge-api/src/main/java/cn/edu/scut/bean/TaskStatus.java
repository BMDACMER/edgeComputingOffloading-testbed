package cn.edu.scut.bean;

public enum TaskStatus {
    /**
     *  NEW: add new tasks
     *  SUCCESS: task success
     *  DROP: task timeout
     *  TRANSMISSION_FAILURE:   task transmission failure
     *  EXECUTION_FAILURE: task execution failure
     *  END: end of training
     *  CANCEL: If any one task executes successfully other task instances cancel execution, responsible for redundancy
     */
    NEW, SUCCESS, DROP, TRANSMISSION_FAILURE, EXECUTION_FAILURE, END, CANCEL
}
