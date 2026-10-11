package cn.zbx1425.mtrsteamloco.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModExecutors {
    private ModExecutors() {}

    public static final ExecutorService POOL = Executors.newWorkStealingPool();
}
